package io.github.hafeee44lab.solargrid.domain

import java.util.Locale

/**
 * Everything the monitor remembers between polls. All of it is persisted: the service is
 * killable at any moment, so state held only in memory would silently reset.
 */
data class MonitorState(
    val outage: OutageState = OutageState(),
    val lastUploadAt: Long = 0L,
    val stateChangedAt: Long = 0L,
    val outageSince: Long = 0L,
    val lowSent: Boolean = false,
    val criticalSent: Boolean = false,
    val overloadSent: Boolean = false,
    val fullSent: Boolean = false,
    val hotSent: Boolean = false,
    val faultSent: Boolean = false,
    val silentSent: Boolean = false,
    val headsUpFor: Long = 0L,
    val summaryDay: Long = 0L
)

sealed interface Observation {
    /** A reading from Solarman. [inverterAlarm] is Solarman's deviceState 2. */
    data class Upload(
        val reading: Reading,
        val collectedAt: Long,
        val inverterAlarm: Boolean = false
    ) : Observation

    /** Solarman answered, but says the logger itself is offline. */
    data object LoggerOffline : Observation

    /** We could not reach Solarman at all. Says nothing about the grid. */
    data object Unreachable : Observation
}

enum class AlertKind {
    OUTAGE, RESTORED, LOW_BATTERY, CRITICAL_BATTERY, BATTERY_FULL, OVERLOAD,
    INVERTER_SILENT, INVERTER_FAULT, INVERTER_HOT, HEADS_UP, SUMMARY
}

data class Alert(
    val kind: AlertKind,
    val title: String,
    val body: String,
    /** Loop the alarm sound and take over the screen, not just post a notification. */
    val ring: Boolean = false,
    /** Text for the WhatsApp relay, or null when this alert is not worth forwarding. */
    val relay: String? = null
)

enum class EventKind { OUTAGE, RESTORED, BATTERY, HEALTH, INFO }

data class Event(val kind: EventKind, val at: Long, val text: String)

data class DaySummary(
    val outages: Int,
    val downMs: Long,
    val producedKwh: Double?,
    val selfSufficiency: Double?
)

data class PlanContext(
    val now: Long,
    val minuteOfDay: Int,
    val settings: AlertSettings,
    val runtimeMinutes: Int? = null,
    val staleAfterMs: Long = 20 * 60_000L,
    val prediction: Prediction? = null,
    val summary: DaySummary? = null,
    val startOfDay: Long = OutageLog.startOfDay(now),
    val formatTime: (Long) -> String = { "%tR".format(Locale.US, it) },
    val phrases: Phrases = Phrases.English
)

data class Plan(
    val state: MonitorState,
    val alerts: List<Alert> = emptyList(),
    val events: List<Event> = emptyList(),
    val freshUpload: Boolean = false,
    val verdict: Verdict? = null,
    val outageBegan: Long? = null,
    val outageEnded: Long? = null,
    /** The grid is back: a ringing alarm should stop on its own. */
    val stopAlarm: Boolean = false
)

/**
 * Turns an observation into what should happen: the new state, which alerts fire and what
 * goes in the log. Pure, so every rule below has a test.
 */
object AlertPlanner {

    const val HYSTERESIS_PERCENT = 5
    const val FULL_PERCENT = 99.0
    const val FULL_CLEAR_PERCENT = 90.0
    const val HOT_CLEAR_MARGIN_C = 5.0
    const val OVERLOAD_CLEAR_RATIO = 0.8

    /** An outage can't have started earlier than this before we saw it. */
    private const val START_BACKDATE_LIMIT_MS = 15 * 60_000L

