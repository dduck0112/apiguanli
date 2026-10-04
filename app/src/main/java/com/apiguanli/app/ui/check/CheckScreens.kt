package com.apiguanli.app.ui.check

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.ModelEntry
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.usableKeys
import com.apiguanli.app.data.protocol
import com.apiguanli.app.probe.Baselines
import com.apiguanli.app.probe.CheckRun
import com.apiguanli.app.probe.CheckRunner
import com.apiguanli.app.probe.CheckStore
import com.apiguanli.app.probe.ProbeMode
import com.apiguanli.app.probe.TargetResult
import com.apiguanli.app.probe.Verdict
import com.apiguanli.app.probe.canonicalModel
import com.apiguanli.app.ui.ConfirmDialog
import com.apiguanli.app.ui.LoadingBox
import com.apiguanli.app.ui.MessageBox
import com.apiguanli.app.ui.fmtAgo
import com.apiguanli.app.ui.fmtCny
import com.apiguanli.app.ui.fmtNum
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val MAX_TARGETS = 6

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFEF6C00)

@Composable
fun verdictColor(v: Verdict?): Color = when (v) {
    Verdict.PASS -> Green
    Verdict.WARN -> Amber
    Verdict.FAIL -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun verdictIcon(v: Verdict) = when (v) {
    Verdict.PASS -> "✅"
    Verdict.WARN -> "⚠️"
    Verdict.FAIL -> "❌"
    Verdict.SKIP -> "➖"
    Verdict.INFO -> "ℹ️"
}

/** What each check looks at, shown under its heading in the report. */
private val explanations = mapOf(
    "basic" to "发一句「只回复 ok」，看能否正常回答。",
    "model" to "响应里的 model 字段是否就是你请求的模型。这个字段很容易伪造，只能抓到偷懒的站。",
    "protocol" to "响应的 JSON 结构是否符合 OpenAI 规范。逆向渠道和拼凑的转发常缺字段。",
    "hidden" to "同一句话的输入 tokens 应该和对照一致。多出来的就是中转站偷偷加的系统提示词：会多扣费，也会改变模型的行为。",
    "tokenizer" to "在同一句话后面加一段固定的中英文、代码、表情混合文本，看输入 tokens 增加多少。不同家的模型分词方式不同，这个数几乎无法伪造；但同一家的大小模型（如 glm-5.3 和 glm-5.3-flash）数字相同，区分不了。",
    "thinking" to "对照渠道会返回思考过程时，这里也应该有。没有的话可能是思考被关掉了，答题质量会下降。",
    "stability" to "同一个问题、温度 0 问 3 次，回答长度应该差不多。波动很大可能是背后混用了多个上游。",
    "tools" to "让模型调用一个查天气的函数，看能否按格式调用。逆向渠道常常不支持。",
    "structured" to "要求按指定的 JSON 结构返回。",
    "stream" to "同一句话分别用流式和非流式请求，回答和计费 tokens 应该一致。",
    "capability" to "4 道有标准答案的题：大数乘法、数字母、倒写字符串、推算星期。被换成更弱的模型时容易答错。",
    "speed" to "首字延迟和输出速度，仅供参考。同一家的大模型通常比小模型慢。",
    "long" to "在约 2.2 万 tokens 的长文本开头、中间、末尾各藏一个暗号，让模型找出来。找不全或计费 tokens 明显偏少，说明上下文被截断了。",
)

// ---------------------------------------------------------------- history

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChecksScreen(onNew: () -> Unit, onOpen: (String) -> Unit) {
    val runs by CheckStore.runs.collectAsState()
    val running by CheckRunner.running.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("模型体检") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Default.Add, null) }, text = { Text("新体检") })
        },
    ) { padding ->
        if (runs.isEmpty()) {
            MessageBox(
                "检测中转站的模型有没有掺水：是不是被换成了别的模型、有没有偷塞提示词、上下文有没有被截断。\n\n" +
                    "把同一个模型在多个站点上一起测，再和你信得过的渠道对比，结果最准。",
                Modifier.padding(padding).padding(top = 32.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(runs, key = { it.id }) { run ->
                Card(onClick = { onOpen(run.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(run.baseModel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (run.id in running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            "${run.mode.label}检测 · ${run.targets.size} 个渠道 · ${fmtAgo(run.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            run.targets.forEach { t -> ScoreChip(t) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreChip(t: TargetResult) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            buildString {
                append(t.siteName)
                if (t.reference) append("（对照）")
                append(" ")
                append(
                    when (t.state) {
                        "done" -> "${t.score ?: "-"} ${t.verdict?.label.orEmpty()}"
                        "error" -> "失败"
                        "running" -> "检测中"
                        else -> "排队"
                    },
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (t.state == "done") verdictColor(t.verdict) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------- new checkup

private fun entryKey(e: ModelEntry) = "${e.site.id}|${e.name}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewCheckScreen(initialModel: String, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val sites by SiteStore.sites.collectAsState()
    // The checkup speaks OpenAI format only.
    val usable = sites.filter { it.usableKeys().isNotEmpty() && it.protocol == "openai" }
    var query by rememberSaveable { mutableStateOf(initialModel) }
    var entries by remember { mutableStateOf<List<ModelEntry>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reference by remember { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(ProbeMode.STANDARD) }

    LaunchedEffect(usable.map { it.id to it.customModels }) {
        entries = coroutineScope {
            usable.map { s -> async { runCatching { Repo.models(s) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }
    }
    val q = canonicalModel(query)
    // Typing a full model name pre-selects that model everywhere, with a trusted site as the reference.
    LaunchedEffect(q, entries) {
        val exact = entries.orEmpty().filter { q.isNotEmpty() && canonicalModel(it.name) == q }
        selected = exact.sortedByDescending { it.site.trusted }.take(MAX_TARGETS).map(::entryKey).toSet()
        reference = exact.firstOrNull { it.site.trusted }?.let(::entryKey)
    }

    val all = entries
    val matches = if (q.isEmpty() || all == null) emptyList()
    else all.filter { canonicalModel(it.name).contains(q) }
        .sortedWith(compareBy({ canonicalModel(it.name) != q }, { !it.site.trusted }, { it.site.name }))
        .take(60)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新体检") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        val chosen = all.orEmpty().filter { entryKey(it) in selected }
                        val run = CheckRun(
                            baseModel = query.trim(),
                            mode = mode,
                            targets = chosen.map { e ->
                                TargetResult(
                                    siteId = e.site.id, siteName = e.site.name, keyId = e.site.usableKeys().first().id,
                                    model = e.name, reference = entryKey(e) == reference,
                                )
                            },
                        )
                        CheckRunner.start(run)
                        onStarted(run.id)
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text("开始体检 · ${selected.size} 个渠道，每个约 ${mode.requests} 次请求") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("要检测的模型") },
                        placeholder = { Text("如 deepseek-v4.1-flash") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ProbeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = { mode = m },
                                shape = SegmentedButtonDefaults.itemShape(i, ProbeMode.entries.size),
                            ) { Text("${m.label} ${m.requests}次") }
                        }
                    }
                    Text(
                        when (mode) {
                            ProbeMode.QUICK -> "快速：模型名、接口格式、隐藏提示词、分词指纹、思考过程。最便宜，也是最能揭穿换模型的几项。"
                            ProbeMode.STANDARD -> "标准：在快速的基础上，加测稳定性、函数调用、结构化输出、流式一致性、能力题和速度。"
                            ProbeMode.DEEP -> "深度：在标准的基础上，再用约 2.2 万 tokens 的长文本测上下文有没有被截断。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val baseline = Baselines.find(query)
                    val hasRef = reference != null
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(
                            when {
                                hasRef -> "已选对照渠道：其他渠道会和它逐项对比。"
                                baseline != null -> "没有选对照渠道，将使用内置基线（火山方舟实测）对比分词指纹和隐藏提示词。"
                                else -> "没有对照：分词指纹和隐藏提示词只能给参考值。可以在站点「概览」里把信得过的渠道设为「可信对照」。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            when {
                usable.isEmpty() -> item { MessageBox("没有可用的 Key。请添加「URL + Key」站点，或在站点的「令牌」页填入 Key。") }
                all == null -> item { LoadingBox() }
                q.isEmpty() -> item { MessageBox("输入模型名，会列出所有站点里的同名模型（忽略 [特价] 这类前缀）。") }
                matches.isEmpty() -> item { MessageBox("没有找到这个模型。可以在站点「概览」里手动添加模型名。") }
                else -> items(matches, key = ::entryKey) { e ->
                    val k = entryKey(e)
                    val on = k in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (on) selected - k else if (selected.size < MAX_TARGETS) selected + k else selected
                            if (on && reference == k) reference = null
                        }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.site.name + if (e.site.trusted) " · 可信" else "", fontWeight = FontWeight.SemiBold)
                            Text(e.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(costText(e, mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (on) {
                            FilterChip(
                                selected = reference == k,
                                onClick = { reference = if (reference == k) null else k },
                                label = { Text("对照") },
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

private fun costText(e: ModelEntry, mode: ProbeMode): String {
    val row = e.price ?: return if (e.site.trusted) "价格未知（订阅渠道通常按次数计）" else "价格未知"
    return if (row.perCall) {
        val n = mode.requests
        "按次计费：约 $n 次 × ${fmtNum(row.callDisplay)} 额度" + (row.callCny?.let { " ≈ ${fmtCny(it * n)}" } ?: "")
    } else {
        "按量计费：" + if (mode == ProbeMode.DEEP) "约 2.5 万输入 tokens，费用较低" else "只用几千 tokens，费用很低"
    }
}

// ---------------------------------------------------------------- report

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CheckRunScreen(runId: String, onBack: () -> Unit) {
    val runs by CheckStore.runs.collectAsState()
    val running by CheckRunner.running.collectAsState()
    val run = runs.firstOrNull { it.id == runId }
    var confirmDelete by remember { mutableStateOf(false) }
    if (run == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val isRunning = run.id in running

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(run.baseModel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${run.mode.label}检测 · ${fmtAgo(run.createdAt)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (isRunning) IconButton(onClick = { CheckRunner.stop(run.id) }) { Icon(Icons.Default.Stop, "停止") }
                    else IconButton(onClick = {
                        CheckRunner.start(
                            CheckRun(baseModel = run.baseModel, mode = run.mode,
                                targets = run.targets.map { TargetResult(it.siteId, it.siteName, it.keyId, it.model, it.reference) }),
                        )
                        onBack()
                    }) { Icon(Icons.Default.Refresh, "重新检测") }
                    IconButton(onClick = { confirmDelete = true }, enabled = !isRunning) { Icon(Icons.Default.DeleteOutline, "删除") }
                },
            )
        },
    ) { padding ->
        // Item ids in the order they were checked, taken from whichever target got furthest.
        val itemIds = run.targets.maxByOrNull { it.items.size }?.items?.map { it.id }.orEmpty()
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(run.targets.size) { i -> TargetCard(run.targets[i]) }
            items(itemIds, key = { it }) { id ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val name = run.targets.firstNotNullOfOrNull { t -> t.items.firstOrNull { it.id == id }?.name } ?: id
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        explanations[id]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        run.targets.forEach { t ->
                            val it = t.items.firstOrNull { item -> item.id == id } ?: return@forEach
                            Row(verticalAlignment = Alignment.Top) {
                                Text(verdictIcon(it.verdict), modifier = Modifier.width(28.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.siteName + if (t.reference) "（对照）" else "",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = verdictColor(it.verdict),
                                    )
                                    Text(it.summary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "没有哪一项能 100% 定案：返回字段可以伪造，能力题只看概率。最有力的证据是「分词指纹」和「隐藏提示词」，以及和可信渠道的横向对比。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog("删除这次体检？", "检测记录会被永久删除。", "删除", onConfirm = { CheckStore.delete(run.id) }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun TargetCard(t: TargetResult) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (t.reference) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.siteName + if (t.reference) " · 对照" else "", fontWeight = FontWeight.SemiBold)
                Text(t.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val sub = when (t.state) {
                    "running" -> "检测中：${t.progress}"
                    "pending" -> "排队中"
                    "error" -> "检测失败：${t.error}"
                    else -> listOfNotNull(
                        t.probes?.requestCount?.let { "$it 次请求" },
                        t.baselineSource?.let { "对比：$it" } ?: "无对照",
                    ).joinToString(" · ")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = if (t.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            when (t.state) {
                "running" -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                "done" -> Column(horizontalAlignment = Alignment.End) {
                    Text("${t.score ?: "-"}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = verdictColor(t.verdict))
                    Text(t.verdict?.label.orEmpty(), style = MaterialTheme.typography.labelMedium, color = verdictColor(t.verdict))
                }
                else -> Unit
            }
        }
    }
}
