package com.apiguanli.app.probe

import android.content.Context
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.keyById
import com.apiguanli.app.data.chatUrl
import com.apiguanli.app.data.protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/** Saved checkup runs, newest first, in one private JSON file. */
object CheckStore {
    private const val KEEP = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private lateinit var file: File
    private val _runs = MutableStateFlow<List<CheckRun>>(emptyList())
    val runs: StateFlow<List<CheckRun>> = _runs

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "checks.json")
        _runs.value = runCatching { json.decodeFromString<List<CheckRun>>(file.readText()) }.getOrDefault(emptyList())
            // A run that was in progress when the app was killed can't resume.
            .map { r -> r.copy(targets = r.targets.map { t -> if (t.state == "done" || t.state == "error") t else t.copy(state = "error", error = "已中断") }) }
    }

    fun get(id: String) = _runs.value.firstOrNull { it.id == id }

    @Synchronized
    fun put(run: CheckRun, persist: Boolean = true) {
        _runs.value = (listOf(run) + _runs.value.filterNot { it.id == run.id }).sortedByDescending { it.createdAt }.take(KEEP)
        if (persist) save()
    }

    @Synchronized
    fun update(id: String, persist: Boolean = true, change: (CheckRun) -> CheckRun) {
        get(id)?.let { put(change(it), persist) }
    }

    @Synchronized
    fun delete(id: String) {
        _runs.value = _runs.value.filterNot { it.id == id }
        save()
    }

    private fun save() {
        val snapshot = _runs.value
        io.launch {
            val tmp = File(file.parentFile, "checks.json.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

/** Runs checkups in the background so they survive leaving the screen. */
object CheckRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running

    /** Saves [run] and starts it. The reference target (if any) goes first so others can be compared to it. */
    fun start(run: CheckRun) {
        val ordered = run.copy(targets = run.targets.sortedByDescending { it.reference })
        CheckStore.put(ordered)
        _running.value = _running.value + run.id
        jobs[run.id] = scope.launch {
            try {
                ordered.targets.indices.forEach { i -> runTarget(run.id, i) }
            } finally {
                jobs.remove(run.id)
                _running.value = _running.value - run.id
            }
        }
    }

    fun stop(runId: String) {
        jobs[runId]?.cancel()
    }

    private fun patch(runId: String, index: Int, persist: Boolean = false, change: (TargetResult) -> TargetResult) =
        CheckStore.update(runId, persist) { r -> r.copy(targets = r.targets.mapIndexed { i, t -> if (i == index) change(t) else t }) }

    private suspend fun runTarget(runId: String, index: Int) {
        val run = CheckStore.get(runId) ?: return
        val t = run.targets[index]
        val site = SiteStore.get(t.siteId)
        val key = site?.keyById(t.keyId)
        if (site == null || key == null) {
            patch(runId, index, true) { it.copy(state = "error", error = if (site == null) "站点已被删除" else "没有可用的 Key") }
            return
        }
        if (site.protocol != "openai") {
            patch(runId, index, true) { it.copy(state = "error", error = "体检目前只支持 OpenAI 格式的接口") }
            return
        }
        patch(runId, index) { it.copy(state = "running", progress = "准备中") }
        try {
            val probes = ProbeRunner(ProbeTarget(site.chatUrl, key.key, t.model)) { step ->
                patch(runId, index) { it.copy(progress = step) }
            }.run(run.mode)
            // The reference itself is judged against the built-in baseline; others against the reference.
            val ref = CheckStore.get(runId)?.targets?.firstOrNull { it.reference && it.state == "done" }?.probes
            val e = Evaluator.evaluate(t.model, probes, if (t.reference) null else ref)
            patch(runId, index, true) {
                it.copy(state = "done", progress = "", probes = probes, items = e.items, score = e.score,
                    verdict = e.verdict, baselineSource = e.baselineSource)
            }
        } catch (e: CancellationException) {
            patch(runId, index, true) { it.copy(state = "error", error = "已停止") }
            throw e
        } catch (e: Exception) {
            patch(runId, index, true) { it.copy(state = "error", error = e.message ?: e.javaClass.simpleName) }
        }
    }
}
