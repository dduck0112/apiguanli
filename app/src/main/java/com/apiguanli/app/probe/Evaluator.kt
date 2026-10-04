package com.apiguanli.app.probe

import com.apiguanli.app.data.normalizeModelName
import com.apiguanli.app.net.Http
import com.apiguanli.app.net.asObj
import com.apiguanli.app.net.bool
import com.apiguanli.app.net.str
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reference numbers measured on the user's trusted channel (Volcengine Ark coding plan, 2026-10-04)
 * with the exact [ProbeTexts.SHORT] and [ProbeTexts.FINGERPRINT] strings. Used when a check runs
 * without a live reference target.
 *  - short: input tokens of the one-line prompt (includes the provider's chat template)
 *  - delta: extra input tokens the fingerprint text adds; depends only on the tokenizer
 */
object Baselines {
    data class Numbers(val short: Long, val delta: Long)

    private val ark = mapOf(
        "deepseek-v4-1-flash" to Numbers(35, 72),
        "deepseek-v4-pro" to Numbers(88, 72),
        "kimi-k3" to Numbers(90, 74),
        "glm-5-3" to Numbers(17, 75),
        "glm-5-3-flash" to Numbers(17, 75),
    )

    fun find(model: String): Numbers? = ark[canonicalModel(model)]
}

/** "[次]DeepSeek-V4.1-Flash" -> "deepseek-v4-1-flash": ignores relay prefixes and dot/underscore spelling. */
fun canonicalModel(name: String): String = normalizeModelName(name).replace('.', '-').replace('_', '-')

/** Response model names may carry a version suffix ("deepseek-v4-1-flash-260910"), so match by prefix. */
fun modelsMatch(requested: String, returned: String): Boolean {
    val a = canonicalModel(requested)
    val b = canonicalModel(returned)
    return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.startsWith(b) || b.startsWith(a))
}

data class Evaluation(val items: List<CheckItem>, val score: Int?, val verdict: Verdict, val baselineSource: String?)

object Evaluator {
    fun evaluate(model: String, p: Probes, ref: Probes?): Evaluation {
        val builtin = Baselines.find(model)
        val refShort = ref?.basic?.usage?.prompt ?: builtin?.short
        val refDelta = ref?.let { delta(it) } ?: builtin?.delta
        val source = when {
            ref != null -> "对照渠道"
            builtin != null -> "内置基线"
            else -> null
        }
        val src = source ?: "对照"

        val items = mutableListOf<CheckItem>()
        val basic = p.basic
        items += basicItem(basic)
        if (basic == null || !basic.ok) {
            // Nothing else is meaningful when the model can't even answer.
            return Evaluation(items, 0, Verdict.FAIL, source)
        }
        items += modelItem(model, basic)
        items += protocolItem(listOfNotNull(basic, p.fingerprint, p.stream))
        items += hiddenPromptItem(basic.usage?.prompt, refShort, src)
        items += tokenizerItem(delta(p), refDelta, src)
        items += thinkingItem(p, ref)

        if (p.mode != ProbeMode.QUICK) {
            items += stabilityItem(p.stability, ref?.stability)
            items += toolsItem(p.tools, ref?.tools)
            items += structuredItem(p.structured, ref?.structured)
            items += streamItem(p.stream, basic)
            items += capabilityItem(p.capability, ref?.capability)
            items += speedItem(p.stream, ref?.stream)
        }
        if (p.mode == ProbeMode.DEEP) items += longContextItem(p, ref)

        val scored = items.filter { it.score != null && it.weight > 0 }
        var score = if (scored.isEmpty()) null
        else (scored.sumOf { it.score!! * it.weight }.toDouble() / scored.sumOf { it.weight }).toInt()
        // Strong evidence of a swapped model or injected prompt outweighs everything that looks fine.
        val critical = items.any { it.verdict == Verdict.FAIL && it.id in setOf("model", "hidden", "tokenizer") }
        if (critical && score != null) score = minOf(score, 59)
        val verdict = when {
            score == null -> Verdict.SKIP
            score >= 80 -> Verdict.PASS
            score >= 60 -> Verdict.WARN
            else -> Verdict.FAIL
        }
        return Evaluation(items, score, verdict, source)
    }

