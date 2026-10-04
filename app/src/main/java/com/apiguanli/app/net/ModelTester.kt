package com.apiguanli.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TestResult(val ok: Boolean, val latencyMs: Long, val httpCode: Int, val message: String)

/** Sends one tiny real request to check a model works. This costs the model's normal price. */
object ModelTester {
    private val http = Http.base.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /** Enough for a reasoning model to think briefly and still answer one word. */
    private const val MAX_TOKENS = 256

    /** Endpoint types we know how to call, in order of preference. */
    val supported = listOf("openai", "anthropic", "gemini")

    fun pickEndpoint(endpoints: List<String>): String =
        supported.firstOrNull { it in endpoints } ?: "openai"

    /** [url] is the full request URL for [endpoint], see [com.apiguanli.app.data.testUrl]. */
    suspend fun test(url: String, key: String, model: String, endpoint: String): TestResult =
        withContext(Dispatchers.IO) {
            val prompt = "Hi, reply with one word."
            val rb = Request.Builder().header("User-Agent", Http.USER_AGENT)
            val body: JsonObject
            when (endpoint) {
                "anthropic" -> {
                    rb.url(url)
                        .header("x-api-key", key)
                        .header("Authorization", "Bearer $key")
                        .header("anthropic-version", "2023-06-01")
                    body = buildJsonObject {
                        put("model", model)
                        put("max_tokens", MAX_TOKENS)
                        putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", prompt) } }
                    }
                }
                "gemini" -> {
                    rb.url(url)
                        .header("x-goog-api-key", key)
                        .header("Authorization", "Bearer $key")
                    body = buildJsonObject {
                        putJsonArray("contents") {
                            addJsonObject { putJsonArray("parts") { addJsonObject { put("text", prompt) } } }
                        }
                    }
                }
                else -> {
                    rb.url(url).header("Authorization", "Bearer $key")
                    body = buildJsonObject {
                        put("model", model)
                        put("max_tokens", MAX_TOKENS)
                        put("stream", false)
                        putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", prompt) } }
                    }
                }
            }
            rb.post(body.toString().toRequestBody(Http.jsonType))
            val start = System.nanoTime()
            try {
                http.newCall(rb.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val ms = (System.nanoTime() - start) / 1_000_000
                    val o = runCatching { Http.json.parseToJsonElement(text) }.getOrNull().asObj()
                    if (resp.isSuccessful && o != null && o.obj("error") == null) {
                        TestResult(true, ms, resp.code, replyText(o, endpoint).ifBlank { "（请求成功，无文本内容）" })
                    } else {
                        val err = o?.obj("error")?.str("message") ?: o?.str("message") ?: text.take(300)
                        TestResult(false, ms, resp.code, explain(resp.code, err.ifBlank { "HTTP ${resp.code}" }))
                    }
                }
            } catch (e: IOException) {
                val ms = (System.nanoTime() - start) / 1_000_000
                TestResult(false, ms, 0, "网络错误：${e.message ?: e.javaClass.simpleName}")
            }
        }

    private fun explain(code: Int, err: String): String {
        val e = err.lowercase()
        val hint = when {
            "invalid token" in e || "无效的令牌" in err -> "Key 无效：可能填错了，或令牌已被删除"
            "quota" in e && ("insufficient" in e || "not enough" in e) || "余额不足" in err -> "余额或令牌额度不足"
            "no available channel" in e || "无可用渠道" in err -> "该模型当前没有可用渠道"
            code == 429 -> "请求太频繁或被限流"
            code == 404 -> "接口或模型不存在，可以换一种接口格式试试"
            else -> null
        }
        return if (hint == null) err else hint + "\n" + err
    }

    private fun replyText(o: JsonObject, endpoint: String): String = runCatching {
        val (text, thinking) = when (endpoint) {
            "anthropic" -> {
                val blocks = o.arr("content").orEmpty().mapNotNull { it.asObj() }
                blocks.filter { it.str("type") == "text" }.joinToString("") { it.str("text").orEmpty() } to
                    blocks.filter { it.str("type") == "thinking" }.joinToString("") { it.str("thinking").orEmpty() }
            }
            "gemini" -> (o.arr("candidates")?.firstOrNull().asObj()?.obj("content")?.arr("parts") as? JsonArray)
                ?.mapNotNull { it.asObj()?.str("text") }?.joinToString("").orEmpty() to ""
            else -> {
                val msg = o.arr("choices")?.firstOrNull().asObj()?.obj("message")
                msg?.str("content").orEmpty() to (msg?.str("reasoning_content") ?: msg?.str("reasoning")).orEmpty()
            }
        }
        // A reasoning model that ran out of tokens while thinking still proves the model works.
        text.trim().ifEmpty { thinking.trim().takeIf { it.isNotEmpty() }?.let { "（只返回了思考过程）$it" }.orEmpty() }
    }.getOrNull().orEmpty().take(200)
}
