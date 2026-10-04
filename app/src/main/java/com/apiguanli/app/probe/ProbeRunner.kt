package com.apiguanli.app.probe

import com.apiguanli.app.net.Http
import com.apiguanli.app.net.arr
import com.apiguanli.app.net.asObj
import com.apiguanli.app.net.long
import com.apiguanli.app.net.obj
import com.apiguanli.app.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Fixed probe texts. Changing them invalidates [Baselines], which were measured with these exact strings. */
object ProbeTexts {
    const val SHORT = "Reply with exactly: ok"

    // Chinese, English, code, numbers, emoji and other scripts: tokenizers split these very differently.
    const val FINGERPRINT = "中转站检测指纹：今天天气晴朗，适合写代码。The quick brown fox jumps over the lazy dog. " +
        "def fib(n): return n if n < 2 else fib(n-1) + fib(n-2)  # 递归 " +
        "1234567890 3.14159 😀🚀 ありがとう Привет мир"

    const val STABILITY = "In one sentence, explain HTTP status 418."

    const val TOOLS = "Use get_current_weather for Boston, MA in celsius. Do not answer directly."

    const val STRUCTURED = "Return JSON with ok=true and nonce=\"chk-7Q2x\"."
    const val STRUCTURED_NONCE = "chk-7Q2x"

    /** Questions with checkable answers; see [Evaluator.gradeCapability]. */
    const val CAPABILITY = "Answer each question on its own line in the format \"N: answer\", with no explanation.\n" +
        "1. What is 7843 × 2961? Give only the number.\n" +
        "2. How many times does the letter r appear in \"strawberry raspberry\"?\n" +
        "3. Reverse the string \"pq7-Lm9x\" character by character.\n" +
        "4. If today is Wednesday, what day of the week is it 100 days from now?"
}

/** [chatUrl] is the full OpenAI-compatible chat completions URL. */
data class ProbeTarget(val chatUrl: String, val key: String, val model: String)

/**
 * Sends the probe requests for one target. Requests run one after another
 * (relays often rate-limit), and every request is recorded even when it fails.
 */
class ProbeRunner(private val target: ProbeTarget, private val onProgress: (String) -> Unit) {
    private val http = Http.base.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()
    private var requests = 0

    // Reasoning models spend tokens thinking before answering; a small cap would leave the answer empty.
    private val answerBudget = 1500

