package io.github.hafeee44lab.solargrid.service

import io.github.hafeee44lab.solargrid.data.AppGraph
import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.HistoryDb
import io.github.hafeee44lab.solargrid.data.Sample
import io.github.hafeee44lab.solargrid.data.SolarSnapshot
import io.github.hafeee44lab.solargrid.data.StateStore
import io.github.hafeee44lab.solargrid.domain.AlertPlanner
import io.github.hafeee44lab.solargrid.domain.AlertSettings
import io.github.hafeee44lab.solargrid.domain.DaySummary
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import io.github.hafeee44lab.solargrid.domain.Insights
import io.github.hafeee44lab.solargrid.domain.Observation
import io.github.hafeee44lab.solargrid.domain.OutageLog
import io.github.hafeee44lab.solargrid.domain.OutagePredictor
import io.github.hafeee44lab.solargrid.domain.Plan
import io.github.hafeee44lab.solargrid.domain.PlanContext
import io.github.hafeee44lab.solargrid.domain.PollPolicy
import io.github.hafeee44lab.solargrid.domain.Reading
import io.github.hafeee44lab.solargrid.domain.RuntimeEstimate
import io.github.hafeee44lab.solargrid.domain.RuntimeEstimator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class PollOutcome(
    val plan: Plan,
    val snapshot: SolarSnapshot,
    val failed: Boolean
)

/**
 * One poll, start to finish: fetch, analyse, plan, persist. Delivering the alerts is left to
 * the caller so this stays usable from the service, a manual refresh and tests alike.
 */
class MonitorEngine(private val graph: AppGraph) {

