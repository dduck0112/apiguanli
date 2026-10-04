package com.apiguanli.app.chat

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

/**
 * Sends chat turns. Lives outside any screen so a reply keeps streaming
 * when the user navigates away, and can be stopped from anywhere.
 */
object ChatRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running

    /** UI refresh interval while streaming; the file is only written when a reply ends. */
    private const val FLUSH_MS = 80L

    fun send(convId: String, text: String) {
        if (convId in jobs) return
        ChatStore.update(convId) { c ->
            val title = if (c.messages.none { it.role == "user" }) text.trim().replace('\n', ' ').take(24) else c.title
            c.copy(title = title, messages = c.messages + ChatMessage(role = "user", content = text.trim()), updatedAt = now())
        }
        start(convId)
    }

    /** Drops the replies after the last user message and asks again. */
    fun regenerate(convId: String) {
        if (convId in jobs) return
        ChatStore.update(convId) { c ->
            val lastUser = c.messages.indexOfLast { it.role == "user" }
            if (lastUser < 0) c else c.copy(messages = c.messages.take(lastUser + 1))
        }
        start(convId)
    }

    fun stop(convId: String) {
        jobs[convId]?.cancel()
    }

    private fun start(convId: String) {
        val conv = ChatStore.get(convId) ?: return
        if (conv.messages.lastOrNull()?.role != "user") return
        val site = SiteStore.get(conv.siteId)
        val key = site?.keyById(conv.keyId)
        // Failed replies are kept on screen but not sent back as context.
        val history = conv.messages.filter { it.error == null && (it.role == "user" || it.content.isNotBlank()) }
        val reply = ChatMessage(role = "assistant", content = "", model = conv.model, siteName = site?.name.orEmpty())
        ChatStore.update(convId, persist = false) { it.copy(messages = it.messages + reply, updatedAt = now()) }

        fun patch(persist: Boolean, change: (ChatMessage) -> ChatMessage) =
            ChatStore.update(convId, persist) { c ->
                c.copy(messages = c.messages.map { if (it.id == reply.id) change(it) else it }, updatedAt = now())
            }

        if (site == null || key == null) {
            patch(true) { it.copy(error = if (site == null) "站点已被删除" else "这个站点没有可用的 Key，请先在「令牌」页填入 Key") }
            return
        }

        _running.value = _running.value + convId
        jobs[convId] = scope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var meta: MsgMeta? = null
            var lastFlush = 0L
            try {
                ChatClient.send(site.chatUrl, site.protocol, key.key, conv.model, conv.settings, history).collect { ev ->
                    when (ev) {
                        is ChatEvent.Delta -> {
                            content.append(ev.content)
                            reasoning.append(ev.reasoning)
                            if (now() - lastFlush > FLUSH_MS) {
                                lastFlush = now()
                                patch(false) { it.copy(content = content.toString(), reasoning = reasoning.toString()) }
                            }
                        }
                        is ChatEvent.Done -> meta = ev.meta
                    }
                }
                patch(true) {
                    it.copy(
                        content = content.toString(),
                        reasoning = reasoning.toString(),
                        meta = meta,
                        error = if (content.isEmpty() && reasoning.isEmpty()) "模型没有返回任何内容" else null,
                    )
                }
            } catch (e: CancellationException) {
                patch(true) { it.copy(content = content.toString(), reasoning = reasoning.toString(), error = "已停止") }
                throw e
            } catch (e: Exception) {
                patch(true) {
                    it.copy(content = content.toString(), reasoning = reasoning.toString(), error = e.message ?: e.javaClass.simpleName)
                }
            } finally {
                jobs.remove(convId)
                _running.value = _running.value - convId
            }
        }
    }

    private fun now() = System.currentTimeMillis()
}
