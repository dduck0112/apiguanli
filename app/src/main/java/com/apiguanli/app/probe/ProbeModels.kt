package com.apiguanli.app.probe

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProbeMode(val label: String, val requests: Int) {
    QUICK("快速", 2),
    STANDARD("标准", 9),
    DEEP("深度", 10),
}

@Serializable
data class Usage(
    val prompt: Long? = null,
    val completion: Long? = null,
    val reasoning: Long? = null,
    val total: Long? = null,
)

/** What one request returned; kept raw so results can be re-compared later. */
@Serializable
data class CallObs(
    val ok: Boolean,
    val http: Int = 0,
    val error: String? = null,
    val model: String? = null,
    val id: String? = null,
    val objectType: String? = null,
    val finish: String? = null,
    val content: String = "",
    val reasoning: String = "",
    val usage: Usage? = null,
    val latencyMs: Long = 0,
    /** Streaming only: time to first content/reasoning token, and number of SSE chunks. */
    val ttftMs: Long? = null,
    val chunks: Int = 0,
    /** Tool calls as "name|arguments" strings. */
    val toolCalls: List<String> = emptyList(),
    /** Deviations from the OpenAI Chat Completions response shape. */
    val protocolIssues: List<String> = emptyList(),
) {
    val hasReasoning: Boolean get() = reasoning.isNotBlank() || (usage?.reasoning ?: 0) > 0
}

@Serializable
data class Probes(
    val mode: ProbeMode,
    val basic: CallObs? = null,
    val fingerprint: CallObs? = null,
    val stability: List<CallObs> = emptyList(),
    val tools: CallObs? = null,
    val structured: CallObs? = null,
    val stream: CallObs? = null,
    val capability: CallObs? = null,
    val longContext: CallObs? = null,
    val longNeedles: List<String> = emptyList(),
    val longApproxTokens: Int = 0,
    val requestCount: Int = 0,
)

@Serializable
enum class Verdict(val label: String) { PASS("正常"), WARN("存疑"), FAIL("异常"), SKIP("未测"), INFO("参考") }

@Serializable
data class CheckItem(
    val id: String,
    val name: String,
    val verdict: Verdict,
    /** 0..100; null for skipped / info-only items. */
    val score: Int?,
    val summary: String,
    val weight: Int,
)

@Serializable
data class TargetResult(
    val siteId: String,
    val siteName: String,
    val keyId: String,
    val model: String,
    val reference: Boolean,
    /** "pending" | "running" | "done" | "error" */
    val state: String = "pending",
    val progress: String = "",
    val probes: Probes? = null,
    val error: String? = null,
    val items: List<CheckItem> = emptyList(),
    val score: Int? = null,
    val verdict: Verdict? = null,
    /** Where the comparison numbers came from: "对照渠道", "内置基线", or none. */
    val baselineSource: String? = null,
)

@Serializable
data class CheckRun(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val baseModel: String,
    val mode: ProbeMode,
    val targets: List<TargetResult>,
)
