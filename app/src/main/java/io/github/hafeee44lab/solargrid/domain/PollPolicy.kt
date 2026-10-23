package io.github.hafeee44lab.solargrid.domain

object PollPolicy {

    const val ACTIVE_MS = 90_000L
    const val CADENCE_MIN_MS = 2 * 60_000L
    const val CADENCE_MAX_MS = 10 * 60_000L
    const val DEFAULT_UPLOAD_MS = 5 * 60_000L
    const val OFFLINE_FALLBACK_MS = 30 * 60_000L
    const val ERROR_BASE_MS = 2 * 60_000L
    const val ERROR_MAX_MS = 30 * 60_000L
    const val WATCH_WINDOW_MS = 10 * 60_000L
    const val UPLOAD_LEAD_MS = 15_000L

    fun nextDelayMs(
        gridDown: Boolean?,
        msSinceStateChange: Long,
        errorStreak: Int,
        learnedUploadMs: Long?,
        msSinceInverterUpload: Long
    ): Long {
        if (errorStreak > 0) {
            val shift = (errorStreak - 1).coerceIn(0, 6)
            return (ERROR_BASE_MS shl shift).coerceAtMost(ERROR_MAX_MS)
        }

        if (gridDown == true) return ACTIVE_MS
        if (msSinceStateChange in 0 until WATCH_WINDOW_MS) return ACTIVE_MS

        val cadence = (learnedUploadMs ?: DEFAULT_UPLOAD_MS)
            .coerceIn(CADENCE_MIN_MS, CADENCE_MAX_MS)

        if (msSinceInverterUpload < 0) return cadence

        val untilNextUpload = cadence - msSinceInverterUpload + UPLOAD_LEAD_MS
        return untilNextUpload.coerceIn(CADENCE_MIN_MS, cadence)
    }

    fun learnUploadInterval(previousLearnedMs: Long?, observedGapMs: Long): Long? {
        if (observedGapMs !in CADENCE_MIN_MS..CADENCE_MAX_MS) return previousLearnedMs
        val previous = previousLearnedMs ?: return observedGapMs
        return (previous * 2 + observedGapMs) / 3
    }
}
