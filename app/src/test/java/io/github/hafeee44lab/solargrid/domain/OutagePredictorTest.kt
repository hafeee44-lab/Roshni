package io.github.hafeee44lab.solargrid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class OutagePredictorTest {

    private val min = 60_000L
    private val day = OutageLog.DAY_MS

    /** Noon on a fixed day, local time, so start-of-day arithmetic is stable. */
    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 20, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val today = OutageLog.startOfDay(now)

    private fun at(daysAgo: Int, hour: Int, minute: Int = 0, lengthMin: Long = 60): OutageRecord {
        val start = OutageLog.startOfDay(today - daysAgo * day + 3_600_000L) + (hour * 60 + minute) * min
        return OutageRecord(startedAt = start, endedAt = start + lengthMin * min)
    }

    @Test
    fun aDailySlotIsFound() {
        val records = (1..10).map { at(it, 14, if (it % 2 == 0) 0 else 20, 90) }
        val slots = OutagePredictor.schedule(records, now, observedSince = today - 10 * day)

        val slot = slots.single()
        assertTrue("slot near 14:10 but was ${slot.minuteOfDay}", slot.minuteOfDay in 14 * 60..14 * 60 + 20)
        assertEquals(10, slot.daysSeen)
        assertEquals(90 * min, slot.typicalDurationMs)
    }

    @Test
    fun tooLittleHistoryPredictsNothing() {
        val records = (1..3).map { at(it, 8) }
        assertTrue(OutagePredictor.schedule(records, now, observedSince = today - 3 * day).isEmpty())
    }

    @Test
    fun scatteredOutagesAreNotAPattern() {
        val records = listOf(at(1, 2), at(2, 7), at(3, 11), at(4, 15), at(5, 19), at(6, 22), at(7, 4))
        assertTrue(OutagePredictor.schedule(records, now, observedSince = today - 8 * day).isEmpty())
    }

    @Test
    fun aSlotHitOnFewerThanHalfTheDaysIsIgnored() {
        val records = (1..4).map { at(it * 3, 20) }
        assertTrue(OutagePredictor.schedule(records, now, observedSince = today - 14 * day).isEmpty())
    }

    @Test
    fun severalSlotsAreFoundAndOrdered() {
        val records = (1..10).flatMap { listOf(at(it, 20), at(it, 8)) }
        val slots = OutagePredictor.schedule(records, now, observedSince = today - 10 * day)
        assertEquals(listOf(8 * 60, 20 * 60), slots.map { it.minuteOfDay })
    }

    @Test
    fun aSlotNearMidnightClustersAcrossIt() {
        val records = (1..8).map { at(it, if (it % 2 == 0) 23 else 0, if (it % 2 == 0) 50 else 5) }
        val slot = OutagePredictor.schedule(records, now, observedSince = today - 8 * day).single()
        assertTrue(slot.minuteOfDay >= 23 * 60 + 40 || slot.minuteOfDay <= 20)
    }

    @Test
    fun theNextSlotLaterTodayIsPredicted() {
        val records = (1..10).map { at(it, 14) }
        val prediction = OutagePredictor.next(records, now, observedSince = today - 10 * day)
        assertNotNull(prediction)
        assertEquals(today + 14 * 60 * min, prediction!!.startsAt)
    }

    @Test
    fun aSlotThatAlreadyHappenedTodayMovesToTomorrow() {
        val records = (1..10).map { at(it, 9) } + at(0, 9, 10)
        val prediction = OutagePredictor.next(records, now, observedSince = today - 10 * day)!!
        assertTrue(prediction.startsAt > today + day)
    }

    @Test
    fun aSlotEarlierTodayThatDidNotHappenStillMovesToTomorrow() {
        val records = (1..10).map { at(it, 6) }
        val prediction = OutagePredictor.next(records, now, observedSince = today - 10 * day)!!
        assertTrue(prediction.startsAt > now)
        assertTrue(prediction.startsAt > today + day)
    }

    @Test
    fun noPatternNoPrediction() {
        assertNull(OutagePredictor.next(emptyList(), now))
    }
}