    private fun delta(p: Probes): Long? {
        val a = p.basic?.usage?.prompt ?: return null
        val b = p.fingerprint?.takeIf { it.ok }?.usage?.prompt ?: return null
        return b - a
    }

    private fun item(id: String, name: String, weight: Int, verdict: Verdict, score: Int?, summary: String) =
        CheckItem(id, name, verdict, score, summary, weight)

    private fun basicItem(b: CallObs?): CheckItem {
        val n = "基础请求"
        return when {
            b == null -> item("basic", n, 15, Verdict.FAIL, 0, "没有发出请求")
            !b.ok -> item("basic", n, 15, Verdict.FAIL, 0, "请求失败：${b.error}")
            b.content.trim().lowercase().trimEnd('.', '。', '!') == "ok" ->
                item("basic", n, 15, Verdict.PASS, 100, "按要求只回复了 ok · 耗时 ${secs(b.latencyMs)}")
            b.content.isNotBlank() -> item("basic", n, 15, Verdict.WARN, 70, "回复了，但没按要求只说 ok：${oneLine(b.content)}")
            b.finish == "length" && b.hasReasoning ->
                item("basic", n, 15, Verdict.WARN, 70, "思考用完了 token 额度，没来得及给出答案")
            else -> item("basic", n, 15, Verdict.FAIL, 30, "返回内容为空")
        }
    }

    private fun modelItem(model: String, b: CallObs): CheckItem {
        val n = "模型名"
        val returned = b.model
        return when {
            returned.isNullOrBlank() -> item("model", n, 10, Verdict.WARN, 50, "响应里没有 model 字段")
            modelsMatch(model, returned) -> item("model", n, 10, Verdict.PASS, 100, "返回 $returned，与请求一致")
            else -> item("model", n, 10, Verdict.FAIL, 0, "请求的是 $model，返回的却是 $returned")
        }
    }

    private fun protocolItem(obs: List<CallObs>): CheckItem {
        val issues = obs.filter { it.ok }.flatMap { it.protocolIssues }.distinct()
        val score = maxOf(0, 100 - 20 * issues.size)
        val v = when {
            issues.isEmpty() -> Verdict.PASS
            issues.size <= 2 -> Verdict.WARN
            else -> Verdict.FAIL
        }
        val summary = if (issues.isEmpty()) "响应格式符合 OpenAI 规范" else issues.take(3).joinToString("；")
        return item("protocol", "接口格式", 10, v, score, summary)
    }

    private fun hiddenPromptItem(short: Long?, refShort: Long?, src: String): CheckItem {
        val n = "隐藏提示词"
        if (short == null) return item("hidden", n, 15, Verdict.SKIP, null, "没有返回用量，无法判断")
        if (refShort == null) {
            return when {
                short <= 120 -> item("hidden", n, 0, Verdict.INFO, null, "一句话算了 $short 个输入 tokens（没有对照，仅供参考）")
                short <= 400 -> item("hidden", n, 15, Verdict.WARN, 60, "一句话就算了 $short 个输入 tokens，偏多")
                else -> item("hidden", n, 15, Verdict.FAIL, 20, "一句话算了 $short 个输入 tokens：很可能被注入了系统提示词")
            }
        }
        val diff = short - refShort
        return when {
            abs(diff) <= 3 -> item("hidden", n, 15, Verdict.PASS, 100, "输入 $short tokens，与${src}（$refShort）一致")
            diff in 4..30 -> item("hidden", n, 15, Verdict.WARN, 70, "比${src}多 $diff tokens，可能加了少量提示词")
            diff > 30 -> item("hidden", n, 15, Verdict.FAIL, 10,
                "比${src}多 $diff tokens：疑似被注入了系统提示词，每次请求都会多扣这部分费用")
            else -> item("hidden", n, 15, Verdict.WARN, 70, "比${src}少 ${-diff} tokens：对话模板不同，可能不是同一上游")
        }
    }

