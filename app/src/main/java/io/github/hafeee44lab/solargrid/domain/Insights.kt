package io.github.hafeee44lab.solargrid.domain

/** What the grid did over a window of time, with outages clipped to the window's edges. */
data class RangeStats(
    val count: Int,
    val downMs: Long,
    val longestMs: Long,
    val averageMs: Long,
    val uptime: Double?,
    val windowMs: Long
)

/** One day of the outage heatmap: minutes without mains in each hour, 0..60. */
data class HeatmapDay(val dayStart: Long, val minutesDown: IntArray) {
    override fun equals(other: Any?): Boolean =
        other is HeatmapDay && other.dayStart == dayStart && other.minutesDown.contentEquals(minutesDown)

    override fun hashCode(): Int = 31 * dayStart.hashCode() + minutesDown.contentHashCode()
}

object Insights {

    private const val HOUR_MS = 3_600_000L

    /**
     * Uptime is only claimed for time we were actually watching. A window that started before
     * monitoring did is shortened to [observedSince] rather than counting the unwatched part
     * as "up".
     */
    fun range(
        records: List<OutageRecord>,
        from: Long,
        to: Long,
        observedSince: Long? = null
    ): RangeStats {
        val start = maxOf(from, observedSince ?: from)
        val window = (to - start).coerceAtLeast(0L)

        var count = 0
        var down = 0L
        var longest = 0L

        for (record in records) {
            val end = record.endedAt ?: to
            val clippedStart = maxOf(record.startedAt, start)
            val clippedEnd = minOf(end, to)
            if (clippedEnd <= clippedStart) continue

            count++
            val length = clippedEnd - clippedStart
            down += length
            longest = maxOf(longest, record.durationMs(to))
        }

        return RangeStats(
            count = count,
            downMs = down,
            longestMs = longest,
            averageMs = if (count == 0) 0L else down / count,
            uptime = if (window < HOUR_MS) null else (1.0 - down.toDouble() / window).coerceIn(0.0, 1.0),
            windowMs = window
        )
    }

    /** Newest day last, so it reads left-to-right / top-to-bottom like a calendar. */
    fun heatmap(records: List<OutageRecord>, days: Int, now: Long): List<HeatmapDay> {
        val today = OutageLog.startOfDay(now)

        return (days - 1 downTo 0).map { back ->
            val dayStart = OutageLog.startOfDay(today - back * OutageLog.DAY_MS + HOUR_MS)
            val minutes = IntArray(24)

            for (hour in 0 until 24) {
                val hourStart = dayStart + hour * HOUR_MS
                val hourEnd = hourStart + HOUR_MS
                if (hourStart >= now) break

                var overlap = 0L
                for (record in records) {
                    val end = record.endedAt ?: now
                    val a = maxOf(record.startedAt, hourStart)
                    val b = minOf(end, hourEnd, now)
                    if (b > a) overlap += b - a
                }
                minutes[hour] = (overlap / 60_000L).toInt().coerceIn(0, 60)
            }

            HeatmapDay(dayStart, minutes)
        }
    }
}
