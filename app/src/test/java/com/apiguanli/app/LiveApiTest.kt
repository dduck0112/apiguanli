package com.apiguanli.app

import com.apiguanli.app.data.Balance
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.buildPriceRows
import com.apiguanli.app.data.normalizeModelName
import com.apiguanli.app.data.toCny
import com.apiguanli.app.data.toDisplay
import com.apiguanli.app.net.NewApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Hits a real NewAPI site. Skipped unless NEWAPI_URL, NEWAPI_USER_ID and NEWAPI_TOKEN are set,
 * so no credentials ever live in the repo. Read-only: never logs in or regenerates tokens.
 */
class LiveApiTest {
    private val url = System.getenv("NEWAPI_URL")
    private val userId = System.getenv("NEWAPI_USER_ID")?.toIntOrNull()
    private val token = System.getenv("NEWAPI_TOKEN")
    private val rate = System.getenv("NEWAPI_RATE")?.toDoubleOrNull()

    private fun client(): NewApiClient {
        assumeTrue("live env not set", url != null && userId != null && token != null)
        return NewApiClient(url!!, userId, token)
    }

    @Test
    fun normalizeStripsPrefixes() {
        assertEquals("claude-sonnet-5", normalizeModelName("[特价E]claude-sonnet-5"))
        assertEquals("gemini-3-flash", normalizeModelName("【满血B】 Gemini-3-Flash"))
        assertEquals("gpt-5", normalizeModelName("gpt-5"))
    }

    @Test
    fun liveReadOnlyEndpoints() = runBlocking {
        val c = client()
        val status = c.status()
        println("status: ${status.name} v${status.version} unit=${status.quotaPerUnit} rate=${status.displayRate}${status.currencySymbol} price=${status.topupPrice} turnstile=${status.turnstile}")
        status.apiLines.forEach { println("  line: ${it.name} ${it.url} ${it.desc}") }

        val me = c.self()
        println("self: ${me.username} group=${me.group} quota=${me.quota} used=${me.usedQuota} req=${me.requestCount}")
        assertEquals(userId, me.id)

        val site = Site(
            id = "t", baseUrl = url!!, name = status.name, userId = userId!!, accessTokenEnc = "",
            rechargeRate = rate, quotaPerUnit = status.quotaPerUnit, displayRate = status.displayRate,
            balance = Balance(me.quota, me.usedQuota, me.requestCount, me.group, 0),
        )
        val display = site.toDisplay(me.quota)
        println("balance: $display 额度 ≈ ¥${site.toCny(display)}")

        val tokens = c.tokens()
        println("tokens: total=${tokens.total}")
        tokens.items.forEach { println("  #${it.id} ${it.name} key=${it.visiblePrefix}… masked=${it.isMasked} status=${it.status} unlimited=${it.unlimited}") }
        assertTrue(tokens.items.isNotEmpty())

        val reveal = tokens.items.firstOrNull()?.let { c.revealKey(it.id) }
        println("reveal supported: ${reveal != null}")

        val logs = c.logs(1, 5, type = 2)
        println("consume logs: total=${logs.total}")
        logs.items.forEach { println("  ${it.createdAt} ${it.modelName} ${it.tokenName} quota=${it.quota} in=${it.promptTokens} out=${it.completionTokens}") }
        val page2 = c.logs(2, 5, type = 2)
        println("page2 first id=${page2.items.firstOrNull()?.id} (page1 first=${logs.items.firstOrNull()?.id})")

        val pricing = c.pricing()
        val rows = buildPriceRows(site, pricing)
        println("pricing: ${rows.size} models, groups=${pricing.groupRatio}")
        rows.filter { it.baseName.contains("claude-sonnet-5") }.sortedBy { it.sortCny ?: Double.MAX_VALUE }.forEach {
            if (it.perCall) println("  ${it.model.name}: ${it.callDisplay}/次 ≈ ¥${it.callCny}")
            else println("  ${it.model.name}: in ${it.inputDisplay} out ${it.outputDisplay} /1M ≈ ¥${it.inputCny}/¥${it.outputCny}")
        }
        rows.filter { !it.perCall }.take(3).forEach {
            println("  [token] ${it.model.name}: ratio=${it.model.modelRatio} comp=${it.model.completionRatio} in ${it.inputDisplay} out ${it.outputDisplay}")
        }
    }
}