    private fun tokenizerItem(delta: Long?, refDelta: Long?, src: String): CheckItem {
        val n = "分词指纹"
        if (delta == null) return item("tokenizer", n, 20, Verdict.SKIP, null, "没有拿到两次请求的用量，无法判断")
        if (refDelta == null) return item("tokenizer", n, 0, Verdict.INFO, null, "指纹增量 $delta（缺少对照，无法判断）")
        val d = abs(delta - refDelta)
        return when {
            d == 0L -> item("tokenizer", n, 20, Verdict.PASS, 100, "指纹增量 $delta，与${src}完全一致：同一种分词器")
            d <= 2 -> item("tokenizer", n, 20, Verdict.WARN, 75, "指纹增量 $delta，${src}是 $refDelta，略有差异")
            else -> item("tokenizer", n, 20, Verdict.FAIL, 0,
                "指纹增量 $delta，${src}是 $refDelta：分词方式不同，很可能不是同一家的模型")
        }
    }

    private fun thinkingItem(p: Probes, ref: Probes?): CheckItem {
        val n = "思考过程"
        val thinks = listOfNotNull(p.basic, p.fingerprint, p.stream).any { it.ok && it.hasReasoning }
        if (ref == null) {
            return item("thinking", n, 0, Verdict.INFO, null, if (thinks) "返回了思考内容" else "没有返回思考内容")
        }
        val refThinks = listOfNotNull(ref.basic, ref.fingerprint, ref.stream).any { it.ok && it.hasReasoning }
        return when {
            refThinks && !thinks -> item("thinking", n, 10, Verdict.WARN, 30, "对照渠道会返回思考过程，这里没有：思考可能被关闭或剥离")
            !refThinks && thinks -> item("thinking", n, 0, Verdict.INFO, null, "这里返回了思考内容，对照渠道没有")
            else -> item("thinking", n, 10, Verdict.PASS, 100, if (thinks) "与对照一致，都有思考过程" else "与对照一致，都没有思考过程")
        }
    }

    /** Answer length (without reasoning) across 3 identical temperature-0 requests. */
    private fun cv(runs: List<CallObs>?): Double? {
        val lens = runs.orEmpty().filter { it.ok }.map { r ->
            val u = r.usage
            if (u?.completion != null) (u.completion - (u.reasoning ?: 0)).toDouble() else r.content.length.toDouble()
        }.filter { it > 0 }
        if (lens.size < 2) return null
        val mean = lens.average()
        return sqrt(lens.sumOf { (it - mean) * (it - mean) } / lens.size) / mean
    }

    private fun stabilityItem(runs: List<CallObs>, ref: List<CallObs>?): CheckItem {
        val n = "输出稳定性"
        val failed = runs.count { !it.ok }
        if (failed == runs.size) return item("stability", n, 5, Verdict.FAIL, 0, "3 次请求都失败：${runs.firstOrNull()?.error}")
        val c = cv(runs) ?: return item("stability", n, 5, Verdict.SKIP, null, "数据不足")
        val refCv = cv(ref)
        val text = "3 次相同请求的回答长度波动 ${pct(c)}" + (refCv?.let { "（对照 ${pct(it)}）" } ?: "") +
            if (failed > 0) "，其中 $failed 次失败" else ""
        val lenient = refCv != null && c <= refCv + 0.1
        return when {
            failed > 0 -> item("stability", n, 5, Verdict.WARN, 50, text)
            c < 0.15 || lenient -> item("stability", n, 5, Verdict.PASS, 100, text)
            c < 0.4 -> item("stability", n, 5, Verdict.WARN, 70, text)
            else -> item("stability", n, 5, Verdict.FAIL, 30, "$text：波动很大，可能混用了多个上游")
        }
    }

