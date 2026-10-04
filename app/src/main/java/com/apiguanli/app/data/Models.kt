package com.apiguanli.app.data

import kotlinx.serialization.Serializable

/** One of the API base URLs a site publishes (e.g. "亚太线路"). */
@Serializable
data class ApiLine(val name: String, val url: String, val desc: String = "")

@Serializable
data class SavedKey(val tokenName: String, val keyEnc: String)

@Serializable
data class Balance(
    val quota: Long,
    val usedQuota: Long,
    val requestCount: Long,
    val group: String,
    val updatedAt: Long,
    /** Key-only sites: the key has no quota cap. */
    val unlimited: Boolean = false,
)

object SiteKind {
    /** Logged in with username/password; full account access. */
    const val ACCOUNT = "account"
    /** Only a base URL + one API key; no account access. */
    const val KEY = "key"
}

/** A relay site. Secrets are stored encrypted (see [Crypto]). */
@Serializable
data class Site(
    val id: String,
    /** Normalized site root, e.g. "https://api.gemai.cc" (no trailing slash). */
    val baseUrl: String,
    val name: String,
    val kind: String = SiteKind.ACCOUNT,
    val username: String = "",
    val userId: Int = 0,
    val accessTokenEnc: String = "",
    /** Key-only sites: the API key, encrypted. */
    val apiKeyEnc: String = "",
    /** User-entered recharge ratio: 1 元 buys this many display units ("额度"). */
    val rechargeRate: Double? = null,
    /** Ratio derived from the site's published top-up price, used as a hint. */
    val suggestedRate: Double? = null,
    val quotaPerUnit: Double = 500000.0,
    /** Multiplier from USD-units to the site's display currency. */
    val displayRate: Double = 1.0,
    val currencySymbol: String = "$",
    val apiLines: List<ApiLine> = emptyList(),
    val selectedLineUrl: String? = null,
    /** tokenId -> full API key the user pasted (or the site revealed), encrypted. */
    val savedKeys: Map<String, SavedKey> = emptyMap(),
    val balance: Balance? = null,
    /** Model names the user starred on this site. */
    val favoriteModels: Set<String> = emptySet(),
    /**
     * Path of the OpenAI-compatible API under the site root. NewAPI uses "/v1";
     * others differ, e.g. Volcengine Ark coding plan uses "/api/coding/v3".
     */
    val apiPath: String = "/v1",
    /** Key-only sites: how [apiPath] turns into the request URL, see [ApiFormat]. */
    val apiFormat: String = ApiFormat.OPENAI,
    /** Marked by the user as a trustworthy reference channel for model checks. */
    val trusted: Boolean = false,
    /** Model names added by hand, for sites whose model list is missing or unusable. */
    val customModels: List<String> = emptyList(),
    /** The site runs NewAPI's daily check-in (签到); refreshed with the balance. */
    val checkinEnabled: Boolean = false,
    val checkin: CheckinInfo? = null,
)

/** What the app knows about an account's daily check-in. */
@Serializable
data class CheckinInfo(
    /** Day (yyyy-MM-dd) the account is known to be checked in. */
    val doneDate: String? = null,
    /** Quota awarded on [doneDate], when known. */
    val awarded: Long? = null,
    val monthCount: Int = 0,
    val totalCount: Long = 0,
    val totalQuota: Long = 0,
    /** Why the app can't check in on its own until the user acts, e.g. it needs a re-login. */
    val problem: String? = null,
)

fun today(): String = java.time.LocalDate.now().toString()

/** Check-in only applies to logged-in accounts on sites that turned it on. */
val Site.canCheckin: Boolean get() = !isKeyOnly && checkinEnabled

val Site.checkedInToday: Boolean get() = checkin?.doneDate == today()

val Site.isKeyOnly: Boolean get() = kind == SiteKind.KEY

/** How a key-only site's URL is completed, like the endpoint option in other AI clients. */
object ApiFormat {
    /** [Site.apiPath] is an OpenAI base such as "/v1"; requests go to "…/chat/completions". */
    const val OPENAI = "openai"
    /** [Site.apiPath] is an Anthropic base ending in "/v1"; requests go to "…/messages". */
    const val ANTHROPIC = "anthropic"
    /** [Site.apiPath] is the full endpoint path, used as-is. */
    const val RAW = "raw"
}

private val RAW_SUFFIXES = listOf("/chat/completions", "/messages")

