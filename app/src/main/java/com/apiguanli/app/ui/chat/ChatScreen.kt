package com.apiguanli.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.apiguanli.app.chat.ChatMessage
import com.apiguanli.app.chat.ChatRunner
import com.apiguanli.app.chat.ChatSettings
import com.apiguanli.app.chat.ChatStore
import com.apiguanli.app.chat.MsgMeta
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.keyById
import com.apiguanli.app.data.normalizeModelName
import com.apiguanli.app.probe.modelsMatch
import com.apiguanli.app.ui.ConfirmDialog
import com.apiguanli.app.ui.MessageBox
import com.apiguanli.app.ui.Prefs
import com.apiguanli.app.ui.copyToClipboard
import com.apiguanli.app.ui.fmtCount
import com.apiguanli.app.ui.fmtNum
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(convId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val convs by ChatStore.items.collectAsState()
    val conv = convs.firstOrNull { it.id == convId }
    val sites by SiteStore.sites.collectAsState()
    val running by ChatRunner.running.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // A conversation that was opened but never used is not worth keeping.
    DisposableEffect(convId) {
        onDispose { ChatStore.get(convId)?.takeIf { it.messages.isEmpty() }?.let { ChatStore.delete(it.id) } }
    }
    if (conv == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LaunchedEffect(conv.id) { if (conv.model.isBlank()) showPicker = true }

    val site = sites.firstOrNull { it.id == conv.siteId }
    val keyLabel = site?.keyById(conv.keyId)?.label
    val isRunning = conv.id in running
    val last = conv.messages.lastOrNull()

    // The list is laid out bottom-up, so it stays pinned to the newest message while a reply
    // grows or the keyboard opens; jump back down whenever a new message is added.
    LaunchedEffect(conv.messages.size) {
        if (conv.messages.isNotEmpty()) listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "参数") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, "删除对话") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("输入消息") },
                        maxLines = 6,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (isRunning) {
                        FilledIconButton(onClick = { ChatRunner.stop(conv.id) }, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.Default.Stop, "停止")
                        }
                    } else {
                        FilledIconButton(
                            onClick = { ChatRunner.send(conv.id, input); input = "" },
                            enabled = input.isNotBlank() && conv.model.isNotBlank() && site != null,
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedCard(
                onClick = { if (!isRunning) showPicker = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(conv.model.ifBlank { "选择模型" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(site?.name ?: "站点已删除", keyLabel?.let { "令牌 $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Default.UnfoldMore, "切换模型")
                }
            }
            if (conv.messages.isEmpty()) {
                MessageBox("发一条消息开始对话。\n按次计费的模型每条消息都会扣一次费用。")
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(conv.messages.asReversed(), key = { it.id }) { m ->
                        val streaming = isRunning && m.id == last?.id
                        if (m.role == "user") UserBubble(m) { copyToClipboard(context, m.content) }
                        else AssistantMessage(
                            m,
                            streaming = streaming,
                            canRegenerate = !isRunning && m.id == last?.id,
                            onCopy = { copyToClipboard(context, m.content) },
                            onRegenerate = { ChatRunner.regenerate(conv.id) },
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModelPicker(
            initialSiteId = conv.siteId,
            initialKeyId = conv.keyId,
            onPick = { s, k, m ->
                showPicker = false
                Prefs.setLastChat(s, k, m)
                ChatStore.update(conv.id, persist = conv.messages.isNotEmpty()) { it.copy(siteId = s, keyId = k, model = m) }
            },
            onDismiss = { showPicker = false },
        )
    }
    if (showSettings) {
        SettingsSheet(conv.settings, onChange = { st ->
            ChatStore.update(conv.id, persist = conv.messages.isNotEmpty()) { it.copy(settings = st) }
        }, onDismiss = { showSettings = false })
    }
    if (confirmDelete) {
        ConfirmDialog("删除对话？", "这个对话会被永久删除。", "删除", onConfirm = {
            ChatRunner.stop(conv.id)
            ChatStore.delete(conv.id)
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun UserBubble(m: ChatMessage, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            SelectionContainer {
                Text(
                    m.content,
                    modifier = Modifier.widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                Icons.Default.ContentCopy, "复制",
                modifier = Modifier.padding(top = 2.dp).size(28.dp).clip(RoundedCornerShape(50)).clickable(onClick = onCopy).padding(6.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    m: ChatMessage,
    streaming: Boolean,
    canRegenerate: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
) {
    var showReasoning by remember(m.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (m.reasoning.isNotBlank()) {
            // Expanded while the model is still only thinking.
            val expanded = showReasoning || (streaming && m.content.isEmpty())
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { showReasoning = !showReasoning }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("思考过程 · ${m.reasoning.length} 字", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                AnimatedVisibility(expanded) {
                    SelectionContainer {
                        Text(
                            m.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        )
                    }
                }
            }
        }
        if (m.content.isNotEmpty()) {
            SelectionContainer {
                val t = MaterialTheme.typography
                Markdown(
                    content = m.content,
                    typography = markdownTypography(
                        h1 = t.titleLarge, h2 = t.titleMedium, h3 = t.titleSmall,
                        h4 = t.bodyLarge, h5 = t.bodyLarge, h6 = t.bodyLarge,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (m.reasoning.isNotEmpty()) "思考中…" else "等待回复…", style = MaterialTheme.typography.bodySmall)
            }
        }
        m.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    metaText(m),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (m.content.isNotEmpty()) IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "复制", Modifier.size(18.dp)) }
                if (canRegenerate) IconButton(onClick = onRegenerate) { Icon(Icons.Default.Refresh, "重新生成", Modifier.size(18.dp)) }
            }
        }
    }
}

private fun secs(ms: Long) = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)

/** "返回 xxx · 入 120 / 出 350 · 首字 1.2s · 共 5.3s · 66 tok/s" */
private fun metaText(m: ChatMessage): String {
    val meta: MsgMeta = m.meta ?: return m.siteName
    val parts = mutableListOf<String>()
    val returned = meta.model
    // A dated name like "x-260910" coming back as "x" is the same model, not a swap.
    if (returned != null && !modelsMatch(normalizeModelName(m.model), normalizeModelName(returned))) parts += "⚠ 返回模型 $returned"
    if (meta.promptTokens != null || meta.completionTokens != null) {
        parts += "入 ${meta.promptTokens?.let(::fmtCount) ?: "?"} / 出 ${meta.completionTokens?.let(::fmtCount) ?: "?"}"
    }
    meta.ttftMs?.let { parts += "首字 ${secs(it)}" }
    parts += "共 ${secs(meta.totalMs)}"
    val genMs = meta.totalMs - (meta.ttftMs ?: 0)
    val out = meta.completionTokens
    if (out != null && out > 0 && genMs > 200) parts += "${(out * 1000 / genMs)} tok/s"
    if (meta.finishReason == "length") parts += "因长度截断"
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(settings: ChatSettings, onChange: (ChatSettings) -> Unit, onDismiss: () -> Unit) {
    var system by remember { mutableStateOf(settings.systemPrompt) }
    var maxTokens by remember { mutableStateOf(settings.maxTokens?.toString().orEmpty()) }
    fun commit(s: ChatSettings) = onChange(s.copy(systemPrompt = system, maxTokens = maxTokens.trim().toIntOrNull()?.takeIf { it > 0 }))

    ModalBottomSheet(onDismissRequest = { commit(settings); onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("对话参数", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = system,
                onValueChange = { system = it },
                label = { Text("系统提示词（可选）") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.temperature != null,
                    onCheckedChange = { on -> commit(settings.copy(temperature = if (on) 0.7 else null)) },
                )
                Text("自定义温度", Modifier.weight(1f))
                Text(settings.temperature?.let { fmtNum(it) } ?: "默认")
            }
            settings.temperature?.let { t ->
                Slider(
                    value = t.toFloat(),
                    onValueChange = { commit(settings.copy(temperature = Math.round(it * 10) / 10.0)) },
                    valueRange = 0f..2f,
                    steps = 19,
                )
            }
            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it.filter(Char::isDigit) },
                label = { Text("最大输出 tokens（留空=默认）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("流式输出")
                    Text("关闭后等全部生成完才显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.stream, onCheckedChange = { commit(settings.copy(stream = it)) })
            }
            Box(Modifier.size(1.dp))
        }
    }
}
