package com.apiguanli.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.lineUrl
import com.apiguanli.app.data.savedKey
import com.apiguanli.app.data.toDisplay
import com.apiguanli.app.net.TokenItem
import kotlinx.coroutines.launch

/** Sites whose API can't reveal full keys (checked once per app run). */
private val revealUnsupported = mutableSetOf<String>()

@Composable
fun TokensTab(site: Site) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tokens by remember { mutableStateOf<List<TokenItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pasteFor by remember { mutableStateOf<TokenItem?>(null) }
    val appendV1 by Prefs.appendV1

    LaunchedEffect(site.id, reload) {
        error = null
        runCatching { Repo.client(site).tokens().items }
            .onSuccess { tokens = it }
            .onFailure { error = it.message }
    }

    /** Full key if we have it: saved by the user, or returned unmasked by older sites. */
    fun keyOf(t: TokenItem): String? =
        site.savedKey(t.id) ?: t.key.takeIf { !t.isMasked && it.isNotBlank() }?.let { if (it.startsWith("sk-")) it else "sk-$it" }

    fun obtainKey(t: TokenItem) {
        if (site.id in revealUnsupported) { pasteFor = t; return }
        scope.launch {
            val key = runCatching { Repo.client(site).revealKey(t.id) }.getOrNull()
            if (key == null) {
                revealUnsupported += site.id
                pasteFor = t
            } else {
                Repo.saveKey(site.id, t.id, t.name, key)
                copyToClipboard(context, key, sensitive = true, label = "已获取并复制 Key")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LineSelector(site, appendV1)
        when {
            tokens == null && error == null -> LoadingBox()
            tokens == null -> MessageBox("加载失败：$error", actionText = "重试") { reload++ }
            tokens!!.isEmpty() -> MessageBox("这个账号还没有令牌，请先在网页上创建。", actionText = "刷新") { reload++ }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tokens!!, key = { it.id }) { t ->
                    TokenCard(
                        site = site,
                        token = t,
                        fullKey = keyOf(t),
                        onCopyKey = { key -> copyToClipboard(context, key, sensitive = true, label = "已复制 Key") },
                        onCopyUrl = { copyToClipboard(context, site.copyUrl(appendV1), label = "已复制地址") },
                        onCopyAll = { key ->
                            copyToClipboard(context, "Base URL: ${site.copyUrl(appendV1)}\nAPI Key: $key", sensitive = true, label = "已复制地址和 Key")
                        },
                        onObtainKey = { obtainKey(t) },
                        onEditKey = { pasteFor = t },
                        onRemoveKey = { scope.launch { Repo.removeKey(site.id, t.id) } },
                    )
                }
                item {
                    Text(
                        "这个站点不能再次查看已创建令牌的完整 Key 时，需要你把 Key 粘贴进来保存一次，之后就能一键复制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    pasteFor?.let { t ->
        KeyPasteDialog(
            token = t,
            initial = site.savedKey(t.id).orEmpty(),
            onDismiss = { pasteFor = null },
            onSave = { key ->
                pasteFor = null
                scope.launch { Repo.saveKey(site.id, t.id, t.name, key) }
                toast(context, "已保存「${t.name}」的 Key")
            },
        )
    }
}

@Composable
private fun LineSelector(site: Site, appendV1: Boolean) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val current = site.apiLines.firstOrNull { it.url == site.lineUrl }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("复制用的线路：${current?.name ?: "站点地址"}", style = MaterialTheme.typography.labelMedium)
                        Text(
                            site.copyUrl(appendV1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Default.ArrowDropDown, null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                site.apiLines.forEach { line ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(line.name)
                                Text(line.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            open = false
                            scope.launch { SiteStore.modify(site.id) { it.copy(selectedLineUrl = line.url) } }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = appendV1, onClick = { Prefs.setAppendV1(!appendV1) }, label = { Text("加 /v1") })
    }
}

private fun statusLabel(status: Int): Pair<String, Color> = when (status) {
    1 -> "启用" to Color(0xFF2E7D32)
    2 -> "已禁用" to Color(0xFF757575)
    3 -> "已过期" to Color(0xFFC62828)
    4 -> "已耗尽" to Color(0xFFEF6C00)
    else -> "状态 $status" to Color(0xFF757575)
}

@Composable
private fun TokenCard(
    site: Site,
    token: TokenItem,
    fullKey: String?,
    onCopyKey: (String) -> Unit,
    onCopyUrl: () -> Unit,
    onCopyAll: (String) -> Unit,
    onObtainKey: () -> Unit,
    onEditKey: () -> Unit,
    onRemoveKey: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(token.name.ifBlank { "未命名" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                val (label, color) = statusLabel(token.status)
                Text(label, color = color, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                if (site.savedKeys.containsKey(token.id.toString())) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("修改已保存的 Key") }, onClick = { menu = false; onEditKey() })
                            DropdownMenuItem(text = { Text("删除已保存的 Key") }, onClick = { menu = false; onRemoveKey() })
                        }
                    }
                }
            }
            Text(
                fullKey?.let { maskKey(it) } ?: "${token.key}（未保存完整 Key）",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = if (fullKey == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val remain = if (token.unlimited) "无限" else "${fmtNum(site.toDisplay(token.remainQuota))} 额度"
            Text(
                "已用 ${fmtNum(site.toDisplay(token.usedQuota))} 额度 · 剩余 $remain" +
                    (if (token.group.isNotBlank()) " · 分组 ${token.group}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "最近使用 ${fmtTime(token.accessedTime)}" +
                    (if (token.expiredTime > 0) " · 到期 ${fmtTime(token.expiredTime)}" else " · 永不过期"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 4.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fullKey != null) {
                    FilledTonalButton(onClick = { onCopyKey(fullKey) }) { Text("复制 Key") }
                    OutlinedButton(onClick = onCopyUrl) { Text("复制地址") }
                    OutlinedButton(onClick = { onCopyAll(fullKey) }) { Text("全部") }
                } else {
                    Button(onClick = onObtainKey) { Text("填入 Key") }
                    OutlinedButton(onClick = onCopyUrl) { Text("复制地址") }
                }
            }
        }
    }
}

@Composable
private fun KeyPasteDialog(token: TokenItem, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    val key = text.trim()
    val mismatch = key.isNotEmpty() && token.isMasked && !token.matches(key)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填入「${token.name}」的 Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "这个站点不能再查看已创建令牌的完整 Key。把你保存的完整 Key 粘贴到这里，App 会加密保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("完整 Key（sk-…）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                AssistChip(onClick = { pasteFromClipboard(context)?.let { text = it } }, label = { Text("从剪贴板粘贴") })
                if (mismatch) {
                    Text(
                        "站点显示这个令牌是「${token.key}」，你填的首尾对不上，确认没贴错吗？",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(key) }, enabled = key.length >= 8) { Text(if (mismatch) "仍然保存" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun pasteFromClipboard(context: android.content.Context): String? =
    context.getSystemService(android.content.ClipboardManager::class.java)
        ?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