    private fun toolsItem(o: CallObs?, ref: CallObs?): CheckItem {
        val n = "函数调用"
        val refCalled = ref?.toolCalls?.any { it.startsWith("get_current_weather|") }
        if (o == null) return item("tools", n, 10, Verdict.SKIP, null, "未测")
        if (!o.ok) {
            return if (ref != null && refCalled != true) item("tools", n, 0, Verdict.SKIP, null, "失败，但对照渠道也不支持")
            else item("tools", n, 10, Verdict.FAIL, 0, "请求失败：${o.error}")
        }
        val call = o.toolCalls.firstOrNull { it.startsWith("get_current_weather|") }
        if (call == null) {
            return if (ref != null && refCalled != true) item("tools", n, 0, Verdict.SKIP, null, "没有调用工具，对照渠道也没有")
            else item("tools", n, 10, Verdict.FAIL, 0, "没有调用工具，直接回答了：${oneLine(o.content)}")
        }
        val args = call.substringAfter('|').lowercase()
        return if ("boston" in args && "celsius" in args) item("tools", n, 10, Verdict.PASS, 100, "正确调用 get_current_weather")
        else item("tools", n, 10, Verdict.WARN, 60, "调用了工具，但参数不对：${call.substringAfter('|').take(60)}")
    }

    private fun parseStructured(o: CallObs?): Pair<Boolean, Boolean>? {
        if (o == null || !o.ok) return null
        val raw = o.content.trim()
        val fenced = raw.startsWith("```")
        val body = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { Http.json.parseToJsonElement(body) }.getOrNull().asObj() ?: return false to fenced
        return (obj.bool("ok") == true && obj.str("nonce") == ProbeTexts.STRUCTURED_NONCE) to fenced
    }

    private fun structuredItem(o: CallObs?, ref: CallObs?): CheckItem {
        val n = "结构化输出"
        val r = parseStructured(o)
        val refOk = parseStructured(ref)?.first
        return when {
            o == null -> item("structured", n, 5, Verdict.SKIP, null, "未测")
            r == null || !r.first -> if (ref != null && refOk != true) item("structured", n, 0, Verdict.SKIP, null, "不符合，但对照渠道也不支持")
            else item("structured", n, 5, Verdict.FAIL, 0, if (!o.ok) "请求失败：${o.error}" else "没有按 JSON 格式返回：${oneLine(o.content)}")
            r.second -> item("structured", n, 5, Verdict.PASS, 85, "内容正确，但包在了代码块里")
            else -> item("structured", n, 5, Verdict.PASS, 100, "严格按 JSON 格式返回")
        }
    }

    private fun streamItem(s: CallObs?, basic: CallObs): CheckItem {
        val n = "流式一致性"
        if (s == null) return item("stream", n, 10, Verdict.SKIP, null, "未测")
        if (!s.ok) return item("stream", n, 10, Verdict.FAIL, 0, "流式请求失败：${s.error}")
        var score = 40
        val notes = mutableListOf<String>()
        if (s.content.trim().equals(basic.content.trim(), ignoreCase = true)) score += 20
        else notes += "流式和非流式回答不同"
        if (s.usage != null) score += 20 else notes += "没有返回流式用量"
        val sp = s.usage?.prompt
        val bp = basic.usage?.prompt
        if (sp != null && bp != null && sp == bp) score += 20
        else if (sp != null && bp != null) notes += "同一句话流式算 $sp tokens、非流式算 $bp tokens"
        notes += s.protocolIssues.filter { "include_usage" !in it }
        val v = when {
            score >= 80 -> Verdict.PASS
            score >= 60 -> Verdict.WARN
            else -> Verdict.FAIL
        }
        return item("stream", n, 10, v, score, if (notes.isEmpty()) "流式与非流式结果一致（${s.chunks} 个数据块）" else notes.joinToString("；"))
    }

