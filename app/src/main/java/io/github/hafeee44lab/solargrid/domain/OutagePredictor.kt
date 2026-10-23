package io.github.hafeee44lab.solargrid.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A time of day when the mains keep going off — the load-shedding schedule, learned rather
 * than typed in.
 */
data class OutageSlot(
    val minuteOfDay: Int,
    val daysSeen: Int,
    val daysObserved: Int,
    val typicalDurationMs: Long?
) {
    val share: Double get() = if (daysObserved == 0) 0.0 else daysSeen.toDouble() / daysObserved
}

data class Prediction(val slot: OutageSlot, val startsAt: Long)

/**
 * Finds the times of day outages cluster around. It only speaks once the pattern is real:
 * at least [MIN_DAYS_OBSERVED] whole days of history, and a slot has to have been hit on
 * [MIN_DAYS_SEEN] separate days and on at least half the days watched.
 */
object OutagePredictor {

    const val LOOKBACK_DAYS = 14
    const val MIN_DAYS_OBSERVED = 5
    const val MIN_DAYS_SEEN = 4
    const val MIN_SHARE = 0.5
    const val WINDOW_MIN = 30
    private const val STEP_MIN = 15
    private const val MINUTES_PER_DAY = 24 * 60
    private const val MINUTE_MS = 60_000L

    private data class Start(val day: Int, val minute: Int, val durationMs: Long?)

    fun schedule(
        records: List<OutageRecord>,
        now: Long,
        observedSince: Long = records.minOfOrNull { it.startedAt } ?: now
    ): List<OutageSlot> {
        val today = OutageLog.startOfDay(now)
        val firstDay = maxOf(
            OutageLog.startOfDay(observedSince),
            today - LOOKBACK_DAYS * OutageLog.DAY_MS
        )

        // Only whole days count. Today has not finished, so a slot that is still to come
        // would otherwise look like a miss.
        val daysObserved = dayIndex(firstDay, today)
        if (daysObserved < MIN_DAYS_OBSERVED) return emptyList()

        val starts = records
            .filter { it.startedAt in firstDay until today }
            .map {
                val dayStart = OutageLog.startOfDay(it.startedAt)
                Start(
                    day = dayIndex(firstDay, dayStart),
                    minute = ((it.startedAt - dayStart) / MINUTE_MS).toInt(),
                    durationMs = it.endedAt?.let { end -> end - it.startedAt }
                )
            }
            .toMutableList()

        val needed = maxOf(MIN_DAYS_SEEN, kotlin.math.ceil(MIN_SHARE * daysObserved).toInt())
        val slots = mutableListOf<OutageSlot>()

        while (true) {
            var bestMinute = -1
            var bestDays = 0

            for (candidate in 0 until MINUTES_PER_DAY step STEP_MIN) {
                val days = starts.filter { near(it.minute, candidate) }.map { it.day }.toSet().size
                if (days > bestDays) {
                    bestDays = days
                    bestMinute = candidate
                }
            }

            if (bestMinute < 0 || bestDays < needed) break

            val members = starts.filter { near(it.minute, bestMinute) }
            starts.removeAll(members.toSet())

            slots += OutageSlot(
                minuteOfDay = roundTo(circularMean(members.map { it.minute }), 5),
                daysSeen = bestDays,
                daysObserved = daysObserved,
                typicalDurationMs = median(members.mapNotNull { it.durationMs })
            )
        }

        return slots.sortedBy { it.minuteOfDay }
    }

    /**
     * The next slot still to come. A slot that has already produced an outage today is
     * skipped — it has happened, so the next one is tomorrow's.
     */
    fun next(
        records: List<OutageRecord>,
        now: Long,
        observedSince: Long = records.minOfOrNull { it.startedAt } ?: now
    ): Prediction? {
        val slots = schedule(records, now, observedSince)
        if (slots.isEmpty()) return null

        val today = OutageLog.startOfDay(now)
        val startedToday = records
            .filter { it.startedAt >= today }
            .map { ((it.startedAt - today) / MINUTE_MS).toInt() }

        return slots.mapNotNull { slot ->
            val todayAt = today + slot.minuteOfDay * MINUTE_MS
            val alreadyHappened = startedToday.any { near(it, slot.minuteOfDay) }

            when {
                !alreadyHappened && todayAt > now -> Prediction(slot, todayAt)
                else -> Prediction(slot, OutageLog.startOfDay(today + OutageLog.DAY_MS + 3_600_000L) + slot.minuteOfDay * MINUTE_MS)
            }
        }.minByOrNull { it.startsAt }
    }

    private fun dayIndex(firstDay: Long, dayStart: Long): Int =
        ((dayStart - firstDay + OutageLog.DAY_MS / 2) / OutageLog.DAY_MS).toInt()

    private fun near(a: Int, b: Int): Boolean {
        val raw = abs(a - b)
        return minOf(raw, MINUTES_PER_DAY - raw) <= WINDOW_MIN
    }

    private fun circularMean(minutes: List<Int>): Int {
        var x = 0.0
        var y = 0.0
        minutes.forEach {
            val angle = it.toDouble() / MINUTES_PER_DAY * 2 * Math.PI
            x += cos(angle)
            y += sin(angle)
        }
        var angle = atan2(y, x)
        if (angle < 0) angle += 2 * Math.PI
        return (angle / (2 * Math.PI) * MINUTES_PER_DAY).roundToInt() % MINUTES_PER_DAY
    }

    private fun roundTo(minute: Int, step: Int): Int =
        ((minute + step / 2) / step * step) % MINUTES_PER_DAY

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