    fun plan(previous: MonitorState, observation: Observation, ctx: PlanContext): Plan {
        val alerts = mutableListOf<Alert>()
        val events = mutableListOf<Event>()
        var state = previous
        var fresh = false
        var verdict: Verdict? = null
        var began: Long? = null
        var ended: Long? = null
        var stopAlarm = false

        val settings = ctx.settings
        val quiet = settings.isQuiet(ctx.minuteOfDay)

        if (observation is Observation.Upload && isNew(observation.collectedAt, previous)) {
            fresh = true
            val reading = observation.reading
            val socText = percent(reading.soc)

            state = state.copy(lastUploadAt = observation.collectedAt.takeIf { it > 0L } ?: ctx.now)

            if (previous.silentSent) {
                state = state.copy(silentSent = false)
                events += Event(EventKind.HEALTH, ctx.now, ctx.phrases.reportingAgain())
            }

            verdict = GridAnalyzer.evaluate(reading)

            if (verdict == null) {
                state = state.copy(outage = state.outage.copy(pendingDown = null, pendingCount = 0))
            } else {
                val transition = OutageStateMachine.apply(state.outage, verdict)
                state = state.copy(outage = transition.state)

                when (transition) {
                    is Transition.Announce -> {
                        state = state.copy(
                            stateChangedAt = ctx.now,
                            lowSent = false,
                            criticalSent = false,
                            overloadSent = false
                        )

                        if (transition.down) {
                            val since = outageStart(observation.collectedAt, ctx.now)
                            state = state.copy(outageSince = since)
                            began = since

                            val p = ctx.phrases
                            val runtime = ctx.runtimeMinutes?.takeIf { it > 0 }?.let { RuntimeEstimator.describe(it, p) }

                            alerts += Alert(
                                kind = AlertKind.OUTAGE,
                                title = p.outageTitle(),
                                body = p.outageBody(socText, runtime),
                                ring = settings.alarmOnOutage && !quiet,
                                relay = p.outageRelay(socText, ctx.formatTime(since))
                            )
                            events += Event(EventKind.OUTAGE, since, p.outageEvent(verdict.basis))
                        } else {
                            val p = ctx.phrases
                            val since = previous.outageSince
                            val lasted = if (since > 0L) OutageLog.describeDuration(ctx.now - since, p) else null

                            state = state.copy(outageSince = 0L)
                            ended = ctx.now
                            stopAlarm = true

                            if (settings.notifyRestored) {
                                alerts += Alert(
                                    kind = AlertKind.RESTORED,
                                    title = p.restoredTitle(),
                                    body = p.restoredBody(socText, lasted),
                                    relay = p.restoredRelay(socText, lasted, ctx.formatTime(ctx.now))
                                )
                            }
                            events += Event(EventKind.RESTORED, ctx.now, p.restoredEvent(lasted, verdict.basis))
                        }
                    }

                    is Transition.Baseline ->
                        events += Event(EventKind.INFO, ctx.now, ctx.phrases.monitoringStarted())

                    is Transition.Hold -> Unit
                }
            }

            state = batteryRules(state, reading, ctx, alerts, events)
            state = healthRules(state, reading, observation.inverterAlarm, ctx, alerts, events)
        }

        state = silenceRule(state, fresh, observation, ctx, alerts, events)
        state = headsUpRule(state, ctx, quiet, alerts)
        state = summaryRule(state, ctx, alerts)

        return Plan(
            state = state,
            alerts = alerts,
            events = events,
            freshUpload = fresh,
            verdict = verdict,
            outageBegan = began,
            outageEnded = ended,
            stopAlarm = stopAlarm
        )
    }

    /**
     * Solarman serves the same upload until the logger sends a new one. Counting that repeat
     * as a second confirmation would let one ambiguous reading confirm itself.
     */
    private fun isNew(collectedAt: Long, previous: MonitorState): Boolean =
        collectedAt <= 0L || collectedAt > previous.lastUploadAt

    private fun outageStart(collectedAt: Long, now: Long): Long =
        if (collectedAt in (now - START_BACKDATE_LIMIT_MS)..now) collectedAt else now

