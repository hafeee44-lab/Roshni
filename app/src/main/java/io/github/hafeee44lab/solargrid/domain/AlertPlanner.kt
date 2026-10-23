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
    val formatTime: (Long) -> String = { "%tR".format(Locale.US, it) }
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
                events += Event(EventKind.HEALTH, ctx.now, "Inverter is reporting again")
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

                            val runtime = ctx.runtimeMinutes?.takeIf { it > 0 }
                                ?.let { " · lasts about ${RuntimeEstimator.describe(it)}" } ?: ""

                            alerts += Alert(
                                kind = AlertKind.OUTAGE,
                                title = "Grid outage",
                                body = "Running on battery at $socText%$runtime.",
                                ring = settings.alarmOnOutage && !quiet,
                                relay = "⚠️ *Grid outage*\nMains power is down, running on battery.\n" +
                                    "Battery: $socText%\n${ctx.formatTime(since)}"
                            )
                            events += Event(EventKind.OUTAGE, since, "Grid failed — ${verdict.basis}")
                        } else {
                            val since = previous.outageSince
                            val lasted = if (since > 0L) {
                                " after ${OutageLog.describeDuration(ctx.now - since)}"
                            } else {
                                ""
                            }

                            state = state.copy(outageSince = 0L)
                            ended = ctx.now
                            stopAlarm = true

                            if (settings.notifyRestored) {
                                alerts += Alert(
                                    kind = AlertKind.RESTORED,
                                    title = "Grid restored",
                                    body = "Mains are back$lasted. Battery at $socText%.",
                                    relay = "⚡ *Grid restored*\nMains power is back$lasted.\n" +
                                        "Battery: $socText%\n${ctx.formatTime(ctx.now)}"
                                )
                            }
                            events += Event(EventKind.RESTORED, ctx.now, "Grid restored$lasted — ${verdict.basis}")
                        }
                    }

                    is Transition.Baseline ->
                        events += Event(EventKind.INFO, ctx.now, "Monitoring started — grid online")

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

        if (down) {
            if (soc <= settings.criticalBatteryPercent && !state.criticalSent) {
                state = state.copy(criticalSent = true, lowSent = true)
                alerts += Alert(
                    kind = AlertKind.CRITICAL_BATTERY,
                    title = "Battery critical · $socText%",
                    body = runtimeLine(ctx, soc) + " Switch off what you can.",
                    ring = settings.criticalAlarm,
                    relay = "🪫 *Battery critical — $socText%*\nGrid is still down.\n${ctx.formatTime(ctx.now)}"
                )
                events += Event(EventKind.BATTERY, ctx.now, "Battery critical at $socText% during outage")
            } else if (soc <= settings.lowBatteryPercent && !state.lowSent) {
                state = state.copy(lowSent = true)
                alerts += Alert(
                    kind = AlertKind.LOW_BATTERY,
                    title = "Battery low · $socText%",
                    body = runtimeLine(ctx, soc),
                    relay = "🔋 *Battery low — $socText%*\nGrid is still down.\n${ctx.formatTime(ctx.now)}"
                )
                events += Event(EventKind.BATTERY, ctx.now, "Battery low at $socText% during outage")
            }

            val load = reading.loadPowerW
            if (settings.overloadWatts > 0 && load != null && load >= settings.overloadWatts && !state.overloadSent) {
                state = state.copy(overloadSent = true)
                alerts += Alert(
                    kind = AlertKind.OVERLOAD,
                    title = "Heavy load on battery",
                    body = "The house is drawing ${kw(load)}. " + runtimeLine(ctx, soc)
                )
                events += Event(EventKind.BATTERY, ctx.now, "Heavy load on battery: ${kw(load)}")
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
                    title = "Battery full",
                    body = if (surplus > 300) {
                        "Solar has ${kw(surplus)} to spare — a good time for heavy appliances."
                    } else {
                        "The battery is charged to $socText%."
                    }
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

        val temp = reading.inverterTempC
        if (temp != null && temp >= settings.inverterHotC && !state.hotSent) {
            state = state.copy(hotSent = true)
            if (settings.notifyHealth) {
                alerts += Alert(
                    kind = AlertKind.INVERTER_HOT,
                    title = "Inverter running hot · ${"%.0f".format(Locale.US, temp)}°C",
                    body = "Check that its vents are clear and it isn't in direct sun."
                )
            }
            events += Event(EventKind.HEALTH, ctx.now, "Inverter at ${"%.0f".format(Locale.US, temp)}°C")
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
                    title = "Inverter reports a fault",
                    body = "Solarman flags an alarm on your inverter. Open the Solarman app for the code."
                )
            }
            events += Event(EventKind.HEALTH, ctx.now, "Solarman reports an inverter alarm")
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

        val minutes = OutageLog.describeDuration(age)
        val down = current.outage.committedDown == true

        if (ctx.settings.notifyHealth) {
            alerts += Alert(
                kind = AlertKind.INVERTER_SILENT,
                title = "Inverter has gone quiet",
                body = if (down) {
                    "No data for $minutes during the outage. The battery may have run out, or the Wi-Fi is down."
                } else {
                    "No data for $minutes. If your router isn't on backup power, the mains may be down."
                }
            )
        }
        events += Event(EventKind.HEALTH, ctx.now, "Inverter silent for $minutes")
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

        val slot = prediction.slot
        val usually = slot.typicalDurationMs?.let { " for about ${OutageLog.describeDuration(it)}" } ?: ""
        val runtime = ctx.runtimeMinutes
        val cover = when {
            runtime == null || slot.typicalDurationMs == null -> ""
            runtime * 60_000L >= slot.typicalDurationMs -> " Your battery should cover it."
            else -> " At this load the battery lasts about ${RuntimeEstimator.describe(runtime)} — less than usual."
        }

        alerts += Alert(
            kind = AlertKind.HEADS_UP,
            title = "Outage likely around ${ctx.formatTime(prediction.startsAt)}",
            body = "It has gone off near this time on ${slot.daysSeen} of the last ${slot.daysObserved} days$usually.$cover"
        )
        return current.copy(headsUpFor = prediction.startsAt)
    }

    private fun summaryRule(current: MonitorState, ctx: PlanContext, alerts: MutableList<Alert>): MonitorState {
        val settings = ctx.settings
        val summary = ctx.summary ?: return current

        if (!settings.dailySummary) return current
        if (ctx.minuteOfDay < settings.summaryMinuteOfDay) return current
        if (current.summaryDay == ctx.startOfDay) return current

        val parts = mutableListOf<String>()
        parts += when (summary.outages) {
            0 -> "No outages"
            1 -> "1 outage · ${OutageLog.describeDuration(summary.downMs)} on battery"
            else -> "${summary.outages} outages · ${OutageLog.describeDuration(summary.downMs)} on battery"
        }
        summary.producedKwh?.let { parts += "solar ${"%.1f".format(Locale.US, it)} kWh" }
        summary.selfSufficiency?.let { parts += "${"%.0f".format(Locale.US, it * 100)}% self-powered" }

        alerts += Alert(kind = AlertKind.SUMMARY, title = "Today on your system", body = parts.joinToString(" · "))
        return current.copy(summaryDay = ctx.startOfDay)
    }

    private fun runtimeLine(ctx: PlanContext, soc: Double): String {
        val minutes = ctx.runtimeMinutes
        return if (minutes != null && minutes > 0) {
            "Power runs out around ${ctx.formatTime(ctx.now + minutes * 60_000L)} at this load."
        } else {
            "Only ${percent(soc)}% left and the grid is still down."
        }
    }

    private fun percent(value: Double?): String =
        value?.let { "%.0f".format(Locale.US, it) } ?: "--"

    private fun kw(watts: Double): String = "%.1f kW".format(Locale.US, watts / 1000.0)
}
