package io.github.hafeee44lab.solargrid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class InsightsTest {

    private val min = 60_000L
    private val hour = 60 * min

    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 20, 18, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val today = OutageLog.startOfDay(now)

    @Test
    fun uptimeCountsOnlyTheWindow() {
        val records = listOf(
            OutageRecord(startedAt = today + 8 * hour, endedAt = today + 9 * hour),
            OutageRecord(startedAt = today + 14 * hour, endedAt = today + 14 * hour + 30 * min)
        )
        val stats = Insights.range(records, today, now)
        assertEquals(2, stats.count)
        assertEquals(90 * min, stats.downMs)
        assertEquals(1.0 - 1.5 / 18.0, stats.uptime!!, 0.0001)
        assertEquals(45 * min, stats.averageMs)
    }

    @Test
    fun anOutageCrossingTheWindowEdgeIsClipped() {
        val records = listOf(OutageRecord(startedAt = today - 2 * hour, endedAt = today + hour))
        val stats = Insights.range(records, today, now)
        assertEquals(hour, stats.downMs)
    }

    @Test
    fun anOngoingOutageRunsToNow() {
        val records = listOf(OutageRecord(startedAt = now - 2 * hour))
        assertEquals(2 * hour, Insights.range(records, today, now).downMs)
    }

    @Test
    fun timeBeforeMonitoringBeganIsNotCountedAsUp() {
        val records = listOf(OutageRecord(startedAt = now - 3 * hour, endedAt = now - 2 * hour))
        val stats = Insights.range(records, now - 7 * 24 * hour, now, observedSince = now - 6 * hour)
        assertEquals(5.0 / 6.0, stats.uptime!!, 0.0001)
    }

    @Test
    fun tooShortAWindowClaimsNoUptime() {
        assertNull(Insights.range(emptyList(), now - 10 * min, now).uptime)
    }

    @Test
    fun theHeatmapFillsTheRightHours() {
        val records = listOf(OutageRecord(startedAt = today + 8 * hour + 30 * min, endedAt = today + 10 * hour))
        val map = Insights.heatmap(records, days = 3, now = now)

        assertEquals(3, map.size)
        val todayRow = map.last()
        assertEquals(today, todayRow.dayStart)
        assertEquals(30, todayRow.minutesDown[8])
        assertEquals(60, todayRow.minutesDown[9])
        assertEquals(0, todayRow.minutesDown[10])
        assertEquals(0, map.first().minutesDown.sum())
    }

    @Test
    fun theFutureIsNeverPaintedAsDown() {
        val records = listOf(OutageRecord(startedAt = now - 30 * min))
        val todayRow = Insights.heatmap(records, days = 1, now = now).single()
        assertEquals(30, todayRow.minutesDown[17])
        assertEquals(0, todayRow.minutesDown[18])
    }

    @Test
    fun selfSufficiencyIsTheShareNotBoughtFromTheGrid() {
        assertEquals(0.75, DailyEnergy(consumedKwh = 20.0, boughtKwh = 5.0).selfSufficiency!!, 0.0001)
        assertNull(DailyEnergy(consumedKwh = 20.0).selfSufficiency)
        assertNull(DailyEnergy(consumedKwh = 0.0, boughtKwh = 0.0).selfSufficiency)
    }

    @Test
    fun chargeEtaIsSensibleAndAbsentWhenFull() {
        val minutes = RuntimeEstimator.minutesToFull(soc = 50.0, capacityWh = 10_000.0, chargeWatts = 2_000.0)!!
        assertEquals(168, minutes)
        assertNull(RuntimeEstimator.minutesToFull(soc = 100.0, capacityWh = 10_000.0, chargeWatts = 2_000.0))
        assertNull(RuntimeEstimator.minutesToFull(soc = 50.0, capacityWh = 10_000.0, chargeWatts = 10.0))
    }

    @Test
    fun theDailyCountersAreReadFromADeyeDump() {
        val metrics = listOf(
            io.github.hafeee44lab.solargrid.data.api.SolarMetric("Etdy_ge1", "Daily Production", "18.40", "kWh"),
            io.github.hafeee44lab.solargrid.data.api.SolarMetric("Etdy_use1", "Daily Consumption", "21.10", "kWh"),
            io.github.hafeee44lab.solargrid.data.api.SolarMetric("Etdy_pu1", "Daily Energy Purchased", "4.20", "kWh"),
            io.github.hafeee44lab.solargrid.data.api.SolarMetric("Etdy_gn1", "Daily Grid Feed-in", "0.80", "kWh")
        )
        val today = GridAnalyzer.read(metrics).today
        assertEquals(18.4, today.producedKwh!!, 0.001)
        assertEquals(21.1, today.consumedKwh!!, 0.001)
        assertEquals(4.2, today.boughtKwh!!, 0.001)
        assertEquals(0.8, today.soldKwh!!, 0.001)
    }
}