    suspend fun run(mode: ProbeMode): Probes {
        step("基础请求")
        val basic = call(user(ProbeTexts.SHORT))
        step("分词指纹")
        val fingerprint = call(user(ProbeTexts.SHORT + "\n\n" + ProbeTexts.FINGERPRINT))
        var p = Probes(mode, basic = basic, fingerprint = fingerprint)
        if (mode == ProbeMode.QUICK) return p.copy(requestCount = requests)

        val stability = (1..3).map { i ->
            step("稳定性 $i/3")
            call(user(ProbeTexts.STABILITY), maxTokens = answerBudget)
        }
        step("函数调用")
        val tools = call(user(ProbeTexts.TOOLS)) {
            putJsonArray("tools") { add(weatherTool()) }
        }
        step("结构化输出")
        val structured = call(user(ProbeTexts.STRUCTURED)) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "check_result")
                    put("strict", true)
                    put("schema", structuredSchema())
                }
            }
        }
        step("流式一致性")
        val stream = streamCall(user(ProbeTexts.SHORT))
        step("能力题")
        val capability = call(user(ProbeTexts.CAPABILITY), maxTokens = 4000)
        p = p.copy(stability = stability, tools = tools, structured = structured, stream = stream, capability = capability)
        if (mode == ProbeMode.STANDARD) return p.copy(requestCount = requests)

        step("长上下文（约 2.2 万 tokens）")
        val (prompt, needles) = LongContext.build(seed = 20261004, targetWords = 18000)
        val long = call(user(prompt), maxTokens = answerBudget)
        return p.copy(longContext = long, longNeedles = needles, longApproxTokens = 22000, requestCount = requests)
    }

    private suspend fun step(name: String) {
        currentCoroutineContext().ensureActive()
        onProgress(name)
    }

    private fun user(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

    private suspend fun call(
        message: JsonObject,
        maxTokens: Int = answerBudget,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): CallObs {
        val body = buildJsonObject {
            put("model", target.model)
            put("temperature", 0)
            put("max_tokens", maxTokens)
            putJsonArray("messages") { add(message) }
            extra()
        }
        return withContext(Dispatchers.IO) {
            requests++
            val start = System.nanoTime()
            try {
                http.newCall(request(body)).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val ms = (System.nanoTime() - start) / 1_000_000
                    val o = runCatching { Http.json.parseToJsonElement(text) }.getOrNull().asObj()
                    if (!resp.isSuccessful || o == null || o.obj("error") != null) {
                        val msg = o?.obj("error")?.str("message") ?: o?.str("message") ?: text.take(300)
                        return@use CallObs(ok = false, http = resp.code, error = msg.ifBlank { "HTTP ${resp.code}" }, latencyMs = ms)
                    }
                    parseCompletion(o, resp.code, ms)
                }
            } catch (e: IOException) {
                CallObs(ok = false, error = "网络错误：${e.message ?: e.javaClass.simpleName}",
                    latencyMs = (System.nanoTime() - start) / 1_000_000)
            }
        }
    }

    private suspend fun streamCall(message: JsonObject): CallObs {
        val body = buildJsonObject {
            put("model", target.model)
            put("temperature", 0)
            put("max_tokens", answerBudget)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") { add(message) }
        }
        return withContext(Dispatchers.IO) {
            requests++
            val start = System.nanoTime()
            fun elapsed() = (System.nanoTime() - start) / 1_000_000
            try {
                http.newCall(request(body)).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        val o = runCatching { Http.json.parseToJsonElement(text) }.getOrNull().asObj()
                        val msg = o?.obj("error")?.str("message") ?: text.take(300)
                        return@use CallObs(ok = false, http = resp.code, error = msg.ifBlank { "HTTP ${resp.code}" }, latencyMs = elapsed())
                    }
                    val source = resp.body?.source() ?: return@use CallObs(ok = false, http = resp.code, error = "空响应")
                    val content = StringBuilder()
                    val reasoning = StringBuilder()
                    var model: String? = null
                    var finish: String? = null
                    var usage: Usage? = null
                    var ttft: Long? = null
                    var chunks = 0
                    var sawDone = false
                    val issues = mutableListOf<String>()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") { sawDone = true; break }
                        val o = runCatching { Http.json.parseToJsonElement(payload) }.getOrNull().asObj() ?: continue
                        chunks++
                        o.obj("error")?.str("message")?.let { return@use CallObs(ok = false, http = resp.code, error = it, latencyMs = elapsed()) }
                        if (chunks == 1 && o.str("object") != null && o.str("object") != "chat.completion.chunk") {
                            issues += "流式 object 字段是 ${o.str("object")}，应为 chat.completion.chunk"
                        }
                        o.str("model")?.takeIf { it.isNotBlank() }?.let { model = it }
                        o.obj("usage")?.let { usage = parseUsage(it) }
                        val choice = o.arr("choices")?.firstOrNull().asObj() ?: continue
                        choice.str("finish_reason")?.let { finish = it }
                        val delta = choice.obj("delta") ?: continue
                        val c = delta.str("content").orEmpty()
                        val r = delta.str("reasoning_content") ?: delta.str("reasoning").orEmpty()
                        if ((c.isNotEmpty() || r.isNotEmpty()) && ttft == null) ttft = elapsed()
                        content.append(c)
                        reasoning.append(r)
                    }
                    if (!sawDone) issues += "流式结束时没有 [DONE] 标记"
                    if (usage == null) issues += "流式响应没有返回用量（include_usage）"
                    CallObs(
                        ok = true, http = resp.code, model = model, finish = finish,
                        content = content.toString(), reasoning = reasoning.toString(), usage = usage,
                        latencyMs = elapsed(), ttftMs = ttft, chunks = chunks, protocolIssues = issues,
                    )
                }
            } catch (e: IOException) {
                CallObs(ok = false, error = "网络错误：${e.message ?: e.javaClass.simpleName}", latencyMs = elapsed())
            }
        }
    }

    private fun request(body: JsonObject) = Request.Builder()
        .url(target.chatUrl)
        .header("Authorization", "Bearer ${target.key}")
        .header("User-Agent", Http.USER_AGENT)
        .post(body.toString().toRequestBody(Http.jsonType))
        .build()

    private fun parseUsage(u: JsonObject) = Usage(
        prompt = u.long("prompt_tokens") ?: u.long("input_tokens"),
        completion = u.long("completion_tokens") ?: u.long("output_tokens"),
        reasoning = u.obj("completion_tokens_details")?.long("reasoning_tokens"),
        total = u.long("total_tokens"),
    )

    private fun parseCompletion(o: JsonObject, http: Int, ms: Long): CallObs {
        val issues = mutableListOf<String>()
        if (o.str("object") != "chat.completion") issues += "object 字段是 ${o.str("object") ?: "空"}，应为 chat.completion"
        if (o.str("id").isNullOrBlank()) issues += "缺少 id"
        if (o.long("created") == null) issues += "缺少 created 时间戳"
        val choices = o.arr("choices")
        if (choices.isNullOrEmpty()) issues += "choices 为空"
        val choice = choices?.firstOrNull().asObj()
        val msg = choice?.obj("message")
        if (msg != null && msg.str("role") != "assistant") issues += "message.role 不是 assistant"
        if (choice != null && choice.str("finish_reason") == null) issues += "缺少 finish_reason"
        val usage = o.obj("usage")?.let(::parseUsage)
        if (usage == null) issues += "缺少 usage 用量"
        else {
            if (usage.prompt == null || usage.completion == null) issues += "usage 缺少 prompt/completion tokens"
            val p = usage.prompt
            val c = usage.completion
            val t = usage.total
            if (p != null && c != null && t != null && t != p + c) issues += "total_tokens($t) ≠ 输入($p)+输出($c)"
        }
        val tools = (msg?.get("tool_calls") as? JsonArray).orEmpty().mapNotNull { tc ->
            val f = tc.asObj()?.obj("function") ?: return@mapNotNull null
            "${f.str("name").orEmpty()}|${f.str("arguments").orEmpty()}"
        }
        return CallObs(
            ok = true, http = http, model = o.str("model"), id = o.str("id"), objectType = o.str("object"),
            finish = choice?.str("finish_reason"),
            content = msg?.str("content").orEmpty(),
            reasoning = msg?.str("reasoning_content") ?: msg?.str("reasoning").orEmpty(),
            usage = usage, latencyMs = ms, toolCalls = tools, protocolIssues = issues,
        )
    }

    private fun weatherTool() = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "get_current_weather")
            put("description", "Get the current weather in a given location")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("location") { put("type", "string"); put("description", "City and state, e.g. San Francisco, CA") }
                    putJsonObject("unit") {
                        put("type", "string")
                        putJsonArray("enum") { add("celsius"); add("fahrenheit") }
                    }
                }
                putJsonArray("required") { add("location"); add("unit") }
            }
        }
    }

    private fun structuredSchema() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("ok") { put("type", "boolean") }
            putJsonObject("nonce") { put("type", "string") }
        }
        putJsonArray("required") { add("ok"); add("nonce") }
        put("additionalProperties", JsonPrimitive(false))
    }
}

