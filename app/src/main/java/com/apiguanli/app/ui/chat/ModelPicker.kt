package com.apiguanli.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apiguanli.app.data.ModelEntry
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.keyById
import com.apiguanli.app.data.usableKeys
import com.apiguanli.app.ui.FavoriteButton
import com.apiguanli.app.ui.LoadingBox
import com.apiguanli.app.ui.MessageBox
import com.apiguanli.app.ui.shortText
import kotlinx.coroutines.launch

/** Full-screen picker for site, key and model. Starred models are listed first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    initialSiteId: String?,
    initialKeyId: String?,
    onPick: (siteId: String, keyId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sites by SiteStore.sites.collectAsState()
    val usable = sites.filter { it.usableKeys().isNotEmpty() }
    var siteId by remember { mutableStateOf(initialSiteId?.takeIf { id -> usable.any { it.id == id } } ?: usable.firstOrNull()?.id) }
    val site = usable.firstOrNull { it.id == siteId }
    var keyId by remember(siteId) { mutableStateOf(site?.keyById(initialKeyId)?.id) }
    var entries by remember(siteId) { mutableStateOf<List<ModelEntry>?>(null) }
    var error by remember(siteId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(siteId) {
        val s = site ?: return@LaunchedEffect
        runCatching { Repo.models(s) }.onSuccess { entries = it }.onFailure { error = it.message }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(
                    title = { Text("选择模型") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") } },
                )
                if (usable.isEmpty()) {
                    MessageBox("没有可用的 Key。请添加「URL + Key」站点，或在站点的「令牌」页填入 Key。")
                    return@Column
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    usable.forEach { s -> FilterChip(selected = s.id == siteId, onClick = { siteId = s.id }, label = { Text(s.name) }) }
                }
                val keys = site?.usableKeys().orEmpty()
                if (keys.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("令牌", style = MaterialTheme.typography.labelMedium)
                        keys.forEach { k -> FilterChip(selected = k.id == keyId, onClick = { keyId = k.id }, label = { Text(k.label) }) }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索模型") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val all = entries
                when {
                    site == null -> Unit
                    all == null && error == null -> LoadingBox()
                    all == null -> MessageBox("模型列表加载失败：$error")
                    else -> {
                        val q = query.trim().lowercase()
                        val shown = all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                            .sortedWith(compareBy({ it.name !in site.favoriteModels }, { it.name.lowercase() }))
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(shown, key = { it.name }) { e ->
                                val fav = e.name in site.favoriteModels
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { keyId?.let { k -> onPick(site.id, k, e.name) } }
                                        .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FavoriteButton(fav) { scope.launch { Repo.toggleFavorite(site.id, e.name) } }
                                    Column(Modifier.weight(1f)) {
                                        Text(e.name, fontWeight = if (fav) FontWeight.SemiBold else null)
                                        e.price?.let {
                                            Text(it.shortText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
