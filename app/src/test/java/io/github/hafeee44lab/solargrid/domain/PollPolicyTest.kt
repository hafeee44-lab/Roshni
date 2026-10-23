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
        sinceUpload: Long = -1L
    ) = PollPolicy.nextDelayMs(gridDown, sinceChange, errors, learned, sinceUpload)

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
}
