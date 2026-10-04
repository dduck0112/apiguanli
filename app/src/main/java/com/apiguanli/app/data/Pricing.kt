package com.apiguanli.app.data

import com.apiguanli.app.net.PricingInfo
import com.apiguanli.app.net.PricingModel

/**
 * A model's price on one site, in the site's display units ("额度") and,
 * when the user set a recharge ratio, in real 元.
 */
data class PriceRow(
    val site: Site,
    val model: PricingModel,
    val baseName: String,
    val groupRatio: Double,
) {
    val perCall: Boolean get() = model.quotaType == 1

    /** Per-call price in display units. */
    val callDisplay: Double get() = site.unitsToDisplay(model.modelPrice * groupRatio)

    /** Per 1M input tokens in display units. NewAPI: ratio 1 = $2 / 1M tokens. */
    val inputDisplay: Double get() = site.unitsToDisplay(model.modelRatio * 2.0 * groupRatio)

    val outputDisplay: Double get() = inputDisplay * model.completionRatio

    val callCny: Double? get() = site.toCny(callDisplay)
    val inputCny: Double? get() = site.toCny(inputDisplay)
    val outputCny: Double? get() = site.toCny(outputDisplay)

    /** What we sort by when comparing: per-call price, or input price for token billing. */
    val sortCny: Double? get() = if (perCall) callCny else inputCny
}

private val prefixRegex = Regex("""^(\s*[\[【(（][^\]】)）]*[\]】)）]\s*)+""")

/** "[特价E]claude-sonnet-5" -> "claude-sonnet-5", so the same model matches across sites. */
fun normalizeModelName(name: String): String = name.replace(prefixRegex, "").trim().lowercase()

fun buildPriceRows(site: Site, info: PricingInfo): List<PriceRow> {
    val userGroup = site.balance?.group
    return info.models.distinctBy { it.name }.map { m ->
        val groups = m.enableGroups
        val ratio = if (userGroup != null && (groups.isEmpty() || userGroup in groups)) {
            info.groupRatio[userGroup] ?: 1.0
        } else {
            groups.mapNotNull { info.groupRatio[it] }.minOrNull() ?: 1.0
        }
        PriceRow(site, m, normalizeModelName(m.name), ratio)
    }
}
