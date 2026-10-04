package com.apiguanli.app.data

import com.apiguanli.app.net.ApiException
import com.apiguanli.app.net.MODELS_UNAVAILABLE
import com.apiguanli.app.net.ModelTester
import com.apiguanli.app.net.NewApiClient
import com.apiguanli.app.net.PricingInfo
import com.apiguanli.app.net.SiteStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A model the user can pick on a site, with its price when the site publishes one. */
data class ModelEntry(val site: Site, val name: String, val price: PriceRow?, val endpoints: List<String>) {
    val favorite: Boolean get() = name in site.favoriteModels
}

/** App-level operations shared by the screens. */
object Repo {
    fun client(site: Site) =
        if (site.isKeyOnly) keyClient(site.baseUrl, site.apiKey(), site.modelsPath, site.protocol)
        else NewApiClient(site.baseUrl, site.userId, site.accessToken())

    private fun keyClient(baseUrl: String, key: String, modelsPath: String?, protocol: String) = NewApiClient(
        baseUrl, apiKey = key, modelsPath = modelsPath,
        extraHeaders = if (protocol == "anthropic") mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION) else emptyMap(),
    )

    const val ANTHROPIC_VERSION = "2023-06-01"

    /**
     * The API path inside a pasted URL, completed the way [format] says:
     *  - OpenAI: "https://x.com" and "https://x.com/v1" give "/v1"; "…/api/coding/v3" is kept;
     *    a pasted "…/chat/completions" is dropped.
     *  - Anthropic: like the Anthropic SDK's base URL, "/v1" is added unless present
     *    ("https://api.deepseek.com/anthropic" gives "/anthropic/v1"); a pasted "…/messages" is dropped.
     *  - Raw: the path exactly as typed (with any query), which must not be empty.
     */
    fun apiPathOf(input: String, format: String = ApiFormat.OPENAI): String {
        var s = input.trim()
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { URI(s) }.getOrNull()
        val path = uri?.rawPath.orEmpty().trimEnd('/')
        return when (format) {
            ApiFormat.RAW -> {
                if (path.isEmpty()) throw ApiException("选了「不补全」，地址要写完整，例如 https://x.com/v1/chat/completions")
                path + (uri?.rawQuery?.let { "?$it" } ?: "")
            }
            ApiFormat.ANTHROPIC -> {
                val p = path.removeSuffix("/messages").trimEnd('/')
                if (p.endsWith("/v1")) p else "$p/v1"
            }
            else -> {
                val p = path.removeSuffix("/chat/completions").trimEnd('/')
                if (p.isEmpty() || p == "/v1") "/v1" else p
            }
        }
    }

    /** The request URL a key-only site would use, shown while the user types; null if not valid yet. */
    fun previewUrl(input: String, format: String): String? {
        if (input.isBlank()) return null
        return runCatching { chatUrlOf(normalizeUrl(input), apiPathOf(input, format), format) }.getOrNull()
    }

    /** Turns "api.gemai.cc/console/" or "https://x.com/v1" into "https://x.com". */
    fun normalizeUrl(input: String): String {
        var s = input.trim()
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { URI(s) }.getOrNull() ?: throw ApiException("网址格式不正确")
        val host = uri.host ?: throw ApiException("网址格式不正确")
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme.lowercase()}://$host$port"
    }

    /** Creates or updates a site after a successful login, keeping the user's local settings. */
    suspend fun saveLogin(baseUrl: String, status: SiteStatus, userId: Int, token: String, username: String, existingId: String?): Site {
        val user = NewApiClient(baseUrl, userId, token).self()
        val name = user.username.ifBlank { username }
        val prev = existingId?.let { SiteStore.get(it) }
            ?: SiteStore.sites.value.firstOrNull { !it.isKeyOnly && it.baseUrl == baseUrl && it.username == name }
        val site = Site(
            id = prev?.id ?: UUID.randomUUID().toString(),
            baseUrl = baseUrl,
            // Keep a name the user gave the site.
            name = prev?.name ?: status.name,
            kind = SiteKind.ACCOUNT,
            username = name,
            userId = userId,
            accessTokenEnc = Crypto.encrypt(token),
            rechargeRate = prev?.rechargeRate,
            suggestedRate = status.topupPrice?.let { status.displayRate / it },
            quotaPerUnit = status.quotaPerUnit,
            displayRate = status.displayRate,
            currencySymbol = status.currencySymbol,
            apiLines = status.apiLines,
            selectedLineUrl = prev?.selectedLineUrl?.takeIf { sel -> status.apiLines.any { it.url == sel } },
            savedKeys = prev?.savedKeys.orEmpty(),
            balance = Balance(user.quota, user.usedQuota, user.requestCount, user.group, System.currentTimeMillis()),
            favoriteModels = prev?.favoriteModels.orEmpty(),
            trusted = prev?.trusted ?: false,
            customModels = prev?.customModels.orEmpty(),
            checkinEnabled = status.checkinEnabled,
            // A fresh login brings a fresh token, so earlier permission problems no longer apply.
            checkin = prev?.checkin?.copy(problem = null),
        )
        SiteStore.upsert(site)
        if (site.canCheckin) runCatching { refreshCheckin(site) }
        return site
    }

    /**
     * Adds a site from just a URL and an API key. The key is checked by listing models;
     * site info and key quota are best-effort since non-NewAPI relays may lack them.
     */
    suspend fun saveKeySite(
        urlInput: String, keyInput: String, existingId: String?, nameInput: String = "", format: String = ApiFormat.OPENAI,
    ): Site {
        val baseUrl = normalizeUrl(urlInput)
        val apiPath = apiPathOf(urlInput, format)
        val key = keyInput.trim()
        // Only NewAPI-style sites (API at the root's /v1) have /api/status and key usage.
        val draft = Site(id = "", baseUrl = baseUrl, name = "", kind = SiteKind.KEY, apiPath = apiPath, apiFormat = format)
        val atRoot = !draft.hasCustomApiPath
        val status = if (atRoot) runCatching { NewApiClient(baseUrl).status() }.getOrNull() else null
        val client = keyClient(baseUrl, key, draft.modelsPath, draft.protocol)
        // The model list doubles as the key check. OpenAI-style APIs always have one; for the
        // others a missing list is fine, and models can be added by hand.
        val models = try {
            client.models()
        } catch (e: ApiException) {
            if (format == ApiFormat.OPENAI || e.code != MODELS_UNAVAILABLE) throw e
            null
        }
        val usage = if (atRoot) runCatching { client.keyUsage() }.getOrNull() else null
        val prev = existingId?.let { SiteStore.get(it) }
            ?: SiteStore.sites.value.firstOrNull { it.isKeyOnly && it.baseUrl == baseUrl && it.apiPath == apiPath && it.apiFormat == format && it.apiKey() == key }
        val lines = status?.apiLines ?: listOf(ApiLine("站点地址", baseUrl))
        val site = Site(
            id = prev?.id ?: UUID.randomUUID().toString(),
            baseUrl = baseUrl,
            name = nameInput.trim().ifEmpty { prev?.name ?: status?.name ?: baseUrl.substringAfter("://") },
            kind = SiteKind.KEY,
            username = usage?.name?.takeIf { it.isNotBlank() } ?: "API Key",
            apiKeyEnc = Crypto.encrypt(key),
            rechargeRate = prev?.rechargeRate,
            suggestedRate = status?.topupPrice?.let { status.displayRate / it },
            quotaPerUnit = status?.quotaPerUnit ?: 500000.0,
            displayRate = status?.displayRate ?: 1.0,
            currencySymbol = status?.currencySymbol ?: "$",
            apiLines = lines,
            selectedLineUrl = prev?.selectedLineUrl?.takeIf { sel -> lines.any { it.url == sel } },
            balance = usage?.let { Balance(it.available, it.used, 0, "", System.currentTimeMillis(), it.unlimited) },
            favoriteModels = prev?.favoriteModels.orEmpty(),
            apiPath = apiPath,
            apiFormat = format,
            trusted = prev?.trusted ?: false,
            customModels = prev?.customModels.orEmpty(),
        )
        SiteStore.upsert(site)
        if (models != null) modelsCache[site.id] = CachedModels(models, System.currentTimeMillis())
        else modelsCache.remove(site.id)
        return site
    }

    suspend fun refreshBalance(site: Site) {
        if (site.isKeyOnly && site.hasCustomApiPath) return // not a NewAPI site: no quota endpoint
        val b = if (site.isKeyOnly) {
            val u = client(site).keyUsage() ?: return
            Balance(u.available, u.used, 0, "", System.currentTimeMillis(), u.unlimited)
        } else {
            val u = client(site).self()
            Balance(u.quota, u.usedQuota, u.requestCount, u.group, System.currentTimeMillis())
        }
        SiteStore.modify(site.id) { it.copy(balance = b) }
        if (!site.isKeyOnly) {
            // The admin can switch check-in on or off at any time.
            val enabled = runCatching { NewApiClient(site.baseUrl).status().checkinEnabled }.getOrNull()
            if (enabled != null && enabled != site.checkinEnabled) SiteStore.modify(site.id) { it.copy(checkinEnabled = enabled) }
            if (enabled ?: site.checkinEnabled) runCatching { refreshCheckin(site) }
        }
    }

    // ---- Daily check-in ----------------------------------------------------

    sealed interface CheckinOutcome {
        data class Done(val awarded: Long) : CheckinOutcome
        data object Already : CheckinOutcome
        data class Failed(val message: String) : CheckinOutcome
    }

    private const val SCOPE_DENIED = "ACCESS_TOKEN_SCOPE_DENIED"
    private const val NEED_RELOGIN = "App 的访问令牌没有签到权限，请在本站点页点「重新登录」一次"
    private const val NEED_WEB = "这个站点签到要过网页人机验证，App 里签不了，请到网页上签到"

    private suspend fun updateCheckin(siteId: String, f: (CheckinInfo) -> CheckinInfo) =
        SiteStore.modify(siteId) { it.copy(checkin = f(it.checkin ?: CheckinInfo())) }

    /** Reads this month's check-in stats from the site. */
    suspend fun refreshCheckin(site: Site) {
        val st = try {
            client(site).checkinStatus()
        } catch (e: ApiException) {
            if (e.code == SCOPE_DENIED) updateCheckin(site.id) { it.copy(problem = NEED_RELOGIN) }
            throw e
        }
        val day = today()
        updateCheckin(site.id) { info ->
            val doneToday = st.checkedToday
            info.copy(
                doneDate = if (doneToday) day else info.doneDate?.takeIf { it != day },
                awarded = if (doneToday) st.records[day] ?: info.awarded.takeIf { info.doneDate == day } else null,
                monthCount = st.monthCount,
                totalCount = st.totalCount,
                totalQuota = st.totalQuota,
                // Reading works with the token now; a web-only check-in is still web-only.
                problem = info.problem.takeIf { it == NEED_WEB },
            )
        }
    }

    suspend fun checkin(site: Site): CheckinOutcome {
        val day = today()
        val outcome = try {
            CheckinOutcome.Done(client(site).checkin())
        } catch (e: ApiException) {
            val msg = e.message.orEmpty()
            when {
                e.code == SCOPE_DENIED -> {
                    updateCheckin(site.id) { it.copy(problem = NEED_RELOGIN) }
                    return CheckinOutcome.Failed(NEED_RELOGIN)
                }
                "已签到" in msg -> CheckinOutcome.Already
                "未启用" in msg -> {
                    SiteStore.modify(site.id) { it.copy(checkinEnabled = false) }
                    return CheckinOutcome.Failed("站点已关闭签到")
                }
                "turnstile" in msg.lowercase() -> {
                    updateCheckin(site.id) { it.copy(problem = NEED_WEB) }
                    return CheckinOutcome.Failed(NEED_WEB)
                }
                else -> return CheckinOutcome.Failed(msg.ifBlank { "签到失败" })
            }
        } catch (e: Exception) {
            return CheckinOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        updateCheckin(site.id) {
            it.copy(
                doneDate = day,
                awarded = (outcome as? CheckinOutcome.Done)?.awarded ?: it.awarded.takeIf { _ -> it.doneDate == day },
                problem = null,
            )
        }
        // New balance and month stats; the check-in itself already succeeded.
        runCatching { refreshBalance(SiteStore.get(site.id) ?: site) }
        return outcome
    }

    /**
     * Checks in on every site that offers it and isn't done today. [auto] (on app start)
     * skips sites with a known problem, so they don't fail again every launch.
     */
    suspend fun checkinAll(auto: Boolean): List<Pair<Site, CheckinOutcome>> = coroutineScope {
        SiteStore.sites.value
            .filter { it.canCheckin && !it.checkedInToday && (!auto || it.checkin?.problem == null) }
            .map { site -> async { site to checkin(site) } }
            .awaitAll()
    }

    /** Refreshes every site in parallel; returns siteId -> error message for failures. */
    suspend fun refreshAll(): Map<String, String> = coroutineScope {
        SiteStore.sites.value.map { site ->
            async { site.id to runCatching { refreshBalance(site) }.exceptionOrNull()?.message }
        }.awaitAll().mapNotNull { (id, err) -> err?.let { id to it } }.toMap()
    }

    suspend fun saveKey(siteId: String, tokenId: Int, tokenName: String, key: String) {
        val enc = Crypto.encrypt(key.trim())
        SiteStore.modify(siteId) { it.copy(savedKeys = it.savedKeys + (tokenId.toString() to SavedKey(tokenName, enc))) }
    }

    suspend fun removeKey(siteId: String, tokenId: Int) {
        SiteStore.modify(siteId) { it.copy(savedKeys = it.savedKeys - tokenId.toString()) }
    }

    suspend fun toggleFavorite(siteId: String, model: String) {
        SiteStore.modify(siteId) {
            it.copy(favoriteModels = if (model in it.favoriteModels) it.favoriteModels - model else it.favoriteModels + model)
        }
    }

    // Pricing and model lists are fetched on demand and kept for a few minutes.
    private data class CachedPricing(val info: PricingInfo, val at: Long)
    private data class CachedModels(val names: List<String>, val at: Long)
    private val pricingCache = ConcurrentHashMap<String, CachedPricing>()
    private val modelsCache = ConcurrentHashMap<String, CachedModels>()
    private const val CACHE_TTL = 10 * 60 * 1000L

    suspend fun pricing(site: Site, force: Boolean = false): PricingInfo {
        val now = System.currentTimeMillis()
        pricingCache[site.id]?.takeIf { !force && now - it.at < CACHE_TTL }?.let { return it.info }
        val info = client(site).pricing()
        pricingCache[site.id] = CachedPricing(info, now)
        return info
    }

    private suspend fun modelNames(site: Site, force: Boolean): List<String> {
        val now = System.currentTimeMillis()
        modelsCache[site.id]?.takeIf { !force && now - it.at < CACHE_TTL }?.let { return it.names }
        val names = client(site).models()
        modelsCache[site.id] = CachedModels(names, now)
        return names
    }

    /**
     * Models usable on a site. Account sites list the site's price table; key-only sites list
     * what the model list returns for the key, joined with prices when the site publishes them.
     */
    suspend fun models(site: Site, force: Boolean = false): List<ModelEntry> {
        // Key sites call models in the format they were added with.
        val own = listOf(if (site.isKeyOnly) site.protocol else ModelTester.supported.first())
        val custom = site.customModels.map { ModelEntry(site, it, null, own) }
        if (!site.isKeyOnly) {
            val priced = buildPriceRows(site, pricing(site, force)).map { ModelEntry(site, it.model.name, it, it.model.endpoints) }
            return priced + custom.filter { c -> priced.none { it.name == c.name } }
        }
        // A site with hand-added models may have no usable model list at all; that's fine.
        val names = if (site.customModels.isEmpty()) modelNames(site, force)
        else runCatching { modelNames(site, force) }.getOrDefault(emptyList())
        val prices = if (site.hasCustomApiPath) emptyMap()
        else runCatching { buildPriceRows(site, pricing(site, force)) }.getOrDefault(emptyList()).associateBy { it.model.name }
        val listed = names.filter { it !in site.customModels }
            .map { n -> ModelEntry(site, n, prices[n], prices[n]?.model?.endpoints ?: own) }
        return custom + listed
    }

    suspend fun setTrusted(siteId: String, trusted: Boolean) = SiteStore.modify(siteId) { it.copy(trusted = trusted) }

    suspend fun rename(siteId: String, name: String) = SiteStore.modify(siteId) { it.copy(name = name.trim().ifEmpty { it.name }) }

    suspend fun setCustomModels(siteId: String, models: List<String>) =
        SiteStore.modify(siteId) { it.copy(customModels = models.map(String::trim).filter(String::isNotEmpty).distinct()) }
}