    private fun batteryRules(
        current: MonitorState,
        reading: Reading,
        ctx: PlanContext,
        alerts: MutableList<Alert>,
        events: MutableList<Event>
    ): MonitorState {
        val settings = ctx.settings
        val soc = reading.soc ?: return current
        var state = current
        val down = state.outage.committedDown == true
        val socText = percent(soc)
        val p = ctx.phrases

        if (down) {
            if (soc <= settings.criticalBatteryPercent && !state.criticalSent) {
                state = state.copy(criticalSent = true, lowSent = true)
                alerts += Alert(
                    kind = AlertKind.CRITICAL_BATTERY,
                    title = p.criticalTitle(socText),
                    body = runtimeLine(ctx, soc) + " " + p.switchOffWhatYouCan(),
                    ring = settings.criticalAlarm,
                    relay = p.criticalRelay(socText, ctx.formatTime(ctx.now))
                )
                events += Event(EventKind.BATTERY, ctx.now, p.criticalEvent(socText))
            } else if (soc <= settings.lowBatteryPercent && !state.lowSent) {
                state = state.copy(lowSent = true)
                alerts += Alert(
                    kind = AlertKind.LOW_BATTERY,
                    title = p.lowTitle(socText),
                    body = runtimeLine(ctx, soc),
                    relay = p.lowRelay(socText, ctx.formatTime(ctx.now))
                )
                events += Event(EventKind.BATTERY, ctx.now, p.lowEvent(socText))
            }

            val load = reading.loadPowerW
            if (settings.overloadWatts > 0 && load != null && load >= settings.overloadWatts && !state.overloadSent) {
                state = state.copy(overloadSent = true)
                alerts += Alert(
                    kind = AlertKind.OVERLOAD,
                    title = p.overloadTitle(),
                    body = p.overloadBody(kw(load)) + " " + runtimeLine(ctx, soc)
                )
                events += Event(EventKind.BATTERY, ctx.now, p.overloadEvent(kw(load)))
            }
        }

        if (soc >= settings.lowBatteryPercent + HYSTERESIS_PERCENT) state = state.copy(lowSent = false)
        if (soc >= settings.criticalBatteryPercent + HYSTERESIS_PERCENT) state = state.copy(criticalSent = false)

        val load = reading.loadPowerW
        if (state.overloadSent && (load == null || load < settings.overloadWatts * OVERLOAD_CLEAR_RATIO)) {
            state = state.copy(overloadSent = false)
        }

        if (soc >= FULL_PERCENT && !state.fullSent && !down) {
            state = state.copy(fullSent = true)
            if (settings.notifyBatteryFull) {
                val surplus = (reading.solarPowerW ?: 0.0) - (reading.loadPowerW ?: 0.0)
                alerts += Alert(
                    kind = AlertKind.BATTERY_FULL,
                    title = p.fullTitle(),
                    body = if (surplus > 300) p.fullSurplus(kw(surplus)) else p.fullCharged(socText)
                )
            }
        } else if (soc <= FULL_CLEAR_PERCENT) {
            state = state.copy(fullSent = false)
        }

        return state
    }

    private fun healthRules(
        current: MonitorState,
        reading: Reading,
        inverterAlarm: Boolean,
        ctx: PlanContext,
        alerts: MutableList<Alert>,
        events: MutableList<Event>
    ): MonitorState {
        var state = current
        val settings = ctx.settings
        val p = ctx.phrases

        val temp = reading.inverterTempC
        if (temp != null && temp >= settings.inverterHotC && !state.hotSent) {
            state = state.copy(hotSent = true)
            if (settings.notifyHealth) {
                alerts += Alert(
                    kind = AlertKind.INVERTER_HOT,
                    title = p.hotTitle("%.0f".format(Locale.US, temp)),
                    body = p.hotBody()
                )
            }
            events += Event(EventKind.HEALTH, ctx.now, p.hotEvent("%.0f".format(Locale.US, temp)))
        } else if (temp != null && temp < settings.inverterHotC - HOT_CLEAR_MARGIN_C) {
            state = state.copy(hotSent = false)
        }

        // A Deye raises an alarm the instant it loses the grid. That is the outage alert's
        // job, so the fault alert only speaks while the mains are up.
        val gridUp = state.outage.committedDown == false
        if (inverterAlarm && gridUp && !state.faultSent) {
            state = state.copy(faultSent = true)
            if (settings.notifyHealth) {
                alerts += Alert(
                    kind = AlertKind.INVERTER_FAULT,
                    title = p.faultTitle(),
                    body = p.faultBody()
                )
            }
            events += Event(EventKind.HEALTH, ctx.now, p.faultEvent())
        } else if (!inverterAlarm) {
            state = state.copy(faultSent = false)
        }

        return state
    }

