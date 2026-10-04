package com.apiguanli.app.chat

import com.apiguanli.app.net.ApiException
import com.apiguanli.app.net.Http
import com.apiguanli.app.net.arr
import com.apiguanli.app.net.asObj
import com.apiguanli.app.net.long
import com.apiguanli.app.net.obj
import com.apiguanli.app.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ChatEvent {
    data class Delta(val content: String, val reasoning: String) : ChatEvent
    data class Done(val meta: MsgMeta) : ChatEvent
}

/** OpenAI-compatible chat completions or Anthropic messages, streaming (SSE) or not. */
object ChatClient {
    /** Anthropic requires max_tokens; used when the user didn't set one. */
    private const val ANTHROPIC_DEFAULT_MAX_TOKENS = 8192

    private val http = Http.base.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun buildBody(model: String, settings: ChatSettings, history: List<ChatMessage>): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", settings.stream)
        if (settings.stream) putJsonObject("stream_options") { put("include_usage", true) }
        settings.temperature?.let { put("temperature", it) }
        settings.maxTokens?.let { put("max_tokens", it) }
        putJsonArray("messages") {
            if (settings.systemPrompt.isNotBlank()) {
                addJsonObject { put("role", "system"); put("content", settings.systemPrompt) }
            }
            history.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
        }
    }

    fun buildAnthropicBody(model: String, settings: ChatSettings, history: List<ChatMessage>): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", settings.stream)
        put("max_tokens", settings.maxTokens ?: ANTHROPIC_DEFAULT_MAX_TOKENS)
        settings.temperature?.let { put("temperature", it) }
        if (settings.systemPrompt.isNotBlank()) put("system", settings.systemPrompt)
        putJsonArray("messages") {
            history.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
        }
    }

    /**
     * [url] is the full request URL; [protocol] is "openai" (chat completions) or
     * "anthropic" (messages), see [com.apiguanli.app.data.Site.protocol].
     */
    fun send(url: String, protocol: String, key: String, model: String, settings: ChatSettings, history: List<ChatMessage>): Flow<ChatEvent> = channelFlow {
        val anthropic = protocol == "anthropic"
        val body = if (anthropic) buildAnthropicBody(model, settings, history) else buildBody(model, settings, history)
        val rb = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("User-Agent", Http.USER_AGENT)
        // Relays differ in which header they read, so send both.
        if (anthropic) rb.header("x-api-key", key).header("anthropic-version", "2023-06-01")
        val call = http.newCall(rb.post(body.toString().toRequestBody(Http.jsonType)).build())
        // A blocking socket read ignores coroutine cancellation, so cancel the call itself.
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            withContext(Dispatchers.IO) {
                if (anthropic) executeAnthropic(call, settings.stream) else execute(call, settings.stream)
            }
        } finally {
            watcher.cancel()
        }
    }

    private fun errorOf(code: Int, text: String): ApiException {
        val o = runCatching { Http.json.parseToJsonElement(text) }.getOrNull().asObj()
        val msg = o?.obj("error")?.str("message") ?: o?.str("message") ?: text.take(300)
        return ApiException("HTTP $code：${msg.ifBlank { "请求失败" }}")
    }

    /** Anthropic Messages API: content blocks, `thinking` blocks as reasoning, usage split across events. */
    private suspend fun ProducerScope<ChatEvent>.executeAnthropic(call: Call, stream: Boolean) {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        var ttft: Long? = null
        var model: String? = null
        var finish: String? = null
        var prompt: Long? = null
        var completion: Long? = null

        fun readUsage(u: JsonObject?) {
            if (u == null) return
            // Cached input is reported separately; count it as prompt like OpenAI does.
            val input = u.long("input_tokens")
            if (input != null) prompt = input + (u.long("cache_read_input_tokens") ?: 0) + (u.long("cache_creation_input_tokens") ?: 0)
            u.long("output_tokens")?.let { completion = it }
        }

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw errorOf(resp.code, resp.body?.string().orEmpty())
                val source = resp.body?.source() ?: throw ApiException("空响应")
                if (!stream) {
                    val o = Http.json.parseToJsonElement(source.readUtf8()).asObj() ?: throw ApiException("响应无法解析")
                    o.obj("error")?.str("message")?.let { throw ApiException(it) }
                    model = o.str("model")
                    finish = o.str("stop_reason")
                    readUsage(o.obj("usage"))
                    val blocks = o.arr("content").orEmpty().mapNotNull { it.asObj() }
                    val text = blocks.filter { it.str("type") == "text" }.joinToString("") { it.str("text").orEmpty() }
                    val thinking = blocks.filter { it.str("type") == "thinking" }.joinToString("") { it.str("thinking").orEmpty() }
                    ttft = elapsed()
                    send(ChatEvent.Delta(text, thinking))
                } else {
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val o = runCatching { Http.json.parseToJsonElement(line.removePrefix("data:").trim()) }.getOrNull().asObj() ?: continue
                        when (o.str("type")) {
                            "error" -> throw ApiException(o.obj("error")?.str("message") ?: "流式响应出错")
                            "message_start" -> o.obj("message")?.let { m ->
                                model = m.str("model")?.takeIf { it.isNotBlank() } ?: model
                                readUsage(m.obj("usage"))
                            }
                            "content_block_delta" -> {
                                val d = o.obj("delta") ?: continue
                                val text = if (d.str("type") == "text_delta") d.str("text").orEmpty() else ""
                                val thinking = if (d.str("type") == "thinking_delta") d.str("thinking").orEmpty() else ""
                                if (text.isNotEmpty() || thinking.isNotEmpty()) {
                                    if (ttft == null) ttft = elapsed()
                                    send(ChatEvent.Delta(text, thinking))
                                }
                            }
                            "message_delta" -> {
                                o.obj("delta")?.str("stop_reason")?.let { finish = it }
                                readUsage(o.obj("usage"))
                            }
                            "message_stop" -> break
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw e
            throw ApiException("网络错误：${e.message ?: e.javaClass.simpleName}")
        }
        send(ChatEvent.Done(MsgMeta(model, prompt, completion, null, ttft, elapsed(), finish)))
    }

    private suspend fun ProducerScope<ChatEvent>.execute(call: Call, stream: Boolean) {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        var ttft: Long? = null
        var model: String? = null
        var finish: String? = null
        var prompt: Long? = null
        var completion: Long? = null
        var reasoningTokens: Long? = null

        fun readUsage(o: JsonObject) {
            val u = o.obj("usage") ?: return
            prompt = u.long("prompt_tokens") ?: u.long("input_tokens") ?: prompt
            completion = u.long("completion_tokens") ?: u.long("output_tokens") ?: completion
            reasoningTokens = u.obj("completion_tokens_details")?.long("reasoning_tokens") ?: reasoningTokens
        }

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw errorOf(resp.code, resp.body?.string().orEmpty())
                val source = resp.body?.source() ?: throw ApiException("空响应")
                if (!stream) {
                    val o = Http.json.parseToJsonElement(source.readUtf8()).asObj() ?: throw ApiException("响应无法解析")
                    o.obj("error")?.str("message")?.let { throw ApiException(it) }
                    val choice = o.arr("choices")?.firstOrNull().asObj()
                    val msg = choice?.obj("message")
                    model = o.str("model")
                    finish = choice?.str("finish_reason")
                    readUsage(o)
                    ttft = elapsed()
                    send(ChatEvent.Delta(msg?.str("content").orEmpty(), msg?.str("reasoning_content") ?: msg?.str("reasoning").orEmpty()))
                } else {
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val o = runCatching { Http.json.parseToJsonElement(payload) }.getOrNull().asObj() ?: continue
                        o.obj("error")?.str("message")?.let { throw ApiException(it) }
                        o.str("model")?.takeIf { it.isNotBlank() }?.let { model = it }
                        readUsage(o)
                        val choice = o.arr("choices")?.firstOrNull().asObj() ?: continue
                        choice.str("finish_reason")?.let { finish = it }
                        val delta = choice.obj("delta") ?: continue
                        val content = delta.str("content").orEmpty()
                        val reasoning = delta.str("reasoning_content") ?: delta.str("reasoning").orEmpty()
                        if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                            if (ttft == null) ttft = elapsed()
                            send(ChatEvent.Delta(content, reasoning))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw e
            throw ApiException("网络错误：${e.message ?: e.javaClass.simpleName}")
        }
        send(ChatEvent.Done(MsgMeta(model, prompt, completion, reasoningTokens, ttft, elapsed(), finish)))
    }
}