/** Wire protocol for chat: "openai" or "anthropic". A raw URL ending in /messages is Anthropic. */
fun protocolOf(apiPath: String, format: String): String = when (format) {
    ApiFormat.ANTHROPIC -> "anthropic"
    ApiFormat.RAW -> if (apiPath.substringBefore('?').trimEnd('/').endsWith("/messages")) "anthropic" else "openai"
    else -> "openai"
}

/** Full URL that chat requests go to. */
fun chatUrlOf(root: String, apiPath: String, format: String): String = root + when (format) {
    ApiFormat.ANTHROPIC -> "$apiPath/messages"
    ApiFormat.RAW -> apiPath
    else -> "$apiPath/chat/completions"
}

/** Converts raw internal quota to the amount the site shows ("额度"). */
fun Site.toDisplay(quota: Long): Double = quota / quotaPerUnit * displayRate

/** Converts a USD-unit price (model_price, ratio-based prices) to display units. */
fun Site.unitsToDisplay(units: Double): Double = units * displayRate

/** Real money in 元, or null when the user hasn't set a recharge ratio. */
fun Site.toCny(display: Double): Double? = rechargeRate?.takeIf { it > 0 }?.let { display / it }

val Site.lineUrl: String get() = selectedLineUrl ?: baseUrl

val Site.protocol: String get() = protocolOf(apiPath, apiFormat)

/** Where chat (and the checkup) send requests, e.g. "https://x.com/v1/chat/completions". */
val Site.chatUrl: String get() = chatUrlOf(lineUrl, apiPath, apiFormat)

/** Model list path under the site root, or null when a raw URL gives no way to find it. */
val Site.modelsPath: String? get() = when (apiFormat) {
    ApiFormat.RAW -> apiPath.substringBefore('?').trimEnd('/').let { p ->
        RAW_SUFFIXES.firstOrNull { p.endsWith(it) }?.let { p.removeSuffix(it) + "/models" }
    }
    else -> "$apiPath/models"
}

/**
 * True when the API isn't at the site root's standard "/v1", which means it is not a
 * NewAPI relay (no balance, logs or prices) and the full address must always be copied.
 */
val Site.hasCustomApiPath: Boolean get() = when (apiFormat) {
    ApiFormat.RAW -> apiPath.substringBefore('?').trimEnd('/').let { p -> RAW_SUFFIXES.none { p == "/v1$it" } }
    else -> apiPath != "/v1"
}

/**
 * URL for one model test. NewAPI roots serve every format at the standard paths;
 * other key sites only have the one endpoint the user configured.
 */
fun Site.testUrl(endpoint: String, model: String): String = when {
    isKeyOnly && hasCustomApiPath -> chatUrl
    endpoint == "anthropic" -> "$lineUrl/v1/messages"
    endpoint == "gemini" -> "$lineUrl/v1beta/models/$model:generateContent"
    else -> "$lineUrl/v1/chat/completions"
}

/** What the user typed for a key-only site, to prefill the edit form. */
val Site.inputUrl: String get() = when (apiFormat) {
    ApiFormat.ANTHROPIC -> baseUrl + apiPath.removeSuffix("/v1")
    ApiFormat.RAW -> baseUrl + apiPath
    else -> if (apiPath == "/v1") baseUrl else baseUrl + apiPath
}

val Site.host: String get() = baseUrl.substringAfter("://")

fun Site.savedKey(tokenId: Int): String? = savedKeys[tokenId.toString()]?.let { Crypto.decrypt(it.keyEnc) }

fun Site.accessToken(): String = Crypto.decrypt(accessTokenEnc).orEmpty()

fun Site.apiKey(): String = Crypto.decrypt(apiKeyEnc).orEmpty()

/** A usable API key on a site: id is the token id, or [KEY_ONLY_ID] for key-only sites. */
data class UsableKey(val id: String, val label: String, val key: String)

const val KEY_ONLY_ID = "apikey"

/** Every full key the app can call models with on this site. */
fun Site.usableKeys(): List<UsableKey> =
    if (isKeyOnly) listOf(UsableKey(KEY_ONLY_ID, "API Key", apiKey()))
    else savedKeys.mapNotNull { (id, k) -> Crypto.decrypt(k.keyEnc)?.let { UsableKey(id, k.tokenName, it) } }

fun Site.keyById(id: String?): UsableKey? = usableKeys().let { keys -> keys.firstOrNull { it.id == id } ?: keys.firstOrNull() }
