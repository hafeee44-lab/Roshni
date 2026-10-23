package io.github.hafeee44lab.solargrid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutageLogTest {

    private val now = OutageLog.startOfDay(System.currentTimeMillis()) + 20 * 60 * 60_000L
    private val hour = 60 * 60_000L

    private fun record(startOffsetMs: Long, durationMs: Long?, startSoc: Double? = 90.0, endSoc: Double? = 70.0) =
        OutageRecord(
            startedAt = now - startOffsetMs,
            endedAt = durationMs?.let { now - startOffsetMs + it },
            startSoc = startSoc,
            endSoc = endSoc
        )

    @Test
    fun anOngoingOutageIsMeasuredUpToNow() {
        val open = OutageRecord(startedAt = now - 90 * 60_000L)
        assertTrue(open.ongoing)
        assertEquals(90L, open.durationMs(now) / 60_000L)
    }

    @Test
    fun batteryUsedNeedsBothEnds() {
        assertEquals(20.0, record(2 * hour, hour).batteryUsed!!, 0.001)
        assertNull(OutageRecord(startedAt = now, startSoc = 90.0).batteryUsed)
    }

    @Test
    fun aBatteryThatGainedChargeIsNotReportedAsUsed() {
        assertNull(record(2 * hour, hour, startSoc = 40.0, endSoc = 65.0).batteryUsed)
    }

    @Test
    fun todayAndTheWeekAreCountedSeparately() {
        val stats = OutageLog.stats(
            listOf(
                record(2 * hour, hour),
                record(6 * hour, 30 * 60_000L),
                record(3 * OutageLog.DAY_MS, 2 * hour),
                record(20 * OutageLog.DAY_MS, hour)
            ),
            now
        )

        assertEquals(2, stats.todayCount)
        assertEquals(90L, stats.todayMs / 60_000L)
        assertEquals(3, stats.weekCount)
        assertEquals(210L, stats.weekMs / 60_000L)
    }

    @Test
    fun theLongestOutageIsTrackedAcrossAllHistory() {
        val stats = OutageLog.stats(
            listOf(record(2 * hour, hour), record(40 * OutageLog.DAY_MS, 9 * hour)),
            now
        )
        assertEquals(9L, stats.longestMs / hour)
    }

    @Test
    fun aTypicalHourEmergesOnlyOnceThereIsEnoughHistory() {
        assertNull(OutageLog.typicalHour(listOf(22, 22, 23)))
        assertEquals(22, OutageLog.typicalHour(listOf(22, 22, 23, 21, 22)))
    }

    @Test
    fun scatteredOutagesHaveNoTypicalHour() {
        assertNull(OutageLog.typicalHour(listOf(2, 8, 14, 19, 23, 11)))
    }

    @Test
    fun midnightWrapAroundCountsAsOneCluster() {
        assertEquals(0, OutageLog.typicalHour(listOf(23, 0, 0, 1, 23)))
    }

    @Test
    fun beginningAnOutageTwiceDoesNotOpenTwoRecords() {
        val first = OutageLog.begin(emptyList(), now, 90.0)
        val second = OutageLog.begin(first, now + 60_000L, 88.0)

        assertEquals(1, second.size)
        assertEquals(now, second.first().startedAt)
    }

    @Test
    fun endingClosesTheOpenRecord() {
        val opened = OutageLog.begin(emptyList(), now - hour, 90.0)
        val closed = OutageLog.end(opened, now, 68.0)

        assertFalse(closed.first().ongoing)
        assertEquals(22.0, closed.first().batteryUsed!!, 0.001)
        assertEquals(60L, closed.first().durationMs(now) / 60_000L)
    }

    @Test
    fun endingWithNothingOpenChangesNothing() {
        val closed = OutageLog.end(listOf(record(3 * hour, hour)), now, 50.0)
        assertEquals(1, closed.size)
        assertEquals(hour, closed.first().durationMs(now))
    }

    @Test
    fun durationsReadTheWayAPersonWouldSayThem() {
        assertEquals("2h 14m", OutageLog.describeDuration(134 * 60_000L))
        assertEquals("45m", OutageLog.describeDuration(45 * 60_000L))
        assertEquals("3h", OutageLog.describeDuration(3 * hour))
        assertEquals("under a minute", OutageLog.describeDuration(20_000L))
    }

    @Test
    fun emptyHistoryProducesZeroesNotCrashes() {
        val stats = OutageLog.stats(emptyList(), now)
        assertEquals(0, stats.todayCount)
        assertEquals(0L, stats.weekMs)
        assertNull(stats.typicalStartHour)
    }
}
