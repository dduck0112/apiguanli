package com.apiguanli.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.Repo.CheckinOutcome
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.canCheckin
import com.apiguanli.app.data.checkedInToday
import com.apiguanli.app.data.toCny
import com.apiguanli.app.data.toDisplay
import kotlinx.coroutines.launch

/** "0.02 额度（≈¥0.0001）" */
fun Site.quotaText(quota: Long): String {
    val d = toDisplay(quota)
    return "${fmtNum(d)} 额度" + (toCny(d)?.let { "（≈${fmtCny(it)}）" } ?: "")
}

/** One line per site, for the snackbar / toast after checking in. */
fun checkinSummary(results: List<Pair<Site, CheckinOutcome>>): String = results.joinToString("\n") { (site, r) ->
    site.name + "：" + when (r) {
        is CheckinOutcome.Done -> "签到成功 +${site.quotaText(r.awarded)}"
        CheckinOutcome.Already -> "今天已签过"
        is CheckinOutcome.Failed -> "失败，${r.message}"
    }
}

/** Short status for the home list; null when the site has no check-in. */
@Composable
fun CheckinLine(site: Site) {
    if (!site.canCheckin) return
    val problem = site.checkin?.problem
    val (text, color) = when {
        site.checkedInToday -> ("今日已签" + (site.checkin?.awarded?.let { " +${fmtNum(site.toDisplay(it))}" } ?: "")) to
            MaterialTheme.colorScheme.primary
        problem != null -> "签到需处理，点进站点查看" to MaterialTheme.colorScheme.error
        else -> "今日未签到" to MaterialTheme.colorScheme.tertiary
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Home screen: one-tap check-in for all sites. Only shown when some site has check-in. */
@Composable
fun CheckinAllCard(sites: List<Site>, busy: Boolean, onCheckinAll: () -> Unit) {
    val eligible = sites.filter { it.canCheckin }
    if (eligible.isEmpty()) return
    val done = eligible.count { it.checkedInToday }
    val auto by Prefs.autoCheckin
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每日签到", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (done == eligible.size) "${eligible.size} 个站点今天都签好了" else "今日已签 $done / ${eligible.size} 个站点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onCheckinAll, enabled = !busy && done < eligible.size) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("签到中…")
                    } else {
                        Text("一键签到")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "打开 App 时自动签到",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = auto, onCheckedChange = { Prefs.setAutoCheckin(it) })
            }
        }
    }
}

/** Site overview: today's state, this month's stats and a check-in button. */
@Composable
fun CheckinSiteCard(site: Site, onRelogin: (String) -> Unit) {
    if (!site.canCheckin) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val info = site.checkin
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每日签到", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (site.checkedInToday) "今天已签到" + (info?.awarded?.let { "，领到 ${site.quotaText(it)}" } ?: "")
                        else "今天还没签到",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (site.checkedInToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                FilledTonalButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = Repo.checkin(site)
                            busy = false
                            toast(context, checkinSummary(listOf(site to r)).substringAfter("："))
                        }
                    },
                    enabled = !busy && !site.checkedInToday,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(if (site.checkedInToday) "已签到" else "签到")
                }
            }
            if (info != null && (info.monthCount > 0 || info.totalCount > 0)) {
                Text(
                    "本月签到 ${info.monthCount} 次 · 累计 ${info.totalCount} 次，共领 ${site.quotaText(info.totalQuota)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            info?.problem?.let { p ->
                Text(p, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if ("重新登录" in p) OutlinedButton(onClick = { onRelogin(site.id) }) { Text("重新登录") }
            }
        }
    }
}
