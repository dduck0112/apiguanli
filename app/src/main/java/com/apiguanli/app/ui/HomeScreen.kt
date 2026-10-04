package com.apiguanli.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.canCheckin
import com.apiguanli.app.data.host
import com.apiguanli.app.data.isKeyOnly
import com.apiguanli.app.data.toCny
import com.apiguanli.app.data.toDisplay
import kotlinx.coroutines.launch

/** Balances refresh once per app launch; afterwards only on pull-to-refresh. */
private var refreshedThisLaunch = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val sites by SiteStore.sites.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var checkingIn by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun checkinAll(auto: Boolean) {
        checkingIn = true
        val results = try { Repo.checkinAll(auto) } finally { checkingIn = false }
        if (results.isNotEmpty()) {
            snackbar.showSnackbar(checkinSummary(results), withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }

    fun refresh(autoCheckin: Boolean = false) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            errors = Repo.refreshAll()
            refreshing = false
            // After the refresh, so sites already checked in today (e.g. on the web) are skipped.
            if (autoCheckin && Prefs.autoCheckin.value) checkinAll(auto = true)
        }
    }

    LaunchedEffect(Unit) {
        if (!refreshedThisLaunch && sites.isNotEmpty()) {
            refreshedThisLaunch = true
            refresh(autoCheckin = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("中转站管家") },
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "刷新") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("添加站点") },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (sites.isEmpty()) {
                MessageBox(
                    "还没有站点。\n点击右下角「添加站点」：可以用账号密码登录 NewAPI 中转站，也可以只填 URL + Key。",
                    Modifier.padding(top = 48.dp),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { SummaryCard(sites) }
                    if (sites.any { it.canCheckin }) item {
                        CheckinAllCard(sites, checkingIn) { scope.launch { checkinAll(auto = false) } }
                    }
                    items(sites, key = { it.id }) { site ->
                        SiteCard(site, errors[site.id]) { onOpen(site.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(sites: List<Site>) {
    // Unlimited keys say nothing about real money left, so they are not counted.
    val counted = sites.filter { it.balance?.unlimited == false }
    val known = counted.mapNotNull { s -> s.balance?.let { s.toCny(s.toDisplay(it.quota)) } }
    val missing = counted.count { it.rechargeRate == null }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("全部站点实际余额", style = MaterialTheme.typography.labelLarge)
            Text(
                if (known.isEmpty()) "—" else fmtCny(known.sum()),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            val note = buildString {
                append("${sites.size} 个站点")
                if (missing > 0) append(" · $missing 个未设置充值比例，未计入")
            }
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SiteCard(site: Site, error: String?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteAvatar(site.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(site.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${site.host} · " + if (site.isKeyOnly) "仅 Key · ${site.username}" else site.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                } else {
                    site.balance?.let {
                        Text(
                            "已用 ${fmtNum(site.toDisplay(it.usedQuota))} 额度 · ${fmtAgo(it.updatedAt)}更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                CheckinLine(site)
            }
            Spacer(Modifier.width(8.dp))
            val b = site.balance
            Column(horizontalAlignment = Alignment.End) {
                if (b == null) {
                    if (site.trusted) Text("可信对照", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    else Text("—", style = MaterialTheme.typography.titleMedium)
                } else if (b.unlimited) {
                    Text("无限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Key 额度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    val display = site.toDisplay(b.quota)
                    Text(fmtNum(display), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        site.toCny(display)?.let { "≈ ${fmtCny(it)}" } ?: "额度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
