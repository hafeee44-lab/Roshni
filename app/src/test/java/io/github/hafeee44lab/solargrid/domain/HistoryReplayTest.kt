package io.github.hafeee44lab.solargrid.domain

import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReplayTest {

    private val step = 5 * 60_000L
    private val t0 = 1_760_000_000_000L

    private fun m(key: String, value: String, unit: String? = null, name: String = key) =
        SolarMetric(key = key, name = name, value = value, unit = unit)

    /** A reading with an explicit grid status, so each verdict is definitive. */
    private fun relay(on: Boolean, soc: Int = 80) = GridAnalyzer.read(
        listOf(m("GS_A1", if (on) "On-Grid" else "Off-Grid", name = "Grid Status"), m("B_left_cap1", "$soc", "%", "Battery SOC"))
    )

    /** No grid flow while the battery supplies the house: an inferred cut that needs a second reading. */
    private fun inferredDown() = GridAnalyzer.read(
        listOf(m("PG_Pt1", "0", "W", "Total Grid Power"), m("B_P1", "900", "W", "Battery Power"))
    )

    private fun frames(vararg readings: Reading) = readings.mapIndexed { i, r -> (t0 + i * step) to r }

    @Test
    fun aCutInHistoryBecomesAnOutageWithItsRealTimes() {
        val result = HistoryReplay.replay(
            frames(relay(true), relay(true), relay(false, 70), relay(false, 60), relay(true, 58), relay(true)),
            cutoff = Long.MAX_VALUE
        )
        assertEquals(1, result.outages.size)
        val cut = result.outages.single()
        assertEquals(t0 + 2 * step, cut.startedAt)
        assertEquals(t0 + 4 * step, cut.endedAt)
        assertEquals(70.0, cut.startSoc!!, 0.01)
        assertEquals(58.0, cut.endSoc!!, 0.01)
    }

    @Test
    fun anInferredCutStartsAtItsFirstReadingNotItsConfirmation() {
        val result = HistoryReplay.replay(frames(relay(true), inferredDown(), inferredDown(), relay(true)), cutoff = Long.MAX_VALUE)
        assertEquals(t0 + step, result.outages.single().startedAt)
    }

    @Test
    fun aSingleInferredBlipIsNotACut() {
        val result = HistoryReplay.replay(frames(relay(true), inferredDown(), relay(true)), cutoff = Long.MAX_VALUE)
        assertTrue(result.outages.isEmpty())
    }

    @Test
    fun readingsFromWhenLiveMonitoringBeganAreLeftAlone() {
        val result = HistoryReplay.replay(frames(relay(true), relay(true), relay(false), relay(false)), cutoff = t0 + 2 * step)
        assertEquals(2, result.points.size)
        assertTrue(result.outages.isEmpty())
    }

    @Test
    fun aCutStillRunningAtTheCutoffClosesAtTheLastReading() {
        val result = HistoryReplay.replay(frames(relay(true), relay(false), relay(false), relay(false)), cutoff = t0 + 3 * step)
        val cut = result.outages.single()
        assertEquals(t0 + step, cut.startedAt)
        assertEquals(t0 + 2 * step, cut.endedAt)
    }

    @Test
    fun replayMarksEachPointWithTheMainsState() {
        val result = HistoryReplay.replay(frames(relay(true), relay(false)), cutoff = Long.MAX_VALUE)
        assertEquals(false, result.points[0].gridDown)
        assertEquals(true, result.points[1].gridDown)
    }

    @Test
    fun emptyHistoryIsHarmless() {
        val result = HistoryReplay.replay(emptyList(), cutoff = Long.MAX_VALUE)
        assertTrue(result.points.isEmpty())
        assertNull(result.outages.firstOrNull())
    }
}