    /** Which of the 4 [ProbeTexts.CAPABILITY] questions were answered correctly. */
    fun gradeCapability(content: String): List<Boolean> {
        val answers = mutableMapOf<Int, String>()
        content.lines().forEach { line ->
            val m = Regex("""^\s*\**\s*(\d)\s*[:：.)、]\s*(.*)$""").find(line) ?: return@forEach
            answers[m.groupValues[1].toInt()] = m.groupValues[2].trim().trim('*', '`', '"', '“', '”', '\'')
        }
        val a1 = answers[1].orEmpty().filter(Char::isDigit)
        val a2 = answers[2].orEmpty().lowercase()
        val a3 = answers[3].orEmpty()
        val a4 = answers[4].orEmpty().lowercase()
        return listOf(
            a1 == "23223123",
            Regex("""(^|\D)6(\D|$)""").containsMatchIn(a2) || "six" in a2,
            a3 == "x9mL-7qp",
            "friday" in a4 || "星期五" in a4 || "周五" in a4,
        )
    }

    private fun capabilityItem(o: CallObs?, ref: CallObs?): CheckItem {
        val n = "能力题"
        if (o == null) return item("capability", n, 10, Verdict.SKIP, null, "未测")
        if (!o.ok) return item("capability", n, 10, Verdict.FAIL, 0, "请求失败：${o.error}")
        val g = gradeCapability(o.content)
        val right = g.count { it }
        val refRight = ref?.takeIf { it.ok }?.let { gradeCapability(it.content).count { r -> r } }
        val wrong = g.withIndex().filter { !it.value }.joinToString("、") { "第${it.index + 1}题" }
        val text = "答对 $right/4" + (refRight?.let { "（对照 $it/4）" } ?: "") + if (wrong.isNotEmpty()) "，错了$wrong" else ""
        val gap = (refRight ?: 4) - right
        return when {
            gap <= 0 -> item("capability", n, 10, Verdict.PASS, 100, text)
            gap == 1 -> item("capability", n, 10, Verdict.WARN, 60, text)
            else -> item("capability", n, 10, Verdict.FAIL, 20, "$text：明显比对照差，可能换成了更弱的模型")
        }
    }

    private fun speed(s: CallObs?): Pair<Long, Long?>? {
        if (s == null || !s.ok) return null
        val ttft = s.ttftMs ?: return null
        val out = s.usage?.completion
        val genMs = s.latencyMs - ttft
        val tps = if (out != null && out > 0 && genMs > 200) out * 1000 / genMs else null
        return ttft to tps
    }

    private fun speedItem(s: CallObs?, ref: CallObs?): CheckItem {
        val mine = speed(s) ?: return item("speed", "速度", 0, Verdict.SKIP, null, "没有流式数据")
        val r = speed(ref)
        fun fmt(x: Pair<Long, Long?>) = "首字 ${secs(x.first)}" + (x.second?.let { " · $it tok/s" } ?: "")
        return item("speed", "速度", 0, Verdict.INFO, null, fmt(mine) + (r?.let { "（对照 ${fmt(it)}）" } ?: ""))
    }

    private fun longContextItem(p: Probes, ref: Probes?): CheckItem {
        val n = "长上下文"
        val o = p.longContext ?: return item("long", n, 15, Verdict.SKIP, null, "未测")
        if (!o.ok) return item("long", n, 15, Verdict.FAIL, 0, "约 ${p.longApproxTokens / 1000}k tokens 的请求被拒绝：${o.error}")
        val found = p.longNeedles.count { it in o.content }
        val reported = o.usage?.prompt
        val expected = ref?.longContext?.usage?.prompt ?: p.longApproxTokens.toLong()
        val truncated = reported != null && reported < expected * 0.6
        val text = "藏在开头、中间、末尾的 3 个暗号找到 $found 个" +
            (reported?.let { "，计费输入 ${it / 1000}k tokens" } ?: "") +
            if (truncated) "，明显少于实际长度（约 ${expected / 1000}k）：上下文被截断了" else ""
        return when {
            found == 3 && !truncated -> item("long", n, 15, Verdict.PASS, 100, text)
            found == 2 && !truncated -> item("long", n, 15, Verdict.WARN, 66, text)
            else -> item("long", n, 15, Verdict.FAIL, if (found == 2) 50 else 0, text)
        }
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim().take(40)

    private fun secs(ms: Long) = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    private fun pct(x: Double) = "${(x * 100).toInt()}%"
}
