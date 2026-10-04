package com.apiguanli.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apiguanli.app.chat.ChatStore
import com.apiguanli.app.chat.Conversation
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.usableKeys
import com.apiguanli.app.ui.ConfirmDialog
import com.apiguanli.app.ui.MessageBox
import com.apiguanli.app.ui.Prefs
import com.apiguanli.app.ui.fmtAgo
import com.apiguanli.app.ui.toast

/** Creates conversations; they are only saved once the first message is sent. */
object ChatLauncher {
    fun create(siteId: String, keyId: String, model: String): String {
        val c = Conversation(siteId = siteId, keyId = keyId, model = model)
        ChatStore.put(c, persist = false)
        Prefs.setLastChat(siteId, keyId, model)
        return c.id
    }

    /** New chat with the last used model, or the first site that has a usable key. Null if none. */
    fun createDefault(): String? {
        val sites = SiteStore.sites.value.filter { it.usableKeys().isNotEmpty() }
        if (sites.isEmpty()) return null
        val last = Prefs.lastChat()
        val site = sites.firstOrNull { it.id == last?.siteId } ?: sites.first()
        val keyId = site.usableKeys().let { keys -> keys.firstOrNull { it.id == last?.keyId } ?: keys.first() }.id
        val model = if (last != null && site.id == last.siteId) last.model else site.favoriteModels.firstOrNull().orEmpty()
        return create(site.id, keyId, model)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val convs by ChatStore.items.collectAsState()
    val sites by SiteStore.sites.collectAsState()
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    val shown = convs.filter { it.messages.isNotEmpty() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("对话") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val id = ChatLauncher.createDefault()
                    if (id == null) toast(context, "还没有可用的 Key：请添加「URL + Key」站点，或在站点的「令牌」页填入 Key")
                    else onOpen(id)
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新对话") },
            )
        },
    ) { padding ->
        if (shown.isEmpty()) {
            MessageBox(
                "还没有对话。\n像中转站的「操练场」一样，选一个站点和模型直接聊天，\n还能看到返回的模型名、token 用量和速度。",
                Modifier.padding(padding).padding(top = 48.dp),
            )
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.id }) { c ->
                    val siteName = sites.firstOrNull { it.id == c.siteId }?.name ?: "（站点已删除）"
                    Card(onClick = { onOpen(c.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${c.model} · $siteName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    c.messages.last().content.replace('\n', ' ').ifBlank { c.messages.last().error.orEmpty() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${c.messages.size} 条 · ${fmtAgo(c.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { deleting = c }) { Icon(Icons.Default.DeleteOutline, "删除") }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { c ->
        ConfirmDialog(
            title = "删除对话？",
            text = "「${c.title}」会被永久删除。",
            confirmText = "删除",
            onConfirm = { ChatStore.delete(c.id) },
            onDismiss = { deleting = null },
        )
    }
}
