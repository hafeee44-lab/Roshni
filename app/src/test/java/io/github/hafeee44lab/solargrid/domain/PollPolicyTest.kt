package io.github.hafeee44lab.solargrid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollPolicyTest {

    private fun next(
        gridDown: Boolean? = false,
        sinceChange: Long = Long.MAX_VALUE,
        errors: Int = 0,
        learned: Long? = null,
        sinceUpload: Long = -1L,
        quota: Boolean = false
    ) = PollPolicy.nextDelayMs(gridDown, sinceChange, errors, learned, sinceUpload, quota)

    @Test
    fun steadyStateBacksOffToTheInverterCadence() {
        assertEquals(PollPolicy.DEFAULT_UPLOAD_MS, next())
    }

    @Test
    fun outagePollsFast() {
        assertEquals(PollPolicy.ACTIVE_MS, next(gridDown = true))
    }

    @Test
    fun recentStateChangeKeepsPollingFast() {
        assertEquals(PollPolicy.ACTIVE_MS, next(sinceChange = 60_000L))
    }

    @Test
    fun oldStateChangeDoesNotKeepPollingFast() {
        assertEquals(PollPolicy.DEFAULT_UPLOAD_MS, next(sinceChange = 20 * 60_000L))
    }

    @Test
    fun errorsBackOffExponentiallyAndCap() {
        assertEquals(PollPolicy.ERROR_BASE_MS, next(errors = 1))
        assertEquals(PollPolicy.ERROR_BASE_MS * 2, next(errors = 2))
        assertEquals(PollPolicy.ERROR_BASE_MS * 4, next(errors = 3))
        assertEquals(PollPolicy.ERROR_MAX_MS, next(errors = 12))
    }

    @Test
    fun errorBackoffOutranksAnOutage() {
        assertEquals(PollPolicy.ERROR_BASE_MS, next(gridDown = true, errors = 1))
    }

    @Test
    fun aimsJustAfterTheNextExpectedUpload() {
        assertEquals(PollPolicy.CADENCE_MIN_MS, next(learned = 300_000L, sinceUpload = 200_000L))
    }

    @Test
    fun waitsWhenTheUploadIsStillFarOff() {
        assertEquals(555_000L, next(learned = 600_000L, sinceUpload = 60_000L))
    }

    @Test
    fun neverPollsFasterThanTheFloor() {
        assertTrue(next(learned = 300_000L, sinceUpload = 299_000L) >= PollPolicy.CADENCE_MIN_MS)
    }

    @Test
    fun neverWaitsLongerThanOneCadence() {
        assertTrue(next(learned = 300_000L, sinceUpload = 0L) <= 300_000L)
    }

    @Test
    fun absurdCadenceIsClamped() {
        assertEquals(PollPolicy.CADENCE_MAX_MS, next(learned = 60 * 60_000L))
        assertEquals(PollPolicy.CADENCE_MIN_MS, next(learned = 5_000L))
    }

    @Test
    fun learnsFromAPlausibleGap() {
        assertEquals(300_000L, PollPolicy.learnUploadInterval(null, 300_000L))
    }

    @Test
    fun learningSmoothsRatherThanJumping() {
        val learned = PollPolicy.learnUploadInterval(300_000L, 600_000L)!!
        assertTrue(learned > 300_000L && learned < 600_000L)
    }

    @Test
    fun implausibleGapsAreIgnored() {
        assertEquals(300_000L, PollPolicy.learnUploadInterval(300_000L, 3_000L))
        assertEquals(300_000L, PollPolicy.learnUploadInterval(300_000L, 5 * 60 * 60_000L))
    }

    @Test
    fun outagePollsJustAfterTheNextUploadInsteadOfEvery90Seconds() {
        assertEquals(215_000L, next(gridDown = true, learned = 300_000L, sinceUpload = 100_000L))
    }

    @Test
    fun outageRetriesQuicklyWhenAnUploadIsLate() {
        assertEquals(PollPolicy.ACTIVE_MS, next(gridDown = true, learned = 300_000L, sinceUpload = 320_000L))
    }

    @Test
    fun outageWithASilentLoggerWaitsAFullCadence() {
        assertEquals(300_000L, next(gridDown = true, learned = 300_000L, sinceUpload = 20 * 60_000L))
    }

    @Test
    fun outageNeverPollsFasterThanItsFloor() {
        assertEquals(PollPolicy.ACTIVE_MIN_MS, next(gridDown = true, learned = 300_000L, sinceUpload = 299_000L))
    }

    @Test
    fun anExhaustedAllowancePausesChecksForAnHour() {
        assertEquals(PollPolicy.QUOTA_BACKOFF_MS, next(gridDown = true, quota = true))
    }

    @Test
    fun aNormalDayStaysWellUnderSolarmansLimits() {
        // Logger uploads every 5 minutes, mains on all day: about one call per upload.
        val perDay = (24 * 60 * 60_000L) / next(learned = 300_000L, sinceUpload = 0L)
        assertTrue("expected ≤ 300 calls a day, got $perDay", perDay <= 300)
    }

    @Test
    fun localPollsEveryMinuteWhenAllIsQuiet() {
        assertEquals(PollPolicy.LOCAL_IDLE_MS, PollPolicy.nextLocalDelayMs(gridDown = false, msSinceStateChange = Long.MAX_VALUE, errorStreak = 0))
    }

    @Test
    fun localPollsEveryThirtySecondsAroundACut() {
        assertEquals(PollPolicy.LOCAL_MIN_MS, PollPolicy.nextLocalDelayMs(gridDown = true, msSinceStateChange = Long.MAX_VALUE, errorStreak = 0))
        assertEquals(PollPolicy.LOCAL_MIN_MS, PollPolicy.nextLocalDelayMs(gridDown = false, msSinceStateChange = 60_000L, errorStreak = 0))
    }

    @Test
    fun localNeverPollsFasterThanThirtySecondsAndBacksOffWhenUnreachable() {
        assertEquals(30_000L, PollPolicy.nextLocalDelayMs(null, Long.MAX_VALUE, errorStreak = 1))
        assertEquals(60_000L, PollPolicy.nextLocalDelayMs(null, Long.MAX_VALUE, errorStreak = 2))
        assertEquals(PollPolicy.LOCAL_ERROR_MAX_MS, PollPolicy.nextLocalDelayMs(true, 0L, errorStreak = 20))
        for (streak in 0..20) {
            assertTrue(PollPolicy.nextLocalDelayMs(true, 0L, streak) >= PollPolicy.LOCAL_MIN_MS)
        }
    }

    @Test
    fun aBrandFloorHoldsEvenDuringACutAndAfterErrors() {
        val floor = 5 * 60_000L
        // During a cut with no learned cadence Solarman would be asked every 90 s.
        assertEquals(floor, PollPolicy.nextDelayMs(true, 0L, 0, null, -1L, floorMs = floor))
        assertEquals(floor, PollPolicy.nextDelayMs(null, Long.MAX_VALUE, errorStreak = 1, learnedUploadMs = null, msSinceInverterUpload = -1L, floorMs = floor))
        // Longer waits are left alone.
        assertEquals(PollPolicy.QUOTA_BACKOFF_MS, PollPolicy.nextDelayMs(true, 0L, 0, null, -1L, quotaExhausted = true, floorMs = floor))
        assertEquals(PollPolicy.ERROR_MAX_MS, PollPolicy.nextDelayMs(null, Long.MAX_VALUE, 10, null, -1L, floorMs = floor))
    }
}