    /**
     * The failure mode that matters most: if the Wi-Fi router is not on backup power, an outage
     * silences the logger and Solarman keeps serving the last good reading. Silence is news.
     */
    private fun silenceRule(
        current: MonitorState,
        fresh: Boolean,
        observation: Observation,
        ctx: PlanContext,
        alerts: MutableList<Alert>,
        events: MutableList<Event>
    ): MonitorState {
        if (fresh || current.silentSent || current.lastUploadAt <= 0L) return current
        if (observation is Observation.Unreachable) return current

        val age = ctx.now - current.lastUploadAt
        if (age < ctx.staleAfterMs) return current

        val p = ctx.phrases
        val minutes = OutageLog.describeDuration(age, p)
        val down = current.outage.committedDown == true

        if (ctx.settings.notifyHealth) {
            alerts += Alert(
                kind = AlertKind.INVERTER_SILENT,
                title = p.silentTitle(),
                body = if (down) p.silentBodyDown(minutes) else p.silentBodyUp(minutes)
            )
        }
        events += Event(EventKind.HEALTH, ctx.now, p.silentEvent(minutes))
        return current.copy(silentSent = true)
    }

    private fun headsUpRule(
        current: MonitorState,
        ctx: PlanContext,
        quiet: Boolean,
        alerts: MutableList<Alert>
    ): MonitorState {
        val prediction = ctx.prediction ?: return current
        val settings = ctx.settings

        if (!settings.headsUp || quiet) return current
        if (current.outage.committedDown != false) return current
        if (current.headsUpFor == prediction.startsAt) return current

        val lead = prediction.startsAt - ctx.now
        if (lead <= 0L || lead > settings.headsUpLeadMinutes * 60_000L) return current

        val p = ctx.phrases
        val slot = prediction.slot
        val usually = slot.typicalDurationMs?.let { OutageLog.describeDuration(it, p) }
        val runtime = ctx.runtimeMinutes
        val cover = when {
            runtime == null || slot.typicalDurationMs == null -> ""
            runtime * 60_000L >= slot.typicalDurationMs -> p.batteryShouldCover()
            else -> p.batteryFallsShort(RuntimeEstimator.describe(runtime, p))
        }

        alerts += Alert(
            kind = AlertKind.HEADS_UP,
            title = p.headsUpTitle(ctx.formatTime(prediction.startsAt)),
            body = p.headsUpBody(slot.daysSeen, slot.daysObserved, usually, cover)
        )
        return current.copy(headsUpFor = prediction.startsAt)
    }

    private fun summaryRule(current: MonitorState, ctx: PlanContext, alerts: MutableList<Alert>): MonitorState {
        val settings = ctx.settings
        val summary = ctx.summary ?: return current

        if (!settings.dailySummary) return current
        if (ctx.minuteOfDay < settings.summaryMinuteOfDay) return current
        if (current.summaryDay == ctx.startOfDay) return current

        val p = ctx.phrases
        val parts = mutableListOf<String>()
        parts += p.summaryCuts(summary.outages, OutageLog.describeDuration(summary.downMs, p))
        summary.producedKwh?.let { parts += p.summarySolar("%.1f".format(Locale.US, it)) }
        summary.selfSufficiency?.let { parts += p.summarySelfPowered("%.0f".format(Locale.US, it * 100)) }

        alerts += Alert(kind = AlertKind.SUMMARY, title = p.summaryTitle(), body = parts.joinToString(" · "))
        return current.copy(summaryDay = ctx.startOfDay)
    }

    private fun runtimeLine(ctx: PlanContext, soc: Double): String {
        val minutes = ctx.runtimeMinutes
        return if (minutes != null && minutes > 0) {
            ctx.phrases.runsOutAround(ctx.formatTime(ctx.now + minutes * 60_000L))
        } else {
            ctx.phrases.onlyLeft(percent(soc))
        }
    }

    private fun percent(value: Double?): String =
        value?.let { "%.0f".format(Locale.US, it) } ?: "--"

    private fun kw(watts: Double): String = "%.1f kW".format(Locale.US, watts / 1000.0)
}
