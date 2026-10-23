package io.github.hafeee44lab.solargrid.domain

import java.util.Calendar

data class OutageRecord(
    val id: Long = 0L,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startSoc: Double? = null,
    val endSoc: Double? = null
) {
    val ongoing: Boolean get() = endedAt == null

    fun durationMs(now: Long = System.currentTimeMillis()): Long =
        (endedAt ?: now) - startedAt

    val batteryUsed: Double?
        get() {
            val from = startSoc ?: return null
            val to = endSoc ?: return null
            return (from - to).takeIf { it > 0 }
        }
}

data class OutageStats(
    val todayCount: Int,
    val todayMs: Long,
    val weekCount: Int,
    val weekMs: Long,
    val longestMs: Long,
    val typicalStartHour: Int?
)

object OutageLog {

    const val MAX_RECORDS = 300
    private const val TYPICAL_MIN_SAMPLES = 4

    fun stats(records: List<OutageRecord>, now: Long): OutageStats {
        val startOfToday = startOfDay(now)
        val startOfWeek = startOfToday - 6 * DAY_MS

        var todayCount = 0
        var todayMs = 0L
        var weekCount = 0
        var weekMs = 0L
        var longest = 0L

        val hours = mutableListOf<Int>()

        for (record in records) {
            val duration = record.durationMs(now)
            if (duration > longest) longest = duration

            if (record.startedAt >= startOfToday) {
                todayCount++
                todayMs += duration
            }

            if (record.startedAt >= startOfWeek) {
                weekCount++
                weekMs += duration
                hours.add(hourOf(record.startedAt))
            }
        }

        return OutageStats(
            todayCount = todayCount,
            todayMs = todayMs,
            weekCount = weekCount,
            weekMs = weekMs,
            longestMs = longest,
            typicalStartHour = typicalHour(hours)
        )
    }

    /**
     * The hour outages most often begin, but only once there is enough history for the
     * answer to mean anything. Hours are compared on a clock face so 23:00 and 00:00
     * count as neighbours rather than opposites.
     */
    fun typicalHour(hours: List<Int>): Int? {
        if (hours.size < TYPICAL_MIN_SAMPLES) return null

        var best = 0
        var bestScore = -1

        for (candidate in 0..23) {
            val score = hours.count { withinOneHour(it, candidate) }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        return if (bestScore * 2 >= hours.size) best else null
    }

    private fun withinOneHour(a: Int, b: Int): Boolean {
        val raw = kotlin.math.abs(a - b)
        return minOf(raw, 24 - raw) <= 1
    }

    fun describeDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000L).toInt()
        if (totalMinutes < 1) return "under a minute"

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours == 0 -> "${minutes}m"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    fun begin(records: List<OutageRecord>, at: Long, soc: Double?): List<OutageRecord> {
        if (records.firstOrNull()?.ongoing == true) return records
        return listOf(OutageRecord(startedAt = at, startSoc = soc)) + records
    }

    fun end(records: List<OutageRecord>, at: Long, soc: Double?): List<OutageRecord> {
        val open = records.firstOrNull() ?: return records
        if (!open.ongoing) return records
        return listOf(open.copy(endedAt = at, endSoc = soc)) + records.drop(1)
    }

    // ---------------- time helpers ----------------

    const val DAY_MS = 24 * 60 * 60 * 1000L

    private fun calendar(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }

    fun startOfDay(millis: Long): Long = calendar(millis).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun hourOf(millis: Long): Int = calendar(millis).get(Calendar.HOUR_OF_DAY)
}
