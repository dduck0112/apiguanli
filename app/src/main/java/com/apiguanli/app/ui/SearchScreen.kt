package com.apiguanli.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.ModelEntry
import com.apiguanli.app.data.PriceRow
import com.apiguanli.app.data.normalizeModelName
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.buildPriceRows
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val MAX_GROUPS = 40

/** One comparable bucket: same model (prefix stripped) and same billing type. */
private data class Group(val baseName: String, val perCall: Boolean, val rows: List<PriceRow>)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onChat: (String) -> Unit, onCheck: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val sites by SiteStore.sites.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<PriceRow>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf<PriceRow?>(null) }

    val priceKey = sites.map { Triple(it.id, it.rechargeRate, it.balance?.group) }
    LaunchedEffect(priceKey, reload) {
        loading = true
        val results = coroutineScope {
            sites.map { site ->
                async { site to runCatching { buildPriceRows(site, Repo.pricing(site, force = reload > 0)) } }
            }.awaitAll()
        }
        rows = results.flatMap { it.second.getOrNull().orEmpty() }
        errors = results.mapNotNull { (site, r) -> r.exceptionOrNull()?.let { "${site.name}：${it.message}" } }
        loading = false
    }

    val q = query.trim().lowercase()
    val groups = remember(rows, q) {
        if (q.isEmpty()) emptyList()
        else rows.filter { it.baseName.contains(q) || it.model.name.lowercase().contains(q) }
            .groupBy { it.baseName to it.perCall }
            .map { (k, list) ->
                Group(k.first, k.second, list.sortedWith(compareBy({ it.sortCny ?: Double.MAX_VALUE }, { it.site.name })))
            }
            // Exact name matches first, then models offered by more sites.
            .sortedWith(compareBy({ it.baseName != q }, { -it.rows.map { r -> r.site.id }.distinct().size }, { it.baseName }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型比价") },
                actions = { IconButton(onClick = { reload++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "刷新价格") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("输入模型名，如 claude-sonnet-5") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "清除") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            errors.forEach {
                Text("价格加载失败 · $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            when {
                sites.isEmpty() -> MessageBox("还没有站点，先去首页添加站点吧。")
                q.isEmpty() -> Column {
                    // Starred models across all sites, by name without prefixes: one tap to compare.
                    val favNames = sites.flatMap { site -> site.favoriteModels.map(::normalizeModelName) }.distinct().sorted()
                    if (favNames.isNotEmpty()) {
                        Text("我的收藏", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        FlowRow(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            favNames.forEach { n ->
                                AssistChip(
                                    onClick = { query = n },
                                    label = { Text(n) },
                                    leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = Color(0xFFF9A825)) },
                                )
                            }
                        }
                    }
                    MessageBox(
                    "在 ${sites.size} 个站点的 ${rows.size} 个模型里搜索。\n" +
                        "模型名前的 [特价E]、[满血B] 这类前缀会被忽略，同一个模型会放在一起比较。\n" +
                        "价格按各站点的充值比例换算成人民币后排序。",
                    )
                }
                groups.isEmpty() && !loading -> MessageBox("没有找到包含「$query」的模型")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    groups.take(MAX_GROUPS).forEach { g ->
                        item(key = "h-${g.baseName}-${g.perCall}") {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "${g.baseName} · ${if (g.perCall) "按次" else "按量（每百万 tokens）"} · ${g.rows.size} 个",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        val cheapest = g.rows.firstOrNull()?.takeIf { it.sortCny != null && g.rows.size > 1 }
                        items(g.rows, key = { "${it.site.id}-${it.model.name}-${g.perCall}" }) { row ->
                            val fav = sites.firstOrNull { it.id == row.site.id }?.favoriteModels?.contains(row.model.name) == true
                            PriceRowItem(
                                row,
                                favorite = fav,
                                onStar = { scope.launch { Repo.toggleFavorite(row.site.id, row.model.name) } },
                                badge = if (row === cheapest) "最便宜" else null,
                            ) { testing = row }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                    if (groups.size > MAX_GROUPS) {
                        item { MessageBox("结果太多，只显示前 $MAX_GROUPS 组，请输入更完整的模型名") }
                    }
                    if (rows.any { it.site.rechargeRate == null }) {
                        item {
                            Text(
                                "有站点没设置充值比例，它们只显示额度、排在后面。到站点「概览」页设置后即可参与比价。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    testing?.let { r -> TestDialog(ModelEntry(r.site, r.model.name, r, r.model.endpoints), onChat = onChat, onCheck = onCheck) { testing = null } }
}
