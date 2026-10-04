package com.apiguanli.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.toCny
import com.apiguanli.app.data.toDisplay
import com.apiguanli.app.net.LogItem
import kotlinx.coroutines.CancellationException

private val logTypes = listOf(0 to "全部", 2 to "消费", 1 to "充值", 4 to "系统", 5 to "错误", 6 to "退款")
private const val PAGE_SIZE = 30

@Composable
fun LogsTab(site: Site) {
    var type by rememberSaveable { mutableIntStateOf(2) }
    var input by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    val items = remember { mutableStateListOf<LogItem>() }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped to restart loading from page 1 when filters change.
    var generation by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current

    suspend fun loadNext() {
        if (loading) return
        loading = true
        error = null
        val gen = generation
        try {
            val r = Repo.client(site).logs(page + 1, PAGE_SIZE, type, model)
            if (gen == generation) {
                items += r.items
                total = r.total
                page += 1
                if (r.items.isEmpty()) total = items.size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == generation) error = e.message ?: "未知错误"
        } finally {
            // A stale request (filters changed meanwhile) must not unlock the new one.
            if (gen == generation) loading = false
        }
    }

    LaunchedEffect(site.id, type, model) {
        generation++
        items.clear()
        total = 0
        page = 0
        loading = false
        loadNext()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            logTypes.forEach { (value, label) ->
                FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("按模型名筛选") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (input.isNotEmpty()) IconButton(onClick = { input = ""; model = "" }) { Icon(Icons.Default.Clear, "清除") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { model = input.trim(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "共 $total 条" + if (model.isNotBlank()) " · 模型含「$model」" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(items, key = { "${it.id}-${it.createdAt}-${it.type}" }) { log ->
                LogRow(site, log)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败：$error", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { error = null }) { Text("重试") }
                        }
                        items.size < total || (page == 0) -> {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            LaunchedEffect(items.size, page, error) { if (error == null) loadNext() }
                        }
                        items.isEmpty() -> Text("没有记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("没有更多了", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun LogRow(site: Site, log: LogItem) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (log.type == 2) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    log.modelName.ifBlank { "（未知模型）" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val display = site.toDisplay(log.quota)
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (log.quota == 0L) "免费" else "-${fmtNum(display)} 额度", style = MaterialTheme.typography.bodyMedium)
                    if (log.quota != 0L) site.toCny(display)?.let {
                        Text(fmtCny(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val parts = buildList {
                add(fmtTime(log.createdAt))
                if (log.tokenName.isNotBlank()) add(log.tokenName)
                add("入 ${fmtCount(log.promptTokens)} / 出 ${fmtCount(log.completionTokens)}")
                add("${log.useTime}s")
                if (log.isStream) add("流式")
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (log.content.isNotBlank()) {
                Text(log.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            val label = logTypes.firstOrNull { it.first == log.type }?.second ?: "类型 ${log.type}"
            Text(log.content.ifBlank { label }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${fmtTime(log.createdAt)} · $label",
                style = MaterialTheme.typography.bodySmall,
                color = if (log.type == 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Key-only sites: the site returns this key's recent logs in one list, without filters. */
@Composable
fun KeyLogsTab(site: Site) {
    var logs by remember { mutableStateOf<List<LogItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(site.id, reload) {
        error = null
        runCatching { Repo.client(site).keyLogs() }.onSuccess { logs = it }.onFailure { error = it.message }
    }
    val list = logs
    when {
        list == null && error == null -> LoadingBox()
        list == null -> MessageBox("加载失败：$error", actionText = "重试") { reload++ }
        list.isEmpty() -> MessageBox("这个 Key 还没有使用记录", actionText = "刷新") { reload++ }
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "这个 Key 最近 ${list.size} 条记录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(list, key = { "${it.id}-${it.createdAt}" }) { log ->
                LogRow(site, log)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