/** Needle-in-a-haystack text: three secret codes hidden at 10%, 50% and 90% of a long filler text. */
object LongContext {
    private val words = ("river mountain lantern copper meadow harbor violet engine pocket garden silver window " +
        "thunder pillow marble orchard canvas whistle compass ladder velvet harvest anchor blanket " +
        "falcon cobalt prairie timber saddle crystal beacon pepper glacier ribbon quarry walnut").split(' ')
    private val colors = listOf("red", "green", "blue")

    fun build(seed: Int, targetWords: Int): Pair<String, List<String>> {
        val rnd = Random(seed)
        val codes = colors.map { (100000 + rnd.nextInt(900000)).toString() }
        val positions = listOf(0.1, 0.5, 0.9).map { (it * targetWords).toInt() }
        val sb = StringBuilder()
        val inserted = BooleanArray(colors.size)
        var sentence = 0
        var w = 0
        while (w < targetWords) {
            positions.forEachIndexed { i, pos ->
                if (!inserted[i] && w >= pos) {
                    sb.append("The secret code for ${colors[i]} is ${codes[i]}. ")
                    inserted[i] = true
                }
            }
            val len = 8 + rnd.nextInt(5)
            val s = (0 until len).joinToString(" ") { words[rnd.nextInt(words.size)] }
            sb.append(s.replaceFirstChar { it.uppercase() }).append(". ")
            w += len
            if (++sentence % 6 == 0) sb.append("\n")
        }
        val prompt = "Read the following text carefully.\n\n" + sb +
            "\n\nQuestion: What are the secret codes for red, green and blue mentioned in the text above? " +
            "Answer exactly in the form: red=XXXXXX, green=XXXXXX, blue=XXXXXX"
        return prompt to codes
    }
}
