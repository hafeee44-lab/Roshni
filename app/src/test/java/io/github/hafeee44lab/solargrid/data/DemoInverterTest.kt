package io.github.hafeee44lab.solargrid.data

import io.github.hafeee44lab.solargrid.data.demo.DemoInverter
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoInverterTest {

    private val start = 1_760_000_000_000L

    @Test
    fun everySimulatedValueIsAFiniteNumber() {
        val demo = DemoInverter()
        var t = start
        while (t < start + 3 * 24 * 3_600_000L) {
            demo.metricsAt(t).forEach { metric ->
                val value = metric.value!!.toDoubleOrNull()
                if (value != null) assertTrue("${metric.key} = ${metric.value} at $t", value.isFinite())
            }
            t += 5 * 60_000L
        }
    }

    @Test
    fun theDemoGridGoesDownSometimesAndTheAnalyzerSeesIt() {
        val demo = DemoInverter()
        val verdicts = (0 until 24 * 12).map { step ->
            GridAnalyzer.evaluate(GridAnalyzer.read(demo.metricsAt(start + step * 5 * 60_000L)))!!.gridDown
        }
        assertTrue(verdicts.any { it })
        assertTrue(verdicts.any { !it })
    }

    @Test
    fun aForcedOutageIsHonoured() {
        val now = start + 12 * 3_600_000L
        val demo = DemoInverter(forcedOutage = { now - 600_000L until now + 600_000L })
        assertTrue(GridAnalyzer.evaluate(GridAnalyzer.read(demo.metricsAt(now)))!!.gridDown)
    }

    @Test
    fun aBackfillWindowOnlyHoldsWhatHappenedInsideIt() {
        val demo = DemoInverter()
        val from = start + 30 * 3_600_000L
        val now = from + 20 * 3_600_000L
        val (samples, outages, _) = demo.history(from = from, now = now)

        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.at > from && it.at <= now })
        assertTrue(outages.all { it.startedAt > from && (it.endedAt ?: 0L) <= now })
    }
}
