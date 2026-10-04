package com.apiguanli.app.net

import com.apiguanli.app.data.ApiLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [code] is the site's machine-readable error code when it sends one, e.g. "ACCESS_TOKEN_SCOPE_DENIED". */
class ApiException(message: String, val code: String? = null) : Exception(message)

/** [ApiException.code] when an endpoint has no model list (as opposed to a bad key). */
const val MODELS_UNAVAILABLE = "MODELS_UNAVAILABLE"

data class SiteStatus(
    val name: String,
    val logo: String,
    val version: String,
    val quotaPerUnit: Double,
    val displayRate: Double,
    val currencySymbol: String,
    /** 元 per 1 USD-unit of quota, as published by the site (top-up price). */
    val topupPrice: Double?,
    val passwordLogin: Boolean,
    val turnstile: Boolean,
    val apiLines: List<ApiLine>,
    /** The site runs NewAPI's daily check-in (签到). */
    val checkinEnabled: Boolean = false,
)

data class UserInfo(
    val id: Int,
    val username: String,
    val quota: Long,
    val usedQuota: Long,
    val requestCount: Long,
    val group: String,
)

data class TokenItem(
    val id: Int,
    val name: String,
    /** Key as returned by the site; may be masked like "Uks4*****". */
    val key: String,
    val status: Int,
    val usedQuota: Long,
    val remainQuota: Long,
    val unlimited: Boolean,
    val expiredTime: Long,
    val accessedTime: Long,
    val group: String,
) {
    val isMasked: Boolean get() = key.contains('*')
    /** The visible part of a masked key, used to check pasted keys. */
    val visiblePrefix: String get() = key.substringBefore('*').removePrefix("sk-")
    val visibleSuffix: String get() = if (isMasked) key.substringAfterLast('*') else ""

    /** Whether a pasted full key is consistent with the masked one the site shows. */
    fun matches(fullKey: String): Boolean {
        val k = fullKey.trim().removePrefix("sk-")
        return k.startsWith(visiblePrefix) && k.endsWith(visibleSuffix)
    }
}

data class LogItem(
    val id: Long,
    val createdAt: Long,
    val type: Int,
    val content: String,
    val modelName: String,
    val tokenName: String,
    val quota: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val useTime: Long,
    val isStream: Boolean,
    val group: String,
)

data class PricingModel(
    val name: String,
    /** 0 = billed by tokens (ratios), 1 = billed per call (model_price). */
    val quotaType: Int,
    val modelRatio: Double,
    val completionRatio: Double,
    val modelPrice: Double,
    val enableGroups: List<String>,
    val endpoints: List<String>,
)

data class PricingInfo(
    val models: List<PricingModel>,
    val groupRatio: Map<String, Double>,
)

data class Page<T>(val items: List<T>, val total: Int)

/** Usage of a single API key (key-only sites). */
data class KeyUsage(
    val name: String,
    val used: Long,
    val available: Long,
    val unlimited: Boolean,
    val expiresAt: Long,
)

/** Daily check-in state; the dates are the site's own. */
data class CheckinStatus(
    val checkedToday: Boolean,
    val monthCount: Int,
    val totalCount: Long,
    val totalQuota: Long,
    /** date (yyyy-MM-dd) -> quota awarded, this month. */
    val records: Map<String, Long>,
)

sealed interface LoginResult {
    data class Success(val userId: Int) : LoginResult
    data object Need2FA : LoginResult
}

object Http {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val jsonType = "application/json; charset=utf-8".toMediaType()

    // Some sites sit behind Cloudflare and reject unknown user agents.
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

private class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, Cookie>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { store[it.name] = it }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.values.filter { it.matches(url) }
}

/**
 * Talks to a NewAPI-style site's management API (`/api/...`).
 * Auth is either the session cookie from [login] or a system access token.
 */
