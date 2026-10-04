package com.apiguanli.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.apiKey
import com.apiguanli.app.data.isKeyOnly
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.data.host
import com.apiguanli.app.data.ApiFormat
import com.apiguanli.app.data.chatUrl
import com.apiguanli.app.data.protocol
import com.apiguanli.app.data.hasCustomApiPath
import com.apiguanli.app.data.lineUrl
import com.apiguanli.app.data.toCny
import com.apiguanli.app.data.toDisplay
import kotlinx.coroutines.launch

private val accountTabs = listOf("概览", "令牌", "日志", "模型")
private val keyTabs = listOf("概览", "日志", "模型")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteScreen(siteId: String, onBack: () -> Unit, onRelogin: (String) -> Unit, onChat: (String) -> Unit, onCheck: (String) -> Unit) {
    val sites by SiteStore.sites.collectAsState()
    val site = sites.firstOrNull { it.id == siteId }
    if (site == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Non-NewAPI channels (e.g. Volcengine Ark) have no usage logs to show.
    val tabs = when {
        site.isKeyOnly && site.hasCustomApiPath -> listOf("概览", "模型")
        site.isKeyOnly -> keyTabs
        else -> accountTabs
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(site.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(site.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tabs.getOrNull(tab)) {
                "令牌" -> TokensTab(site)
                "日志" -> if (site.isKeyOnly) KeyLogsTab(site) else LogsTab(site)
                "模型" -> ModelsTab(site, onChat, onCheck)
                else -> OverviewTab(site, onRelogin)
            }
        }
    }
}

@Composable
private fun OverviewTab(site: Site, onRelogin: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(site.id) {
        refreshError = runCatching { Repo.refreshBalance(site) }.exceptionOrNull()?.message
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Balance (non-NewAPI channels don't report one)
        if (!(site.isKeyOnly && site.hasCustomApiPath)) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val b = site.balance
                Text(if (site.isKeyOnly) "这个 Key 的剩余额度" else "余额", style = MaterialTheme.typography.labelLarge)
                if (b != null && b.unlimited) {
                    Text("无限额度", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Key 本身不限额，实际以账户余额为准（只有 Key 看不到账户余额）。已用 ${fmtNum(site.toDisplay(b.usedQuota))} 额度" +
                            (site.toCny(site.toDisplay(b.usedQuota))?.let { "（${fmtCny(it)}）" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (b == null) {
                    Text("—", style = MaterialTheme.typography.headlineMedium)
                } else {
                    val display = site.toDisplay(b.quota)
                    Text("${fmtNum(display)} 额度", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        site.toCny(display)?.let { "实际约 ${fmtCny(it)}" } ?: "设置下方充值比例后显示实际金额",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(2.dp))
                    Text(
                        "已用 ${fmtNum(site.toDisplay(b.usedQuota))} 额度" +
                            (site.toCny(site.toDisplay(b.usedQuota))?.let { "（${fmtCny(it)}）" } ?: "") +
                            (if (site.isKeyOnly) "" else " · 请求 ${fmtCount(b.requestCount)} 次"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!site.isKeyOnly) Text("分组 ${b.group} · ${fmtAgo(b.updatedAt)}更新", style = MaterialTheme.typography.bodySmall)
                }
                refreshError?.let { Text("刷新失败：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        CheckinSiteCard(site, onRelogin)
        if (site.isKeyOnly) KeyCard(site)
        RechargeRateCard(site)
        SiteSettingsCard(site)

        // API lines
        Card {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("API 线路", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                Text(
                    "选中的线路用于复制地址、对话和测试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                site.apiLines.forEach { line ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { scope.launch { SiteStore.modify(site.id) { it.copy(selectedLineUrl = line.url) } } }
                            .padding(start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = site.lineUrl == line.url,
                            onClick = { scope.launch { SiteStore.modify(site.id) { it.copy(selectedLineUrl = line.url) } } },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = MaterialTheme.typography.bodyMedium)
                            Text(line.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (line.desc.isNotBlank()) {
                                Text(line.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { copyToClipboard(context, line.url, label = "已复制地址") }) {
                            Icon(Icons.Default.ContentCopy, "复制地址")
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onRelogin(site.id) }, modifier = Modifier.weight(1f)) {
                Text(if (site.isKeyOnly) "修改 Key" else "重新登录")
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                Text("删除站点", color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider()
        Text(
            "访问令牌和已保存的 Key 都用系统密钥库加密，只存在这台手机上。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除站点？",
            text = "会删除「${site.name}」的登录信息和在 App 里保存的 ${site.savedKeys.size} 个 Key。站点上的账号和令牌不受影响。",
            confirmText = "删除",
            onConfirm = { scope.launch { SiteStore.delete(site.id) } },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RechargeRateCard(site: Site) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var text by remember(site.id, site.rechargeRate) { mutableStateOf(site.rechargeRate?.let { fmtNum(it) }.orEmpty()) }
    val parsed = text.trim().toDoubleOrNull()?.takeIf { it > 0 }
    val changed = parsed != site.rechargeRate

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("充值比例", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1 元 = ")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp),
                )
                Text(" 额度")
                Spacer(Modifier.weight(1f))
                if (changed) {
                    Button(onClick = {
                        scope.launch { SiteStore.modify(site.id) { it.copy(rechargeRate = parsed) } }
                        toast(context, if (parsed == null) "已清除充值比例" else "已保存")
                    }) { Text("保存") }
                }
            }
            val hint = buildString {
                append("用来把「额度」换算成真实花的钱，比价时会用到。")
                site.suggestedRate?.let { append("站点公布的充值价约为 1 元 = ${fmtNum(it)} 额度，有优惠时以实际为准。") }
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (site.rechargeRate == null && site.suggestedRate != null) {
                TextButton(onClick = { text = fmtNum(site.suggestedRate) }) { Text("填入站点公布值") }
            }
        }
    }
}

/** Key-only sites: the key itself, with the same copy actions as the token list. */
@Composable
private fun KeyCard(site: Site) {
    val context = LocalContext.current
    val appendV1 by Prefs.appendV1
    val key = site.apiKey()
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API Key", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!site.hasCustomApiPath && site.apiFormat == ApiFormat.OPENAI) {
                    FilterChip(selected = appendV1, onClick = { Prefs.setAppendV1(!appendV1) }, label = { Text("加 /v1") })
                }
            }
            Text(maskKey(key), fontFamily = FontFamily.Monospace)
            Text(site.copyUrl(appendV1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { copyToClipboard(context, key, sensitive = true, label = "已复制 Key") }) { Text("复制 Key") }
                OutlinedButton(onClick = { copyToClipboard(context, site.copyUrl(appendV1), label = "已复制地址") }) { Text("复制地址") }
                OutlinedButton(onClick = {
                    copyToClipboard(context, "Base URL: ${site.copyUrl(appendV1)}\nAPI Key: $key", sensitive = true, label = "已复制地址和 Key")
                }) { Text("全部") }
            }
        }
    }
}

/** Name, trusted-reference flag and hand-added models. */
@Composable
private fun SiteSettingsCard(site: Site) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember(site.id, site.name) { mutableStateOf(site.name) }
    var models by remember(site.id, site.customModels) { mutableStateOf(site.customModels.joinToString("\n")) }
    val modelsChanged = models.lines().map(String::trim).filter(String::isNotEmpty) != site.customModels

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("站点设置", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (name.trim() != site.name && name.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { scope.launch { Repo.rename(site.id, name) } }) { Text("保存") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("可信对照渠道")
                    Text(
                        "官方 API 或你信得过的渠道。模型体检时会把其他站点和它逐项对比。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = site.trusted, onCheckedChange = { on -> scope.launch { Repo.setTrusted(site.id, on) } })
            }
            OutlinedTextField(
                value = models,
                onValueChange = { models = it },
                label = { Text("手动添加的模型（每行一个）") },
                placeholder = { Text("站点的模型列表不全时用，如\nglm-5.3\nkimi-k3") },
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (modelsChanged) {
                Button(onClick = {
                    scope.launch { Repo.setCustomModels(site.id, models.lines()) }
                    toast(context, "已保存")
                }) { Text("保存模型") }
            }
            if (site.isKeyOnly) {
                Text(
                    "接口格式：${formatLabel(site.apiFormat)}" +
                        (if (site.apiFormat == ApiFormat.RAW) "（按 ${if (site.protocol == "anthropic") "Anthropic" else "OpenAI"} 格式发送）" else "") +
                        "\n请求地址：${site.chatUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
