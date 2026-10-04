package com.apiguanli.app

import com.apiguanli.app.probe.Evaluator
import com.apiguanli.app.probe.ProbeMode
import com.apiguanli.app.probe.ProbeRunner
import com.apiguanli.app.probe.ProbeTarget
import com.apiguanli.app.probe.Probes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs the model checkup against a real OpenAI-compatible channel. Skipped unless PROBE_BASE/PROBE_KEY are set. */
class ProbeLiveTest {
    private val base = System.getenv("PROBE_BASE")
    private val key = System.getenv("PROBE_KEY")
    private val models = System.getenv("PROBE_MODELS")?.split(',')?.map(String::trim) ?: emptyList()
    private val mode = System.getenv("PROBE_MODE")?.let { ProbeMode.valueOf(it) } ?: ProbeMode.STANDARD

    @Test
    fun capabilityGrading() {
        val g = Evaluator.gradeCapability("1: 23,223,123\n2: 6\n3: x9mL-7qp\n4: Friday")
        assertEquals(listOf(true, true, true, true), g)
        val bad = Evaluator.gradeCapability("**1:** 23223124\n2. 5\n3) x9ml-7qp\n4：星期四")
        assertEquals(listOf(false, false, false, false), bad)
    }

    @Test
    fun liveCheckup() = runBlocking {
        assumeTrue("live env not set", base != null && key != null && models.isNotEmpty())
        val results = mutableMapOf<String, Probes>()
        for (m in models) {
            val p = ProbeRunner(ProbeTarget(base!!.trimEnd('/') + "/chat/completions", key!!, m)) { }.run(mode)
            results[m] = p
            report("$m vs 内置基线", Evaluator.evaluate(m, p, null))
        }
        // Cross check: evaluate the first model as if the second were its trusted reference.
        if (models.size >= 2) {
            val a = models[0]
            val b = models[1]
            report("$a 冒充 $b（以真 $b 为对照）", Evaluator.evaluate(b, results.getValue(a), results.getValue(b)))
        }
    }

    private fun report(title: String, e: com.apiguanli.app.probe.Evaluation) {
        println("===== $title → ${e.score} ${e.verdict.label} (基线: ${e.baselineSource})")
        e.items.forEach { println("  [${it.verdict.label}] ${it.name} ${it.score ?: "-"}: ${it.summary}") }
    }
}