class NewApiClient(
    baseUrl: String,
    private var userId: Int? = null,
    private val accessToken: String? = null,
    /** For key-only sites: an sk- API key used instead of an access token. */
    private val apiKey: String? = null,
    /** Model list path for key-only sites, see [com.apiguanli.app.data.Site.modelsPath]; null when unknown. */
    private val modelsPath: String? = "/v1/models",
    /** Sent with every request, e.g. Anthropic's x-api-key / anthropic-version. */
    private val extraHeaders: Map<String, String> = emptyMap(),
) {
    private val root = baseUrl.trimEnd('/')
    private val http = Http.base.newBuilder().cookieJar(MemoryCookieJar()).build()

    private class Raw(val code: Int, val body: JsonElement?, val text: String)

    private suspend fun raw(path: String, method: String = "GET", body: JsonObject? = null): Raw =
        rawWithHeader(path, method, body, null, null)

    private suspend fun rawWithHeader(path: String, method: String, body: JsonObject?, headerName: String?, headerValue: String?): Raw =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder().url(root + path)
                .header("User-Agent", Http.USER_AGENT)
                .header("Accept", "application/json")
            val bearer = accessToken?.takeIf { it.isNotEmpty() } ?: sessionToken ?: apiKey?.takeIf { it.isNotEmpty() }
            if (bearer != null) rb.header("Authorization", "Bearer $bearer")
            extraHeaders.forEach { (k, v) -> rb.header(k, v) }
            if (headerName != null && headerValue != null) rb.header(headerName, headerValue)
            userId?.let { rb.header("New-Api-User", it.toString()) }
            when (method) {
                "GET" -> rb.get()
                else -> rb.method(method, (body ?: JsonObject(emptyMap())).toString().toRequestBody(Http.jsonType))
            }
            try {
                http.newCall(rb.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    Raw(resp.code, runCatching { Http.json.parseToJsonElement(text) }.getOrNull(), text)
                }
            } catch (e: IOException) {
                throw ApiException("网络错误：${e.message ?: e.javaClass.simpleName}")
            }
        }

    private fun messageOf(r: Raw): String? {
        val o = r.body.asObj() ?: return null
        val msg = o.str("message")?.takeIf { it.isNotBlank() } ?: o.obj("error")?.str("message")
        return msg?.let(::localize)
    }

    /** Translates the most common English server messages. */
    private fun localize(msg: String): String {
        val m = msg.lowercase()
        return when {
            "username or password is incorrect" in m -> "用户名或密码错误，或账号已被封禁"
            "password login" in m && "disabled" in m -> "该站点关闭了账号密码登录"
            "not logged in" in m || "access token is invalid" in m || "unauthorized" in m -> "登录已失效，请重新登录"
            "too many requests" in m || "rate limit" in m -> "请求太频繁，请稍后再试"
            else -> msg
        }
    }

    private fun isMissingRoute(r: Raw): Boolean =
        r.code == 404 || r.code == 405 || messageOf(r)?.startsWith("Invalid URL") == true

    /** Unwraps `{success, message, data}`, throwing a readable error on failure. */
    private fun unwrap(r: Raw): JsonElement {
        val o = r.body.asObj()
        if (o == null) {
            val hint = if (r.text.contains("cloudflare", ignoreCase = true)) "（可能被 Cloudflare 拦截）" else ""
            throw ApiException("站点返回了无法识别的内容 HTTP ${r.code}$hint")
        }
        if (r.code == 401) throw ApiException("登录已失效，请重新登录（${messageOf(r) ?: "401"}）")
        if (o.bool("success") == false || r.code >= 400) {
            throw ApiException(messageOf(r) ?: "请求失败 HTTP ${r.code}", o.str("code"))
        }
        return o["data"] ?: JsonObject(emptyMap())
    }

    private suspend fun data(path: String, method: String = "GET", body: JsonObject? = null) =
        unwrap(raw(path, method, body))

    suspend fun status(): SiteStatus {
        val d = data("/api/status").asObj() ?: throw ApiException("站点状态为空")
        val quotaPerUnit = d.dbl("quota_per_unit")?.takeIf { it > 0 } ?: 500000.0
        val displayType = d.str("quota_display_type")
            ?: if (d.bool("display_in_currency") == false) "TOKENS" else "USD"
        val (rate, symbol) = when (displayType.uppercase()) {
            "CNY" -> (d.dbl("usd_exchange_rate") ?: 7.3) to "¥"
            "CUSTOM" -> (d.dbl("custom_currency_exchange_rate") ?: 1.0) to (d.str("custom_currency_symbol") ?: "¤")
            "TOKENS" -> quotaPerUnit to ""
            else -> 1.0 to "$"
        }
        val lines = mutableListOf(ApiLine("站点地址", root))
        if (d.bool("api_info_enabled") != false) {
            d.arr("api_info")?.forEach { item ->
                val o = item.asObj() ?: return@forEach
                val url = o.str("url")?.trim()?.trimEnd('/') ?: return@forEach
                if (url.startsWith("http") && lines.none { it.url == url }) {
                    lines += ApiLine(o.str("route") ?: url, url, o.str("description").orEmpty().takeIf { it != "无" }.orEmpty())
                }
            }
        }
        return SiteStatus(
            name = d.str("system_name")?.takeIf { it.isNotBlank() } ?: root.substringAfter("://"),
            logo = d.str("logo").orEmpty(),
            version = d.str("version").orEmpty(),
            quotaPerUnit = quotaPerUnit,
            displayRate = rate,
            currencySymbol = symbol,
            topupPrice = d.dbl("price")?.takeIf { it > 0 },
            passwordLogin = d.bool("password_login_enabled") != false,
            turnstile = d.bool("turnstile_check") == true,
            apiLines = lines,
            checkinEnabled = d.bool("checkin_enabled") == true,
        )
    }

    // ---- Login -------------------------------------------------------------
    //
    // Two generations of NewAPI exist:
    //  - classic: login sets a session cookie and returns the user in `data`;
    //    `/api/user/token` then issues one long-lived "system access token".
    //  - current (2026+): login returns `data.access_token` (a short-lived session
    //    bearer) and `data.user`, or first a 2FA challenge (`require_verification`).
    //    Long-lived tokens are scoped personal access tokens ("nap_…") created at
    //    `/api/user/access_tokens`, each needing a fresh security proof.

    /** Session bearer from a current-generation login. */
    private var sessionToken: String? = null
    /** Pending 2FA login challenge (current generation). */
    private var loginFlowToken: String? = null
    /** Verification methods the account offers, e.g. "2fa", "password". */
    private var verifyMethods: List<String> = emptyList()

    /** RSA public key for sites that require the password to be encrypted in transit. */
    private data class PasswordKey(val kid: String, val publicKeyPem: String)

    private suspend fun passwordKey(): PasswordKey? {
        val r = raw("/api/user/login/encryption-key")
        if (isMissingRoute(r)) return null
        val d = runCatching { unwrap(r) }.getOrNull().asObj() ?: return null
        if (d.bool("enabled") != true) return null
        return PasswordKey(d.str("kid") ?: return null, d.str("public_key") ?: return null)
    }

    /** RSA-OAEP with SHA-256 (also for MGF1), the scheme the server decrypts. */
    private fun encryptPassword(password: String, pem: String): String {
        val der = java.util.Base64.getMimeDecoder().decode(
            pem.lines().filterNot { it.startsWith("-----") }.joinToString(""),
        )
        val key = java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(der))
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding")
        val spec = javax.crypto.spec.OAEPParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT,
        )
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, spec)
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
    }

    /** Adds the password to [b], encrypted when the site asks for it. */
    private suspend fun putPassword(b: kotlinx.serialization.json.JsonObjectBuilder, password: String) {
        val key = passwordKey()
        if (key == null) {
            b.put("password", password)
        } else {
            b.put("password_encrypted", encryptPassword(password, key.publicKeyPem))
            b.put("encryption_key_id", key.kid)
        }
    }

    /** Reads either generation's successful login payload; null when it's a challenge instead. */
    private fun acceptLogin(d: JsonObject?): Int? {
        if (d == null) return null
        if (d.bool("require_verification") == true) {
            loginFlowToken = d.str("flow_token")
            verifyMethods = d.arr("methods")?.mapNotNull { m ->
                m.asObj()?.takeIf { it.bool("available") != false }?.str("method")
            }.orEmpty()
            return null
        }
        if (d.bool("require_2fa") == true) return null
        d.str("access_token")?.takeIf { it.isNotBlank() }?.let { sessionToken = it }
        val id = d.obj("user")?.int("id") ?: d.int("id") ?: d.int("user_id")
        if (id != null) userId = id
        return id
    }

    suspend fun login(username: String, password: String): LoginResult {
        val body = buildJsonObject {
            put("username", username)
            putPassword(this, password)
        }
        val d = data("/api/user/login", "POST", body).asObj()
        acceptLogin(d)?.let { return LoginResult.Success(it) }
        if (d?.bool("require_verification") == true && "2fa" !in verifyMethods) {
            throw ApiException("这个账号的登录验证方式是「${verifyMethods.joinToString("、").ifEmpty { "未知" }}」，App 目前只支持两步验证码（2FA）")
        }
        if (d?.bool("require_verification") == true || d?.bool("require_2fa") == true) return LoginResult.Need2FA
        throw ApiException("登录成功，但站点返回的内容里没有用户信息，可能是不兼容的 NewAPI 版本")
    }

    suspend fun login2fa(code: String): Int {
        val flow = loginFlowToken
        val d = if (flow != null) {
            data("/api/user/login/verify", "POST", buildJsonObject {
                put("flow_token", flow)
                put("method", "2fa")
                put("code", code)
            }).asObj()
        } else {
            data("/api/user/login/2fa", "POST", buildJsonObject { put("code", code) }).asObj()
        }
        return acceptLogin(d) ?: throw ApiException("两步验证通过，但站点没有返回用户信息")
    }

    /** Thrown when creating the access token needs a fresh 2FA code instead of the password. */
    class NeedProofCode : Exception("需要再输入一次两步验证码")

    /**
     * Creates the long-lived token the app keeps. Current sites get a scoped personal
     * access token (read-only, plus revealing full keys); classic sites get the legacy
     * system access token (which replaces the account's previous one).
     */
    suspend fun generateAccessToken(password: String, twoFaCode: String? = null): String {
        if (sessionToken != null) {
            createPersonalToken(password, twoFaCode)?.let { return it }
        }
        return legacyAccessToken(password)
    }

    // wallet:* is what the daily check-in needs; it can't touch API keys or the account itself.
    private val tokenScopes = listOf("api_key:read", "api_key:reveal", "profile:read", "usage:read", "wallet:read", "wallet:write")

    /**
     * Sites between the classic and current generations (e.g. rc.36) bind the proof to the
     * scope alone and reject any context; their token is the single system access token.
     */
    private var proofWithoutContext = false

    /** Null when the site doesn't have personal access tokens. */
    private suspend fun createPersonalToken(password: String, twoFaCode: String?): String? {
        val scopes = kotlinx.serialization.json.JsonArray(tokenScopes.map { JsonPrimitive(it) })
        val context = buildJsonObject {
            put("scopes", scopes)
            put("expires_at", 0)
        }
        // The proof is bound to exactly this scope + grant.
        val method = if (twoFaCode != null) "2fa" else "password"
        suspend fun verify(withContext: Boolean) = raw("/api/verify", "POST", buildJsonObject {
            put("method", method)
            put("scope", "access_token.generate")
            if (withContext) put("context", context)
            if (twoFaCode != null) put("code", twoFaCode) else putPassword(this, password)
        })
        var vr = verify(withContext = !proofWithoutContext)
        if (isMissingRoute(vr)) return null
        if (!proofWithoutContext && vr.body.asObj()?.str("code") == "SECURITY_CONTEXT_INVALID") {
            proofWithoutContext = true
            vr = verify(withContext = false)
        }
        if (vr.code >= 400 || vr.body.asObj()?.bool("success") == false) {
            // Accounts with 2FA may not be allowed to confirm with the password.
            if (twoFaCode == null && "2fa" in verifyMethods) throw NeedProofCode()
            throw ApiException("生成访问令牌前的安全验证没通过：${messageOf(vr) ?: "HTTP ${vr.code}"}")
        }
        val proof = unwrap(vr).asObj()?.str("proof_token") ?: throw ApiException("安全验证没有返回凭证")

        if (proofWithoutContext) {
            // Replaces the account's previous system access token, like the classic flow.
            val r = rawWithHeader("/api/user/token", "POST", null, "X-Security-Proof", proof)
            val token = (unwrap(r) as? JsonPrimitive)?.content
            if (token.isNullOrBlank()) throw ApiException("站点未返回访问令牌")
            return token
        }

        val name = "中转站管家 " + java.time.LocalDate.now().toString()
        val r = rawWithHeader("/api/user/access_tokens", "POST", buildJsonObject {
            put("name", name)
            put("scopes", scopes)
            put("expires_at", 0)
        }, "X-Security-Proof", proof)
        if (isMissingRoute(r)) return null
        val token = unwrap(r).asObj()?.str("token")
        if (token.isNullOrBlank()) throw ApiException("站点没有返回访问令牌")
        return token
    }

    private suspend fun legacyAccessToken(password: String): String {
        var r = raw("/api/user/token", "POST")
        if (isMissingRoute(r)) r = raw("/api/user/token", "GET")
        if (r.code == 403) {
            val o = r.body.asObj()
            val code = o?.str("code").orEmpty()
            if (code.startsWith("VERIFICATION_")) {
                val verify = buildJsonObject {
                    put("method", "password")
                    put("code", password)
                    o?.str("verification_challenge")?.let { put("challenge", it) }
                }
                data("/api/verify", "POST", verify)
                r = raw("/api/user/token", "POST")
            }
        }
        val token = (unwrap(r) as? JsonPrimitive)?.content
        if (token.isNullOrBlank()) throw ApiException("站点未返回访问令牌")
        return token
    }

    suspend fun self(): UserInfo {
        val d = data("/api/user/self").asObj() ?: throw ApiException("用户信息为空")
        return UserInfo(
            id = d.int("id") ?: 0,
            username = d.str("username").orEmpty(),
            quota = d.long("quota") ?: 0,
            usedQuota = d.long("used_quota") ?: 0,
            requestCount = d.long("request_count") ?: 0,
            group = d.str("group") ?: "default",
        )
    }

    private fun <T> toPage(d: JsonElement, map: (JsonObject) -> T?): Page<T> = when (d) {
        is JsonArray -> Page(d.mapNotNull { it.asObj()?.let(map) }, d.size)
        is JsonObject -> {
            val items = d.arr("items")?.mapNotNull { it.asObj()?.let(map) }.orEmpty()
            Page(items, d.int("total") ?: items.size)
        }
        else -> Page(emptyList(), 0)
    }

    suspend fun tokens(page: Int = 1, size: Int = 100): Page<TokenItem> =
        toPage(data("/api/token/?p=$page&size=$size")) { o ->
            TokenItem(
                id = o.int("id") ?: return@toPage null,
                name = o.str("name").orEmpty(),
                key = o.str("key").orEmpty(),
                status = o.int("status") ?: 1,
                usedQuota = o.long("used_quota") ?: 0,
                remainQuota = o.long("remain_quota") ?: 0,
                unlimited = o.bool("unlimited_quota") == true,
                expiredTime = o.long("expired_time") ?: -1,
                accessedTime = o.long("accessed_time") ?: 0,
                group = o.str("group").orEmpty(),
            )
        }

    /** Upstream NewAPI can reveal a full key; returns null when the site doesn't support it. */
    suspend fun revealKey(tokenId: Int): String? {
        val r = raw("/api/token/$tokenId/key", "POST")
        if (isMissingRoute(r)) return null
        val key = unwrap(r).asObj()?.str("key") ?: return null
        if (key.contains('*')) return null
        return if (key.startsWith("sk-")) key else "sk-$key"
    }

    suspend fun logs(
        page: Int,
        size: Int,
        type: Int = 0,
        modelName: String = "",
        tokenName: String = "",
    ): Page<LogItem> {
        val q = buildString {
            append("/api/log/self?p=$page&page_size=$size&type=$type")
            if (modelName.isNotBlank()) append("&model_name=").append(java.net.URLEncoder.encode(modelName, "UTF-8"))
            if (tokenName.isNotBlank()) append("&token_name=").append(java.net.URLEncoder.encode(tokenName, "UTF-8"))
        }
        return toPage(data(q), ::parseLog)
    }

    /** Key-only: the key's own quota (`/api/usage/token`). Null if the site lacks it. */
    // ---- Daily check-in ----------------------------------------------------

    suspend fun checkinStatus(): CheckinStatus {
        val stats = data("/api/user/checkin").asObj()?.obj("stats") ?: throw ApiException("签到状态为空")
        return CheckinStatus(
            checkedToday = stats.bool("checked_in_today") == true,
            monthCount = stats.int("checkin_count") ?: 0,
            totalCount = stats.long("total_checkins") ?: 0,
            totalQuota = stats.long("total_quota") ?: 0,
            records = stats.arr("records")?.mapNotNull { r ->
                val o = r.asObj() ?: return@mapNotNull null
                (o.str("checkin_date") ?: return@mapNotNull null) to (o.long("quota_awarded") ?: 0)
            }.orEmpty().toMap(),
        )
    }

    /** Checks in for today and returns the quota awarded. */
    suspend fun checkin(): Long =
        data("/api/user/checkin", "POST", buildJsonObject {}).asObj()?.long("quota_awarded") ?: 0

    suspend fun keyUsage(): KeyUsage? {
        val r = raw("/api/usage/token/")
        if (isMissingRoute(r)) return null
        val d = unwrapLoose(r).asObj() ?: return null
        return KeyUsage(
            name = d.str("name").orEmpty(),
            used = d.long("total_used") ?: 0,
            available = d.long("total_available") ?: 0,
            unlimited = d.bool("unlimited_quota") == true,
            expiresAt = d.long("expires_at") ?: 0,
        )
    }

    /** Key-only: recent logs of this key (`/api/log/token`). */
    suspend fun keyLogs(): List<LogItem> {
        val r = raw("/api/log/token")
        if (isMissingRoute(r)) throw ApiException("这个站点不支持用 Key 查询日志")
        return toPage(unwrapLoose(r), ::parseLog).items.sortedByDescending { it.createdAt }
    }

    /** OpenAI-compatible model list (`/v1/models`), works with an API key. Retired models are skipped. */
    suspend fun models(): List<String> {
        val path = modelsPath ?: throw ApiException("这个地址没法自动获取模型列表，请在「概览 → 站点设置」里手动添加模型", MODELS_UNAVAILABLE)
        val r = raw(path)
        // Some endpoints (often Anthropic-style relays) just don't offer a model list.
        val missing = if (r.code == 404 || r.code == 405) MODELS_UNAVAILABLE else null
        val o = r.body.asObj() ?: throw ApiException("模型列表返回无法识别 HTTP ${r.code}", missing)
        o.obj("error")?.str("message")?.let { throw ApiException(localize(it), missing) }
        if (r.code >= 400) throw ApiException(messageOf(r) ?: "获取模型失败 HTTP ${r.code}", missing)
        return o.arr("data")?.mapNotNull { it.asObj() }
            ?.filter { it.str("status")?.lowercase() !in setOf("shutdown", "retiring", "deprecated") }
            ?.mapNotNull { it.str("id") }.orEmpty().distinct()
    }

    /** Like [unwrap] but tolerates `code: true` responses that lack `success`. */
    private fun unwrapLoose(r: Raw): JsonElement {
        val o = r.body.asObj()
        if (o != null && o.bool("success") == null && r.code < 400) return o["data"] ?: JsonObject(emptyMap())
        return unwrap(r)
    }

    private fun parseLog(o: JsonObject) = LogItem(
        id = o.long("id") ?: 0,
        createdAt = o.long("created_at") ?: 0,
        type = o.int("type") ?: 0,
        content = o.str("content").orEmpty(),
        modelName = o.str("model_name").orEmpty(),
        tokenName = o.str("token_name").orEmpty(),
        quota = o.long("quota") ?: 0,
        promptTokens = o.long("prompt_tokens") ?: 0,
        completionTokens = o.long("completion_tokens") ?: 0,
        useTime = o.long("use_time") ?: 0,
        isStream = o.bool("is_stream") == true,
        group = o.str("group").orEmpty(),
    )

    suspend fun pricing(): PricingInfo {
        val r = raw("/api/pricing")
        val o = r.body.asObj() ?: throw ApiException("价格接口返回无法识别 HTTP ${r.code}")
        if (o.bool("success") == false) throw ApiException(o.str("message") ?: "获取价格失败")
        val models = (o["data"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e.asObj() ?: return@mapNotNull null
            PricingModel(
                name = m.str("model_name") ?: return@mapNotNull null,
                quotaType = m.int("quota_type") ?: 0,
                modelRatio = m.dbl("model_ratio") ?: 0.0,
                completionRatio = m.dbl("completion_ratio") ?: 1.0,
                modelPrice = m.dbl("model_price") ?: 0.0,
                enableGroups = m.strList("enable_groups"),
                endpoints = m.strList("supported_endpoint_types"),
            )
        }
        val groupRatio = o.obj("group_ratio")?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { k to it }
        }?.toMap().orEmpty()
        return PricingInfo(models, groupRatio)
    }
}
