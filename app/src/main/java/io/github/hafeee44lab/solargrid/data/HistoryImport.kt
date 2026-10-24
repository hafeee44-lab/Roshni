package io.github.hafeee44lab.solargrid.data

import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.domain.Event
import io.github.hafeee44lab.solargrid.domain.EventKind
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import io.github.hafeee44lab.solargrid.domain.HistoryReplay
import io.github.hafeee44lab.solargrid.domain.OutageLog
import io.github.hafeee44lab.solargrid.domain.Reading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fills History from Solarman's own records the first time a real account is watched, so the
 * outage map and forecasts don't start from nothing. One API call per day imported, spaced far
 * below Solarman's 50-a-minute limit, and done once unless the user asks again.
 */
class HistoryImport(private val graph: AppGraph) {

    sealed interface Outcome {
        data class Imported(val days: Int, val cuts: Int) : Outcome
        data class Failed(val message: String) : Outcome
        data object NothingToDo : Outcome
        data object Skipped : Outcome
    }

    suspend fun runIfDue(force: Boolean = false): Outcome {
        val settings = graph.settings.load()
        if (settings.demoMode || settings.source != DataSource.CLOUD) return Outcome.Skipped
        val account = graph.accounts.load() ?: return Outcome.Skipped
        val state = graph.state(Profile.LIVE)
        val prefs = state.prefs
        val now = System.currentTimeMillis()

        if (!force) {
            if (prefs.getLong(DONE_AT, 0L) > 0L) return Outcome.Skipped
            if (now - prefs.getLong(FAILED_AT, 0L) < RETRY_AFTER_MS) return Outcome.Skipped
            if (state.quotaHitAt > 0L) return Outcome.Skipped
        }
        if (!running.compareAndSet(false, true)) return Outcome.Skipped

        try {
            val db = graph.history(Profile.LIVE)
            val cutoff = db.observedSince() ?: now
            val days = daysBetween(OutageLog.startOfDay(now) - DAYS * OutageLog.DAY_MS, cutoff)
            if (days.isEmpty()) {
                prefs.edit().putLong(DONE_AT, now).apply()
                return Outcome.NothingToDo
            }

            val source = SolarmanSource(account, state)
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val frames = mutableListOf<Pair<Long, Reading>>()
            var fetchedDays = 0

            for ((index, day) in days.withIndex()) {
                _progress.value = index to days.size
                when (val result = source.history(format.format(Date(day)))) {
                    is HistoryFetch.Ok -> {
                        frames += result.frames.map { (at, metrics) -> at to GridAnalyzer.read(metrics) }
                        fetchedDays++
                    }
                    is HistoryFetch.Failed -> {
                        if (result.quotaExhausted) state.quotaHitAt = now
                        // Keep what arrived; a partial fortnight still beats an empty one.
                        if (fetchedDays == 0) return fail(result.message, now)
                        break
                    }
                }
                if (index < days.lastIndex) delay(SPACING_MS)
            }

            val replay = HistoryReplay.replay(frames, cutoff)
            db.addSamples(replay.points.map { p ->
                Sample(
                    at = p.at,
                    soc = p.reading.soc,
                    solarW = p.reading.solarPowerW,
                    loadW = p.reading.loadPowerW,
                    gridW = p.reading.gridPowerW,
                    batteryW = p.reading.batteryPowerW,
                    gridDown = p.gridDown
                )
            })
            db.addOutages(replay.outages)
            val cutoffDay = OutageLog.startOfDay(cutoff)
            db.addDays(replay.days.filterKeys { it < cutoffDay }.map { (day, energy) -> DayEnergy(day, energy) })
            db.addEvents(listOf(Event(EventKind.INFO, now, graph.app.getString(R.string.import_event, fetchedDays, replay.outages.size))))

            prefs.edit()
                .putLong(DONE_AT, now)
                .putInt(DONE_DAYS, fetchedDays)
                .putInt(DONE_CUTS, replay.outages.size)
                .remove(FAILED_AT)
                .remove(FAILED_MESSAGE)
                .apply()
            return Outcome.Imported(fetchedDays, replay.outages.size)
        } catch (e: Exception) {
            return fail(graph.app.getString(R.string.cant_reach_solarman, e.localizedMessage ?: e.javaClass.simpleName), now)
        } finally {
            _progress.value = null
            running.set(false)
        }
    }

    private fun fail(message: String, now: Long): Outcome {
        graph.state(Profile.LIVE).prefs.edit().putLong(FAILED_AT, now).putString(FAILED_MESSAGE, message).apply()
        return Outcome.Failed(message)
    }

    /** Day starts from [from] through the day containing [until], stepping by calendar day. */
    private fun daysBetween(from: Long, until: Long): List<Long> {
        val last = OutageLog.startOfDay(until)
        return generateSequence(OutageLog.startOfDay(from)) { OutageLog.startOfDay(it + OutageLog.DAY_MS + 2 * 3_600_000L) }
            .takeWhile { it <= last }
            .toList()
    }

    companion object {
        /** A fortnight: what the outage map shows and what the forecasts look back over. */
        const val DAYS = 14
        private const val SPACING_MS = 1_500L
        private const val RETRY_AFTER_MS = 6 * 60 * 60_000L

        const val DONE_AT = "history_import_done_at"
        const val DONE_DAYS = "history_import_days"
        const val DONE_CUTS = "history_import_cuts"
        const val FAILED_AT = "history_import_failed_at"
        const val FAILED_MESSAGE = "history_import_failed_message"

        private val running = AtomicBoolean(false)
        private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)

        /** (days done, days total) while an import runs, otherwise null. */
        val progress: StateFlow<Pair<Int, Int>?> = _progress
    }
}
