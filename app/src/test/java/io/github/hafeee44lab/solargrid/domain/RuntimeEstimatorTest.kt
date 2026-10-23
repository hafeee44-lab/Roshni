package io.github.hafeee44lab.solargrid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEstimatorTest {

    // Hafi's pack: 110 Ah at 52.56 V.
    private val nameplate = RuntimeEstimator.nameplateWh(110.0, 52.56)!!

    @Test
    fun nameplateMatchesTheDumpedRatings() {
        assertEquals(5781.6, nameplate, 1.0)
    }

    @Test
    fun implausibleRatingsAreRejectedRatherThanUsed() {
        assertNull(RuntimeEstimator.nameplateWh(0.5, 3.0))
        assertNull(RuntimeEstimator.nameplateWh(null, 52.0))
        assertNull(RuntimeEstimator.nameplateWh(110.0, null))
    }

    @Test
    fun theRealOutageReadingGivesASensibleRuntime() {
        // 75% charge, 1129 W load — the numbers from the 22:46 dump.
        val estimate = RuntimeEstimator.estimate(75.0, nameplate, 1129.0, calibrated = false)!!

        assertTrue(estimate.minutesRemaining in 160..180)
        assertEquals("2h 48m", RuntimeEstimator.describe(estimate.minutesRemaining))
    }

    @Test
    fun theReserveIsHeldBackNotSpent() {
        val full = RuntimeEstimator.estimate(100.0, 1000.0, 100.0, false)!!
        // 80% of 1000 Wh at 100 W = 8 hours, not 10.
        assertEquals(480, full.minutesRemaining)
    }

    @Test
    fun atOrBelowTheReserveThereIsNothingLeft() {
        assertEquals(0, RuntimeEstimator.estimate(20.0, 5000.0, 500.0, false)!!.minutesRemaining)
        assertEquals(0, RuntimeEstimator.estimate(12.0, 5000.0, 500.0, false)!!.minutesRemaining)
    }

    @Test
    fun aTinyLoadIsNotExtrapolatedIntoAFantasyNumber() {
        assertNull(RuntimeEstimator.estimate(80.0, 5000.0, 5.0, false))
    }

    @Test
    fun missingInputsGiveNoEstimateRatherThanAGuess() {
        assertNull(RuntimeEstimator.estimate(null, 5000.0, 500.0, false))
        assertNull(RuntimeEstimator.estimate(80.0, null, 500.0, false))
        assertNull(RuntimeEstimator.estimate(80.0, 5000.0, null, false))
    }

    @Test
    fun capacityIsLearnedFromWhatTheBatteryActuallyDelivered() {
        // Fell 40 points and gave up 2.0 kWh, so the pack is really worth 5 kWh.
        val learned = RuntimeEstimator.learnCapacity(
            anchorSoc = 90.0,
            anchorDischargeKwh = 3.0,
            currentSoc = 50.0,
            currentDischargeKwh = 5.0
        )!!

        assertEquals(5000.0, learned, 1.0)
    }

    @Test
    fun aShortRunIsNotEnoughToCalibrate() {
        assertNull(
            RuntimeEstimator.learnCapacity(
                anchorSoc = 90.0,
                anchorDischargeKwh = 3.0,
                currentSoc = 85.0,
                currentDischargeKwh = 3.3
            )
        )
    }

    @Test
    fun aDailyCounterRollingOverDoesNotCorruptTheEstimate() {
        // Etdy_dcg1 resets at midnight, so delivered energy can look negative.
        assertNull(
            RuntimeEstimator.learnCapacity(
                anchorSoc = 90.0,
                anchorDischargeKwh = 8.0,
                currentSoc = 60.0,
                currentDischargeKwh = 0.2
            )
        )
    }

    @Test
    fun absurdLearnedCapacityIsDiscarded() {
        assertNull(
            RuntimeEstimator.learnCapacity(
                anchorSoc = 90.0,
                anchorDischargeKwh = 0.0,
                currentSoc = 80.0,
                currentDischargeKwh = 40.0
            )
        )
    }

    @Test
    fun learningMovesTowardTheObservationWithoutJumping() {
        val blended = RuntimeEstimator.blend(6000.0, 4500.0)
        assertTrue(blended < 6000.0 && blended > 4500.0)
        assertEquals(4500.0, RuntimeEstimator.blend(null, 4500.0), 0.001)
    }

    @Test
    fun loadIsSmoothedSoAKettleDoesNotHalveTheEstimate() {
        val smoothed = RuntimeEstimator.smoothLoad(1000.0, 3000.0)!!
        assertTrue(smoothed > 1000.0 && smoothed < 1700.0)
        assertEquals(800.0, RuntimeEstimator.smoothLoad(null, 800.0)!!, 0.001)
        assertEquals(800.0, RuntimeEstimator.smoothLoad(800.0, null)!!, 0.001)
    }

    @Test
    fun durationsReadTheWayAPersonWouldSayThem() {
        assertEquals("2h 48m", RuntimeEstimator.describe(168))
        assertEquals("45m", RuntimeEstimator.describe(45))
        assertEquals("3h", RuntimeEstimator.describe(180))
        assertEquals("empty", RuntimeEstimator.describe(0))
    }

    @Test
    fun usualLowLoadIsTheQuietEndOfTheWeek() {
        // 200 readings of a house idling at 400 W, plus 100 with the AC on at 2 kW.
        val loads = List(200) { 400.0 + it % 5 } + List(100) { 2000.0 }
        assertEquals(400.0, RuntimeEstimator.typicalLowLoad(loads)!!, 5.0)
    }

    @Test
    fun usualLowLoadNeedsAboutADayOfHistory() {
        assertNull(RuntimeEstimator.typicalLowLoad(List(50) { 400.0 }))
    }

    @Test
    fun stretchShowsHowLongTheBatteryLastsAtTheLowLoad() {
        val s = RuntimeEstimator.stretch(soc = 80.0, capacityWh = nameplate, currentLoadW = 1600.0, lowLoadW = 400.0, reservePercent = 20.0)!!
        // 60% of 5.78 kWh at 400 W is about 8h 40m.
        assertEquals(520.0, s.minutes.toDouble(), 5.0)
    }

    @Test
    fun noStretchWhenTheHouseIsAlreadyQuiet() {
        assertNull(RuntimeEstimator.stretch(80.0, nameplate, currentLoadW = 450.0, lowLoadW = 400.0, reservePercent = 20.0))
    }
}