    suspend fun pollOnce(): PollOutcome {
        val settings = graph.settings.load()
        val state = graph.state(settings.profile)
        val db = graph.history(settings.profile)
        val now = System.currentTimeMillis()

        if (settings.demoMode) graph.backfillDemo(now)
        val source = graph.readingSource()
        val result = if (source == null) {
            FetchResult.Failed("Not signed in to Solarman", authProblem = true)
        } else {
            try {
                state.lastPollAt = now
                source.fetch()
            } catch (e: Exception) {
                FetchResult.Failed("Network error: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }

        var snapshot = state.snapshot
        var reading: Reading? = null
        var runtime: RuntimeEstimate? = null
        var collectedAt = 0L

        val observation = when (result) {
            is FetchResult.Ok -> {
                reading = GridAnalyzer.read(result.metrics)
                collectedAt = result.collectedAt
                learnCadence(state, collectedAt)
                runtime = updateRuntimeModel(state, reading, settings.alerts)
                state.errorStreak = 0
                state.quotaHitAt = 0L
                state.dump = reading.dump
                Observation.Upload(reading, collectedAt, result.inverterAlarm)
            }
            FetchResult.LoggerOffline -> {
                state.errorStreak = 0
                state.quotaHitAt = 0L
                Observation.LoggerOffline
            }
            is FetchResult.Failed -> {
                state.errorStreak = state.errorStreak + 1
                if (result.quotaExhausted) state.quotaHitAt = now
                Observation.Unreachable
            }
        }

        val outages = db.outages(since = now - (OutagePredictor.LOOKBACK_DAYS + 1) * OutageLog.DAY_MS)
        val observedSince = db.observedSince()
        val prediction = OutagePredictor.next(outages, now, observedSince ?: now)

        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val today = OutageLog.startOfDay(now)
        val todayStats = Insights.range(outages, today, now)
        val energy = reading?.today ?: snapshot.today

        val cadence = (state.uploadIntervalMs ?: PollPolicy.DEFAULT_UPLOAD_MS)
        val ctx = PlanContext(
            now = now,
            minuteOfDay = minuteOfDay,
            settings = settings.alerts,
            runtimeMinutes = runtime?.minutesRemaining ?: snapshot.runtimeMinutes,
            staleAfterMs = maxOf(3 * cadence, 20 * 60_000L),
            prediction = prediction,
            summary = DaySummary(todayStats.count, todayStats.downMs, energy.producedKwh, energy.selfSufficiency),
            startOfDay = today,
            formatTime = { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
        )

        val plan = AlertPlanner.plan(state.monitor, observation, ctx)
        state.monitor = plan.state

        val soc = reading?.soc ?: snapshot.soc
        plan.outageBegan?.let { db.beginOutage(it, soc) }
        plan.outageEnded?.let { db.endOutage(it, soc) }
        db.addEvents(plan.events)

        snapshot = when {
            reading != null && plan.freshUpload -> buildSnapshot(reading, collectedAt, now, plan, runtime, state, (result as FetchResult.Ok).inverterAlarm)
            reading != null -> snapshot.copy(checkedAt = now, error = null)
            result is FetchResult.Failed -> snapshot.copy(checkedAt = now, error = result.message)
            else -> snapshot.copy(checkedAt = now, error = null)
        }
        state.snapshot = snapshot

        if (reading != null && plan.freshUpload) {
            val at = if (collectedAt > 0L) collectedAt else now
            db.addSample(
                Sample(
                    at = at,
                    soc = reading.soc,
                    solarW = reading.solarPowerW,
                    loadW = reading.loadPowerW,
                    gridW = reading.gridPowerW,
                    batteryW = reading.batteryPowerW,
                    gridDown = plan.state.outage.committedDown
                )
            )
            db.recordDay(OutageLog.startOfDay(at), reading.today)
            pruneOccasionally(state, db, now)
        }

        return PollOutcome(plan, snapshot, failed = result is FetchResult.Failed)
    }

    private fun buildSnapshot(
        reading: Reading,
        collectedAt: Long,
        now: Long,
        plan: Plan,
        runtime: RuntimeEstimate?,
        state: StateStore,
        inverterAlarm: Boolean
    ): SolarSnapshot {
        val capacity = state.learnedCapacityWh
            ?: RuntimeEstimator.nameplateWh(reading.batteryCapacityAh, reading.batteryVoltage)
        val charge = reading.batteryPowerW?.let { -it }

        return SolarSnapshot(
            gridDown = plan.state.outage.committedDown,
            gridPowerW = reading.gridPowerW,
            gridVoltage = reading.gridVoltage,
            gridFrequency = reading.gridFrequency,
            gridPhaseVolts = reading.gridPhaseVolts,
            inverterTempC = reading.inverterTempC,
            batteryTempC = reading.batteryTempC,
            batteryPowerW = reading.batteryPowerW,
            batteryVoltage = reading.batteryVoltage,
            solarPowerW = reading.solarPowerW,
            loadPowerW = reading.loadPowerW,
            soc = reading.soc,
            runtimeMinutes = runtime?.minutesRemaining,
            runtimeCalibrated = runtime?.calibrated == true,
            minutesToFull = RuntimeEstimator.minutesToFull(reading.soc, capacity, charge),
            capacityWh = capacity,
            basis = plan.verdict?.basis ?: "",
            checkedAt = now,
            collectedAt = collectedAt,
            outageSince = plan.state.outageSince,
            inverterAlarm = inverterAlarm,
            today = reading.today,
            error = null
        )
    }

    private fun learnCadence(state: StateStore, collectedAt: Long) {
        if (collectedAt <= 0L) return
        val previous = state.lastCollectionMs

        if (previous > 0L && collectedAt > previous) {
            PollPolicy.learnUploadInterval(state.uploadIntervalMs, collectedAt - previous)?.let {
                state.uploadIntervalMs = it
            }
        }
        if (collectedAt > previous) state.lastCollectionMs = collectedAt
    }

    private fun updateRuntimeModel(state: StateStore, reading: Reading, alerts: AlertSettings): RuntimeEstimate? {
        val smoothed = RuntimeEstimator.smoothLoad(state.smoothedLoadW, reading.loadPowerW)
        if (smoothed != null) state.smoothedLoadW = smoothed

        calibrate(state, reading)

        val learned = state.learnedCapacityWh
        val capacity = learned ?: RuntimeEstimator.nameplateWh(reading.batteryCapacityAh, reading.batteryVoltage)

        return RuntimeEstimator.estimate(
            soc = reading.soc,
            capacityWh = capacity,
            loadWatts = smoothed,
            calibrated = learned != null,
            reservePercent = alerts.batteryReservePercent.toDouble()
        )
    }

    /**
     * Watches a discharge run and works out what the pack is really worth, rather than trusting
     * the nameplate rating forever.
     */
    private fun calibrate(state: StateStore, reading: Reading) {
        val soc = reading.soc ?: return
        val discharged = reading.dischargedKwh ?: return
        val battery = reading.batteryPowerW ?: return

        if (battery <= RuntimeEstimator.MIN_LOAD_W) {
            state.calibrationAnchor = null
            return
        }

        val anchor = state.calibrationAnchor
        if (anchor == null || discharged < anchor.second) {
            state.calibrationAnchor = soc to discharged
            return
        }

        val observed = RuntimeEstimator.learnCapacity(anchor.first, anchor.second, soc, discharged) ?: return
        state.learnedCapacityWh = RuntimeEstimator.blend(state.learnedCapacityWh, observed)
        state.calibrationAnchor = soc to discharged
    }

    private fun pruneOccasionally(state: StateStore, db: HistoryDb, now: Long) {
        val last = state.prefs.getLong(LAST_PRUNE, 0L)
        if (now - last < OutageLog.DAY_MS) return
        db.prune(keepSamplesSince = now - 60 * OutageLog.DAY_MS)
        state.prefs.edit().putLong(LAST_PRUNE, now).apply()
    }

    private companion object {
        const val LAST_PRUNE = "last_prune"
    }
}
