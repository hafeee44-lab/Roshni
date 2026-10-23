package io.github.hafeee44lab.solargrid.domain

import io.github.hafeee44lab.solargrid.data.SolarSnapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiTest {

    private val now = 1_700_000_000_000L

    private fun snap(
        gridDown: Boolean? = false,
        ageMs: Long = 30_000L,
        inverterAgeMs: Long = 120_000L,
        soc: Double? = 78.0,
        volts: Double? = 232.0,
        battery: Double? = -800.0,
        solar: Double? = 3100.0,
        load: Double? = 1168.0,
        grid: Double? = 1240.0,
        error: String? = null,
        runtimeMinutes: Int? = 168,
        phases: List<Double> = listOf(231.0, 230.0, 229.0),
        inverterTemp: Double? = 51.2
    ) = SolarSnapshot(
        gridDown = gridDown,
        gridPowerW = grid,
        gridVoltage = volts,
        gridPhaseVolts = phases,
        inverterTempC = inverterTemp,
        batteryTempC = 34.0,
        batteryPowerW = battery,
        solarPowerW = solar,
        loadPowerW = load,
        soc = soc,
        runtimeMinutes = runtimeMinutes,
        runtimeCalibrated = true,
        basis = "grid voltage",
        checkedAt = now - ageMs,
        collectedAt = now - inverterAgeMs,
        error = error
    )

    @Test
    fun healthyGridReadsAsOnline() {
        val ui = buildHomeUi(snap(), running = true, now = now)
        assertEquals("Mains on", ui.heroState)
        assertEquals(StateRole.UP, ui.heroRole)
        assertTrue(ui.gridLink.live)
        assertFalse(ui.freshAlert)
    }

    @Test
    fun outageReadsAsDownAndReversesTheBattery() {
        val ui = buildHomeUi(snap(gridDown = true, battery = 2100.0, volts = 0.0), running = true, now = now)
        assertEquals("Mains off", ui.heroState)
        assertEquals(StateRole.DOWN, ui.heroRole)
        assertFalse(ui.gridLink.live)
        assertTrue(ui.batteryLink.live)
        assertTrue(ui.batteryLink.reversed)
    }

    @Test
    fun chargingBatteryFlowsTowardTheBattery() {
        val ui = buildHomeUi(snap(battery = -900.0), running = true, now = now)
        assertTrue(ui.batteryLink.live)
        assertFalse(ui.batteryLink.reversed)
        assertTrue(ui.batteryFlow.contains("charging"))
    }

    @Test
    fun staleDataNeverClaimsAState() {
        val ui = buildHomeUi(snap(ageMs = 20 * 60_000L), running = true, now = now)
        assertEquals("Last known", ui.heroState)
        assertEquals(StateRole.NEUTRAL, ui.heroRole)
        assertTrue(ui.freshAlert)
        assertFalse(ui.gridLink.live)
        assertEquals("—", ui.gridLabel)
    }

    @Test
    fun anErrorNeverClaimsAState() {
        val ui = buildHomeUi(snap(error = "Solarman returned HTTP 500"), running = true, now = now)
        assertEquals("Last known", ui.heroState)
        assertTrue(ui.freshAlert)
        assertEquals("Solarman returned HTTP 500", ui.heroSub)
    }

    @Test
    fun missingGridMetricSaysSoPlainly() {
        val ui = buildHomeUi(snap(gridDown = null), running = true, now = now)
        assertEquals("Can't tell", ui.heroState)
        assertEquals(StateRole.NEUTRAL, ui.heroRole)
    }

    @Test
    fun noDataYetShowsStarting() {
        val ui = buildHomeUi(SolarSnapshot(), running = true, now = now)
        assertEquals("Starting", ui.heroState)
        assertEquals("Waiting for the first reading", ui.heroSub)
    }

    @Test
    fun staleInverterUploadRaisesTheAlertEvenWhenTheCheckSucceeded() {
        val ui = buildHomeUi(snap(inverterAgeMs = 40 * 60_000L), running = true, now = now)
        assertTrue(ui.freshAlert)
        assertTrue(ui.freshSecondary.contains("last reported"))
    }

    @Test
    fun idleBatteryIsNotDrawnAsFlowing() {
        val ui = buildHomeUi(snap(battery = 5.0), running = true, now = now)
        assertFalse(ui.batteryLink.live)
        assertEquals("idle", ui.batteryFlow)
    }

    @Test
    fun houseLoadIsHiddenWhenTheReadingCannotBeTrusted() {
        assertEquals("1.17 kW", buildHomeUi(snap(), running = true, now = now).houseLoad)
        assertEquals("—", buildHomeUi(snap(ageMs = 20 * 60_000L), running = true, now = now).houseLoad)
    }

    @Test
    fun runtimeIsShownWhenTheReadingCanBeTrusted() {
        assertEquals("2h 48m", buildHomeUi(snap(), running = true, now = now).runtime)
    }

    @Test
    fun runtimeIsWithheldWhenTheReadingIsStale() {
        assertNull(buildHomeUi(snap(ageMs = 20 * 60_000L), running = true, now = now).runtime)
    }

    @Test
    fun noRuntimeGivesAReasonRatherThanABlank() {
        val ui = buildHomeUi(snap(runtimeMinutes = null), running = true, now = now)
        assertNull(ui.runtime)
        assertEquals("load too low to estimate", ui.runtimeNote)
    }

    @Test
    fun everyPhaseIsLabelledAndMarkedLive() {
        val ui = buildHomeUi(snap(), running = true, now = now)
        assertEquals(listOf("L1", "L2", "L3"), ui.phases.map { it.label })
        assertTrue(ui.phases.all { it.live })
        assertNull(ui.phaseNote)
    }

    @Test
    fun aMissingPhaseIsCalledOutByName() {
        val ui = buildHomeUi(snap(phases = listOf(231.0, 0.0, 229.0)), running = true, now = now)
        assertEquals("L2 missing — partial supply", ui.phaseNote)
        assertFalse(ui.phases[1].live)
    }

    @Test
    fun everyPhaseDeadSaysSoPlainly() {
        val ui = buildHomeUi(snap(phases = listOf(0.0, 0.0, 0.0)), running = true, now = now)
        assertEquals("All phases dead", ui.phaseNote)
    }

    @Test
    fun aHotInverterIsFlagged() {
        assertFalse(buildHomeUi(snap(), running = true, now = now).inverterTempHot)
        assertTrue(buildHomeUi(snap(inverterTemp = 71.0), running = true, now = now).inverterTempHot)
        assertEquals("51°C", buildHomeUi(snap(), running = true, now = now).inverterTemp)
    }

    @Test
    fun missingSocDoesNotRenderAsZero() {
        val ui = buildHomeUi(snap(soc = null), running = true, now = now)
        assertEquals("—", ui.socText)
    }

    @Test
    fun zeroVoltsIsCalledOut() {
        val ui = buildHomeUi(snap(gridDown = true, volts = 0.0), running = true, now = now)
        assertEquals("No mains detected", ui.voltNote)
    }
}
