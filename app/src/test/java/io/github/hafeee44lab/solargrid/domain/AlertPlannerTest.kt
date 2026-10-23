package io.github.hafeee44lab.solargrid.domain

import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPlannerTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L

    private fun reading(
        relay: String? = "Closed",
        soc: Double = 80.0,
        load: Double = 900.0,
        solar: Double = 0.0,
        battery: Double = 0.0,
        grid: Double = 900.0,
        temp: Double = 45.0
    ): Reading {
        val metrics = buildList {
            if (relay != null) add(SolarMetric("GRID_RELAY_ST1", "Grid Relay Status", relay, ""))
            add(SolarMetric("B_left_cap1", "SoC", soc.toString(), "%"))
            add(SolarMetric("E_Puse_t1", "Total Consumption Power", load.toString(), "W"))
            add(SolarMetric("S_P_T", "Total Solar Power", solar.toString(), "W"))
            add(SolarMetric("B_P1", "Battery Power", battery.toString(), "W"))
            add(SolarMetric("PG_Pt1", "Total Grid Power", grid.toString(), "W"))
            add(SolarMetric("AC_T", "AC Temperature", temp.toString(), "℃"))
        }
        return GridAnalyzer.read(metrics)
    }

    private fun down(soc: Double = 70.0, load: Double = 900.0) =
        reading(relay = "Break", soc = soc, load = load, battery = load, grid = 0.0)

    private fun ctx(
        at: Long = now,
        settings: AlertSettings = AlertSettings(),
        minuteOfDay: Int = 12 * 60,
        runtime: Int? = 120,
        prediction: Prediction? = null,
        summary: DaySummary? = null
    ) = PlanContext(
        now = at,
        minuteOfDay = minuteOfDay,
        settings = settings,
        runtimeMinutes = runtime,
        prediction = prediction,
        summary = summary,
        startOfDay = at - minuteOfDay * min,
        formatTime = { "T" }
    )

    private fun upload(r: Reading, at: Long) = Observation.Upload(r, collectedAt = at)

    /** A system that has been watched for a while and knows the grid is up. */
    private val settled = MonitorState(
        outage = OutageState(committedDown = false, pendingDown = false, pendingCount = 3),
        lastUploadAt = now - 5 * min
    )

    private val inOutage = MonitorState(
        outage = OutageState(committedDown = true, pendingDown = true, pendingCount = 3),
        lastUploadAt = now - 5 * min,
        outageSince = now - 40 * min
    )

    @Test
    fun anOutageRingsTheAlarmAndOpensARecord() {
        val plan = AlertPlanner.plan(settled, upload(down(), now - min), ctx())

        val alert = plan.alerts.single()
        assertEquals(AlertKind.OUTAGE, alert.kind)
        assertTrue(alert.ring)
        assertNotNull(alert.relay)
        assertEquals(now - min, plan.outageBegan)
        assertEquals(now - min, plan.state.outageSince)
        assertTrue(plan.state.outage.committedDown!!)
    }

    @Test
    fun theOutageStartsWhenTheInverterSawItNotWhenWePolled() {
        val plan = AlertPlanner.plan(settled, upload(down(), now - 4 * min), ctx())
        assertEquals(now - 4 * min, plan.outageBegan)
    }

    @Test
    fun anImplausiblyOldUploadTimeIsNotUsedAsTheStart() {
        val plan = AlertPlanner.plan(settled.copy(lastUploadAt = now - 3 * 60 * min), upload(down(), now - 2 * 60 * min), ctx())
        assertEquals(now, plan.outageBegan)
    }

    @Test
    fun quietHoursDowngradeTheAlarmToANotification() {
        val settings = AlertSettings(quietHours = true, quietStartMinute = 23 * 60, quietEndMinute = 7 * 60)
        val plan = AlertPlanner.plan(settled, upload(down(), now), ctx(settings = settings, minuteOfDay = 2 * 60))

        val alert = plan.alerts.single()
        assertEquals(AlertKind.OUTAGE, alert.kind)
        assertFalse(alert.ring)
    }

    @Test
    fun turningTheAlarmOffStillNotifies() {
        val plan = AlertPlanner.plan(settled, upload(down(), now), ctx(settings = AlertSettings(alarmOnOutage = false)))
        assertFalse(plan.alerts.single().ring)
    }

    @Test
    fun restorationStopsTheAlarmAndSaysHowLongItLasted() {
        val plan = AlertPlanner.plan(inOutage, upload(reading(), now), ctx())

        assertTrue(plan.stopAlarm)
        assertEquals(now, plan.outageEnded)
        val alert = plan.alerts.single()
        assertEquals(AlertKind.RESTORED, alert.kind)
        assertTrue(alert.body.contains("40m"))
        assertEquals(0L, plan.state.outageSince)
    }

    @Test
    fun restorationCanBeSilencedButStillEndsTheRecord() {
        val plan = AlertPlanner.plan(inOutage, upload(reading(), now), ctx(settings = AlertSettings(notifyRestored = false)))
        assertTrue(plan.alerts.isEmpty())
        assertEquals(now, plan.outageEnded)
        assertTrue(plan.stopAlarm)
    }

    @Test
    fun theSameUploadServedTwiceIsNotASecondConfirmation() {
        // Power-balance inference needs two readings. Solarman repeating one upload is not two.
        val inferred = reading(relay = null, battery = 800.0, grid = 0.0)
        val first = AlertPlanner.plan(settled, upload(inferred, now - min), ctx())
        assertTrue(first.alerts.isEmpty())

        val repeat = AlertPlanner.plan(first.state, upload(inferred, now - min), ctx(at = now + 2 * min))
        assertFalse(repeat.freshUpload)
        assertTrue(repeat.alerts.isEmpty())

        val second = AlertPlanner.plan(repeat.state, upload(inferred, now + 4 * min), ctx(at = now + 5 * min))
        assertEquals(AlertKind.OUTAGE, second.alerts.single().kind)
    }

    @Test
    fun lowBatteryWarnsOncePerOutage() {
        val first = AlertPlanner.plan(inOutage, upload(down(soc = 19.0), now), ctx())
        assertEquals(AlertKind.LOW_BATTERY, first.alerts.single().kind)

        val again = AlertPlanner.plan(first.state, upload(down(soc = 18.0), now + 2 * min), ctx(at = now + 3 * min))
        assertTrue(again.alerts.isEmpty())
    }

    @Test
    fun criticalBatteryRingsAndSupersedesLow() {
        val plan = AlertPlanner.plan(inOutage, upload(down(soc = 9.0), now), ctx())
        val alert = plan.alerts.single()
        assertEquals(AlertKind.CRITICAL_BATTERY, alert.kind)
        assertTrue(alert.ring)
        assertTrue(plan.state.lowSent)
    }

    @Test
    fun criticalRingsEvenInQuietHours() {
        val settings = AlertSettings(quietHours = true, quietStartMinute = 0, quietEndMinute = 23 * 60)
        val plan = AlertPlanner.plan(inOutage, upload(down(soc = 8.0), now), ctx(settings = settings, minuteOfDay = 60))
        assertTrue(plan.alerts.single().ring)
    }

    @Test
    fun lowBatteryIsIgnoredWhileTheGridIsUp() {
        val plan = AlertPlanner.plan(settled, upload(reading(soc = 12.0), now), ctx())
        assertTrue(plan.alerts.none { it.kind == AlertKind.LOW_BATTERY || it.kind == AlertKind.CRITICAL_BATTERY })
    }

    @Test
    fun aNewOutageCanWarnAboutBatteryAgain() {
        val warned = inOutage.copy(lowSent = true)
        val restored = AlertPlanner.plan(warned, upload(reading(soc = 19.0), now), ctx())
        val again = AlertPlanner.plan(restored.state, upload(down(soc = 18.0), now + 10 * min), ctx(at = now + 10 * min))
        assertTrue(again.alerts.any { it.kind == AlertKind.LOW_BATTERY })
    }

    @Test
    fun heavyLoadOnBatteryWarnsWhenEnabled() {
        val off = AlertPlanner.plan(inOutage, upload(down(load = 3500.0), now), ctx())
        assertTrue(off.alerts.none { it.kind == AlertKind.OVERLOAD })

        val on = AlertPlanner.plan(inOutage, upload(down(load = 3500.0), now), ctx(settings = AlertSettings(overloadWatts = 3000)))
        assertEquals(AlertKind.OVERLOAD, on.alerts.single().kind)
    }

    @Test
    fun batteryFullIsOptInAndOnlyOnce() {
        val off = AlertPlanner.plan(settled, upload(reading(soc = 100.0), now), ctx())
        assertTrue(off.alerts.isEmpty())

        val settings = AlertSettings(notifyBatteryFull = true)
        val fresh = settled.copy(fullSent = false)
        val first = AlertPlanner.plan(fresh, upload(reading(soc = 100.0, solar = 4000.0, load = 800.0), now), ctx(settings = settings))
        val alert = first.alerts.single()
        assertEquals(AlertKind.BATTERY_FULL, alert.kind)
        assertTrue(alert.body.contains("spare"))

        val again = AlertPlanner.plan(first.state, upload(reading(soc = 100.0), now + 5 * min), ctx(at = now + 5 * min, settings = settings))
        assertTrue(again.alerts.isEmpty())
    }

    @Test
    fun aHotInverterWarnsOnceAndClearsWithMargin() {
        val hot = AlertPlanner.plan(settled, upload(reading(temp = 70.0), now), ctx())
        assertEquals(AlertKind.INVERTER_HOT, hot.alerts.single().kind)

        val stillWarm = AlertPlanner.plan(hot.state, upload(reading(temp = 62.0), now + 5 * min), ctx(at = now + 5 * min))
        assertTrue(stillWarm.state.hotSent)

        val cooled = AlertPlanner.plan(stillWarm.state, upload(reading(temp = 55.0), now + 10 * min), ctx(at = now + 10 * min))
        assertFalse(cooled.state.hotSent)
    }

    @Test
    fun anInverterAlarmDuringAnOutageIsNotAFault() {
        val plan = AlertPlanner.plan(settled, Observation.Upload(down(), now, inverterAlarm = true), ctx())
        assertTrue(plan.alerts.none { it.kind == AlertKind.INVERTER_FAULT })
    }

    @Test
    fun anInverterAlarmWithTheGridUpIsReported() {
        val plan = AlertPlanner.plan(settled, Observation.Upload(reading(), now, inverterAlarm = true), ctx())
        assertEquals(AlertKind.INVERTER_FAULT, plan.alerts.single().kind)
    }

    @Test
    fun aSilentInverterIsNewsOnceItHasBeenQuietLongEnough() {
        val quiet = settled.copy(lastUploadAt = now - 30 * min)
        val plan = AlertPlanner.plan(quiet, Observation.LoggerOffline, ctx())
        val alert = plan.alerts.single()
        assertEquals(AlertKind.INVERTER_SILENT, alert.kind)
        assertTrue(alert.body.contains("router"))

        val again = AlertPlanner.plan(plan.state, Observation.LoggerOffline, ctx(at = now + 5 * min))
        assertTrue(again.alerts.isEmpty())
    }

    @Test
    fun silenceDuringAnOutageSuggestsTheBatteryRanOut() {
        val plan = AlertPlanner.plan(inOutage.copy(lastUploadAt = now - 30 * min), Observation.LoggerOffline, ctx())
        assertTrue(plan.alerts.single().body.contains("battery may have run out"))
    }

    @Test
    fun ourOwnNetworkFailureIsNotBlamedOnTheInverter() {
        val plan = AlertPlanner.plan(settled.copy(lastUploadAt = now - 60 * min), Observation.Unreachable, ctx())
        assertTrue(plan.alerts.isEmpty())
    }

    @Test
    fun aFreshUploadAfterSilenceIsLogged() {
        val silent = settled.copy(lastUploadAt = now - 40 * min, silentSent = true)
        val plan = AlertPlanner.plan(silent, upload(reading(), now), ctx())
        assertFalse(plan.state.silentSent)
        assertTrue(plan.events.any { it.text.contains("reporting again") })
    }

    @Test
    fun aHeadsUpComesOnceBeforeALikelyOutage() {
        val slot = OutageSlot(minuteOfDay = 12 * 60 + 10, daysSeen = 9, daysObserved = 14, typicalDurationMs = 90 * min)
        val prediction = Prediction(slot, now + 10 * min)

        val plan = AlertPlanner.plan(settled, Observation.Unreachable, ctx(prediction = prediction, runtime = 200))
        val alert = plan.alerts.single()
        assertEquals(AlertKind.HEADS_UP, alert.kind)
        assertTrue(alert.body.contains("9 of the last 14"))
        assertTrue(alert.body.contains("should cover it"))

        val again = AlertPlanner.plan(plan.state, Observation.Unreachable, ctx(at = now + min, prediction = prediction))
        assertTrue(again.alerts.isEmpty())
    }

    @Test
    fun noHeadsUpWhenItIsTooFarOffOrDuringAnOutage() {
        val slot = OutageSlot(14 * 60, 9, 14, 60 * min)
        val far = AlertPlanner.plan(settled, Observation.Unreachable, ctx(prediction = Prediction(slot, now + 2 * 60 * min)))
        assertTrue(far.alerts.isEmpty())

        val during = AlertPlanner.plan(inOutage, Observation.Unreachable, ctx(prediction = Prediction(slot, now + 5 * min)))
        assertTrue(during.alerts.none { it.kind == AlertKind.HEADS_UP })
    }

    @Test
    fun theHeadsUpWarnsWhenTheBatteryWillNotLast() {
        val slot = OutageSlot(12 * 60, 9, 14, 3 * 60 * min)
        val plan = AlertPlanner.plan(settled, Observation.Unreachable, ctx(prediction = Prediction(slot, now + 5 * min), runtime = 60))
        assertTrue(plan.alerts.single().body.contains("less than usual"))
    }

    @Test
    fun theDailySummaryGoesOutOnceAfterItsTime() {
        val settings = AlertSettings(dailySummary = true, summaryMinuteOfDay = 21 * 60)
        val summary = DaySummary(outages = 2, downMs = 150 * min, producedKwh = 18.4, selfSufficiency = 0.72)

        val early = AlertPlanner.plan(settled, Observation.Unreachable, ctx(settings = settings, minuteOfDay = 20 * 60, summary = summary))
        assertTrue(early.alerts.isEmpty())

        val due = AlertPlanner.plan(settled, Observation.Unreachable, ctx(settings = settings, minuteOfDay = 21 * 60 + 5, summary = summary))
        val alert = due.alerts.single()
        assertEquals(AlertKind.SUMMARY, alert.kind)
        assertTrue(alert.body.contains("2 outages"))
        assertTrue(alert.body.contains("18.4 kWh"))
        assertTrue(alert.body.contains("72%"))

        val again = AlertPlanner.plan(due.state, Observation.Unreachable, ctx(at = now + 30 * min, settings = settings, minuteOfDay = 21 * 60 + 35, summary = summary))
        assertTrue(again.alerts.isEmpty())
    }

    @Test
    fun quietHoursWrapPastMidnight() {
        val s = AlertSettings(quietHours = true, quietStartMinute = 23 * 60, quietEndMinute = 7 * 60)
        assertTrue(s.isQuiet(23 * 60 + 30))
        assertTrue(s.isQuiet(3 * 60))
        assertFalse(s.isQuiet(7 * 60))
        assertFalse(s.isQuiet(12 * 60))
        assertFalse(AlertSettings().isQuiet(3 * 60))
    }

    @Test
    fun aFirstHealthyReadingIsQuiet() {
        val plan = AlertPlanner.plan(MonitorState(), upload(reading(), now), ctx())
        assertTrue(plan.alerts.isEmpty())
        assertNull(plan.outageBegan)
        assertEquals(false, plan.state.outage.committedDown)
    }
}
