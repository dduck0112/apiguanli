package com.apiguanli.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.hasCustomApiPath
import com.apiguanli.app.data.lineUrl
import com.apiguanli.app.data.ApiFormat
import com.apiguanli.app.data.chatUrl
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object Prefs {
    private const val NAME = "prefs"
    private lateinit var ctx: Context
    val appendV1 = mutableStateOf(false)
    /** Check in on every site that offers it when the app starts. */
    val autoCheckin = mutableStateOf(true)

    fun init(context: Context) {
        ctx = context.applicationContext
        appendV1.value = sp().getBoolean("append_v1", false)
        autoCheckin.value = sp().getBoolean("auto_checkin", true)
    }

    fun setAutoCheckin(v: Boolean) {
        autoCheckin.value = v
        sp().edit().putBoolean("auto_checkin", v).apply()
    }

    /** Last site/key/model chosen for chat, used as the default for a new conversation. */
    data class LastChat(val siteId: String, val keyId: String, val model: String)

    fun lastChat(): LastChat? {
        val sp = sp()
        val site = sp.getString("chat_site", null) ?: return null
        return LastChat(site, sp.getString("chat_key", "").orEmpty(), sp.getString("chat_model", "").orEmpty())
    }

    fun setLastChat(siteId: String, keyId: String, model: String) {
        sp().edit().putString("chat_site", siteId).putString("chat_key", keyId).putString("chat_model", model).apply()
    }

    fun setAppendV1(v: Boolean) {
        appendV1.value = v
        sp().edit().putBoolean("append_v1", v).apply()
    }

    private fun sp() = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

fun formatLabel(format: String): String = when (format) {
    ApiFormat.ANTHROPIC -> "Anthropic"
    ApiFormat.RAW -> "不补全"
    else -> "OpenAI"
}

/**
 * The address to paste into other apps. A raw endpoint is copied whole; an Anthropic base
 * without the "/v1" those apps add themselves; an OpenAI base with its custom path.
 */
fun Site.copyUrl(appendV1: Boolean): String = when {
    apiFormat == ApiFormat.RAW -> chatUrl
    apiFormat == ApiFormat.ANTHROPIC -> lineUrl + apiPath.removeSuffix("/v1")
    hasCustomApiPath -> lineUrl + apiPath
    else -> lineUrl + if (appendV1) "/v1" else ""
}

fun copyToClipboard(context: Context, text: String, sensitive: Boolean = false, label: String = "已复制") {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
    // Android 13+ shows its own copy confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
}

fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

/** 663.6285 -> "663.63", 0.004512 -> "0.00451". */
fun fmtNum(v: Double): String {
    if (v == 0.0 || v.isNaN() || v.isInfinite()) return "0"
    val bd = BigDecimal(v)
    val r = if (abs(v) >= 1) bd.setScale(2, RoundingMode.HALF_UP) else bd.round(MathContext(3, RoundingMode.HALF_UP))
    return r.stripTrailingZeros().toPlainString()
}

fun fmtCny(v: Double?): String = v?.let { "¥" + fmtNum(it) } ?: "—"

fun fmtCount(n: Long): String = when {
    n >= 100_000_000 -> fmtNum(n / 100_000_000.0) + "亿"
    n >= 10_000 -> fmtNum(n / 10_000.0) + "万"
    else -> n.toString()
}

private val fmtShort = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val fmtLong = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun fmtTime(epochSec: Long): String {
    if (epochSec <= 0) return "—"
    val t = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault())
    return if (t.year == LocalDate.now().year) t.format(fmtShort) else t.format(fmtLong)
}

fun fmtAgo(epochMs: Long): String {
    val s = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        s < 60 -> "刚刚"
        s < 3600 -> "${s / 60} 分钟前"
        s < 86400 -> "${s / 3600} 小时前"
        else -> "${s / 86400} 天前"
    }
}

/** Shows "sk-Uks4••••Xy9z" so a saved key can be recognized without exposing it. */
fun maskKey(key: String): String =
    if (key.length <= 12) key.take(4) + "••••" else key.take(7) + "••••" + key.takeLast(4)

private val avatarColors = listOf(
    Color(0xFF3D5AFE), Color(0xFF00897B), Color(0xFFF4511E), Color(0xFF8E24AA),
    Color(0xFF039BE5), Color(0xFF7CB342), Color(0xFFE53935), Color(0xFF6D4C41),
)

@Composable
fun SiteAvatar(name: String, size: Dp = 40.dp) {
    val color = avatarColors[abs(name.hashCode()) % avatarColors.size]
    Box(
        Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp,
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageBox(text: String, modifier: Modifier = Modifier, actionText: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionText != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionText) }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = { onDismiss(); onConfirm() }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
