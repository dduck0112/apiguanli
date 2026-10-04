package com.apiguanli.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.ModelEntry
import com.apiguanli.app.data.PriceRow
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.lineUrl
import com.apiguanli.app.data.hasCustomApiPath
import com.apiguanli.app.data.isKeyOnly
import com.apiguanli.app.data.protocol
import com.apiguanli.app.data.testUrl
import com.apiguanli.app.data.usableKeys
import com.apiguanli.app.net.ModelTester
import com.apiguanli.app.net.TestResult
import com.apiguanli.app.ui.chat.ChatLauncher
import kotlinx.coroutines.launch

private val Green = Color(0xFF2E7D32)
private val Gold = Color(0xFFF9A825)

private enum class ModelSort { NAME, PRICE }

@Composable
fun ModelsTab(site: Site, onChat: (String) -> Unit, onCheck: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ModelEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ModelSort.NAME) }
    var onlyFav by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf<ModelEntry?>(null) }

    LaunchedEffect(site.id, site.rechargeRate, site.balance?.group, reload) {
        error = null
        runCatching { Repo.models(site, force = reload > 0) }
            .onSuccess { entries = it }
            .onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索模型") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "清除") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = onlyFav,
                onClick = { onlyFav = !onlyFav },
                label = { Text("收藏 ${site.favoriteModels.size}") },
                leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp), tint = Gold) },
            )
            FilterChip(selected = sort == ModelSort.NAME, onClick = { sort = ModelSort.NAME }, label = { Text("按名称") })
            FilterChip(selected = sort == ModelSort.PRICE, onClick = { sort = ModelSort.PRICE }, label = { Text("按价格") })
        }
        val all = entries
        when {
            all == null && error == null -> LoadingBox()
            all == null -> MessageBox("加载失败：$error", actionText = "重试") { reload++ }
            else -> {
                val q = query.trim().lowercase()
                val shown = all
                    .filter { (q.isEmpty() || it.name.lowercase().contains(q)) && (!onlyFav || it.name in site.favoriteModels) }
                    .sortedWith(
                        compareBy<ModelEntry> { it.name !in site.favoriteModels }.let { byFav ->
                            if (sort == ModelSort.PRICE) byFav.thenBy { it.price?.perCall != true }.thenBy { it.price?.sortCny ?: Double.MAX_VALUE }
                            else byFav
                        }.thenBy { it.price?.baseName ?: it.name.lowercase() },
                    )
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(
                            "${shown.size} 个模型 · ☆ 收藏的排在最前 · 点模型可测试或对话" +
                                if (site.rechargeRate == null) " · 未设置充值比例，只显示额度" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (shown.isEmpty() && onlyFav) {
                        item { MessageBox("还没有收藏。点模型左边的 ☆ 收藏常用模型。") }
                    }
                    items(shown, key = { it.name }) { e ->
                        ModelEntryItem(
                            e,
                            favorite = e.name in site.favoriteModels,
                            onStar = { scope.launch { Repo.toggleFavorite(site.id, e.name) } },
                        ) { testing = e }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }

    testing?.let { TestDialog(it, onChat = onChat, onCheck = onCheck) { testing = null } }
}

@Composable
fun FavoriteButton(favorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        if (favorite) Icon(Icons.Default.Star, "取消收藏", tint = Gold)
        else Icon(Icons.Default.StarBorder, "收藏", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelEntryItem(e: ModelEntry, favorite: Boolean, onStar: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FavoriteButton(favorite, onStar)
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (favorite) FontWeight.SemiBold else null)
            val row = e.price
            Text(
                when {
                    row == null -> "站点未公布价格"
                    else -> (if (row.perCall) "按次计费" else "按量计费") +
                        if (row.groupRatio != 1.0) " · 分组倍率 ${fmtNum(row.groupRatio)}" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        e.price?.let {
            Spacer(Modifier.width(8.dp))
            PriceColumn(it)
        }
    }
}

/** One-line price, e.g. "¥0.12/次" or "入 ¥1.8 · 出 ¥9 /百万". */
fun PriceRow.shortText(): String {
    val free = if (perCall) model.modelPrice == 0.0 else model.modelRatio == 0.0
    return when {
        free -> "免费"
        perCall -> callCny?.let { "${fmtCny(it)}/次" } ?: "${fmtNum(callDisplay)} 额度/次"
        inputCny != null -> "入 ${fmtCny(inputCny)} · 出 ${fmtCny(outputCny)} /百万"
        else -> "入 ${fmtNum(inputDisplay)} · 出 ${fmtNum(outputDisplay)} 额度/百万"
    }
}

/** Price in real 元 (when known) with the display-unit price underneath. */
@Composable
fun PriceColumn(row: PriceRow) {
    Column(horizontalAlignment = Alignment.End) {
        val free = if (row.perCall) row.model.modelPrice == 0.0 else row.model.modelRatio == 0.0
        when {
            free -> Text("免费", style = MaterialTheme.typography.bodyMedium, color = Green)
            row.perCall -> {
                Text(row.callCny?.let { "${fmtCny(it)}/次" } ?: "${fmtNum(row.callDisplay)} 额度/次", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (row.callCny != null) Text("${fmtNum(row.callDisplay)} 额度/次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val main = if (row.inputCny != null) "入 ${fmtCny(row.inputCny)} · 出 ${fmtCny(row.outputCny)}"
                else "入 ${fmtNum(row.inputDisplay)} · 出 ${fmtNum(row.outputDisplay)} 额度"
                Text(main, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("每百万 tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A price row in the cross-site comparison list. */
@Composable
fun PriceRowItem(row: PriceRow, favorite: Boolean, onStar: () -> Unit, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FavoriteButton(favorite, onStar)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.site.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = Green)
                }
            }
            Text(row.model.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        PriceColumn(row)
    }
}

/** Sends one tiny real request with a saved key to check the model works; can also open a chat. */
@Composable
fun TestDialog(entry: ModelEntry, onChat: ((String) -> Unit)? = null, onCheck: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    val site = entry.site
    val row = entry.price
    val scope = rememberCoroutineScope()
    val keys = site.usableKeys()
    var selected by remember { mutableStateOf(keys.firstOrNull()?.id) }
    // A key site with its own endpoint only speaks the format it was added with.
    val endpoints = if (site.isKeyOnly && site.hasCustomApiPath) listOf(site.protocol)
    else entry.endpoints.filter { it in ModelTester.supported }.ifEmpty { listOf("openai") }
    var endpoint by remember {
        mutableStateOf(if (site.isKeyOnly && site.protocol in endpoints) site.protocol else ModelTester.pickEndpoint(endpoints))
    }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TestResult?>(null) }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(entry.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${site.name} · ${site.lineUrl}", style = MaterialTheme.typography.bodySmall)
                if (keys.isEmpty()) {
                    Text(
                        "这个站点还没有保存完整 Key。请先到站点的「令牌」页填入一个 Key。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    if (keys.size > 1) {
                        Text("使用的令牌", style = MaterialTheme.typography.labelMedium)
                        keys.forEach { k ->
                            Row(Modifier.fillMaxWidth().clickable { selected = k.id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected == k.id, onClick = { selected = k.id })
                                Text(k.label)
                            }
                        }
                    }
                    if (endpoints.size > 1) {
                        Text("测试用的接口格式", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            endpoints.forEach { e -> FilterChip(selected = endpoint == e, onClick = { endpoint = e }, label = { Text(e) }) }
                        }
                    }
                    val cost = when {
                        row == null -> "站点未公布价格，测试会按站点规则扣费。"
                        row.perCall -> "测试会按次扣费：${fmtNum(row.callDisplay)} 额度" + (row.callCny?.let { "（约 ${fmtCny(it)}）" } ?: "")
                        else -> "按量计费，测试只消耗几十个 tokens，费用很低。"
                    }
                    Text(cost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("请求中…（最长等 2 分钟）")
                    }
                }
                result?.let { r ->
                    Text(
                        (if (r.ok) "✅ 可用" else "❌ 失败") + " · 耗时 ${fmtNum(r.latencyMs / 1000.0)} 秒" +
                            if (r.httpCode > 0) " · HTTP ${r.httpCode}" else "",
                        color = if (r.ok) Green else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(r.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // The checkup speaks OpenAI format only.
                if (onCheck != null && keys.isNotEmpty() && (!site.isKeyOnly || site.protocol == "openai")) {
                    TextButton(enabled = !running, onClick = { onDismiss(); onCheck(entry.name) }) { Text("体检") }
                }
                if (onChat != null && keys.isNotEmpty()) {
                    TextButton(enabled = !running, onClick = {
                        val keyId = selected ?: keys.first().id
                        onDismiss()
                        onChat(ChatLauncher.create(site.id, keyId, entry.name))
                    }) { Text("去对话") }
                }
                Button(
                    enabled = !running && selected != null,
                    onClick = {
                        val key = keys.firstOrNull { it.id == selected }?.key ?: return@Button
                        running = true
                        result = null
                        scope.launch {
                            result = ModelTester.test(site.testUrl(endpoint, entry.name), key, entry.name, endpoint)
                            running = false
                        }
                    },
                ) { Text(if (result == null) "测试" else "再测") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("关闭") } },
    )
}
