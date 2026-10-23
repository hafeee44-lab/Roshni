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
    const val ACTIVE_MIN_MS = 30_000L
    const val SILENT_UPLOADS = 3
    const val QUOTA_BACKOFF_MS = 60 * 60_000L

    /**
     * When to ask Solarman next. Every call counts against the App ID's allowance, so this never
     * asks faster than the inverter can upload something new.
     */
    fun nextDelayMs(
        gridDown: Boolean?,
        msSinceStateChange: Long,
        errorStreak: Int,
        learnedUploadMs: Long?,
        msSinceInverterUpload: Long,
        quotaExhausted: Boolean = false
    ): Long {
        // Out of allowance: more calls only fail and can delay Solarman lifting the block.
        if (quotaExhausted) return QUOTA_BACKOFF_MS

        if (errorStreak > 0) {
            val shift = (errorStreak - 1).coerceIn(0, 6)
            return (ERROR_BASE_MS shl shift).coerceAtMost(ERROR_MAX_MS)
        }

        val cadence = (learnedUploadMs ?: DEFAULT_UPLOAD_MS)
            .coerceIn(CADENCE_MIN_MS, CADENCE_MAX_MS)

        if (gridDown == true || msSinceStateChange in 0 until WATCH_WINDOW_MS) {
            // Without a learned cadence there is nothing to aim at, so check often.
            if (learnedUploadMs == null || msSinceInverterUpload < 0) return ACTIVE_MS
            // The logger has gone quiet, often because the router has no backup power.
            // Nothing new can arrive faster than it uploads, so don't burn calls waiting.
            if (msSinceInverterUpload > SILENT_UPLOADS * cadence) return cadence
            // Otherwise land just after the next upload, and retry quickly if it is late.
            val untilNextUpload = cadence - msSinceInverterUpload + UPLOAD_LEAD_MS
            return if (untilNextUpload > 0) untilNextUpload.coerceIn(ACTIVE_MIN_MS, cadence) else ACTIVE_MS
        }

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
