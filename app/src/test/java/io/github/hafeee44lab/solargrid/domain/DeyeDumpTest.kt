package io.github.hafeee44lab.solargrid.domain

import io.github.hafeee44lab.solargrid.data.api.SolarMetric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture taken verbatim from Hafi's three-phase LV hybrid (SG05) at 22:35 on 25 Aug,
 * captured while the mains were actually out. It keeps every decoy that fooled earlier
 * versions: inverter output voltage and frequency, load voltage and frequency, reactive
 * power, per-phase grid power and the BMS copy of SoC.
 */
class DeyeDumpTest {

    private fun m(key: String, value: String, unit: String, name: String) =
        SolarMetric(key = key, name = name, value = value, unit = unit)

    private val outage = listOf(
        m("Pr1", "8000.00", "W", "Rated Power"),
        m("DV1", "14.40", "V", "DC Voltage PV1"),
        m("DP1", "0", "W", "DC Power PV1"),
        m("AV1", "229.90", "V", "AC Voltage R/U/A"),
        m("AV2", "229.90", "V", "AC Voltage S/V/B"),
        m("AV3", "228.90", "V", "AC Voltage T/W/C"),
        m("A_Fo1", "50.00", "Hz", "AC Output Frequency R"),
        m("INV_O_P_T", "1168", "W", "Total Inverter Output Power"),
        m("S_P_T", "0.00", "W", "Total Solar Power"),
        m("G_V_L1", "0.00", "V", "Grid Voltage L1"),
        m("G_P_L1", "0", "W", "Grid Power L1"),
        m("G_V_L2", "0.00", "V", "Grid Voltage L2"),
        m("G_V_L3", "0.00", "V", "Grid Voltage L3"),
        m("ST_PG1", "Static", "", "Grid Status"),
        m("PG_F1", "0.00", "Hz", "Grid Frequency"),
        m("PG_Pt1", "0", "W", "Total Grid Power"),
        m("G16", "8400.00", "Var", "Total Grid reactive Power"),
        m("A_RP_PG", "1040.00", "Var", "A-Phase Reactive Power Of Power Grid"),
        m("GS_T", "0", "W", "Internal Power"),
        m("C_V_L1", "229.20", "V", "Load Voltage  L1"),
        m("C_P_L3", "1055", "W", "Load  Power L3"),
        m("E_Puse_t1", "1168", "W", "Total Consumption Power"),
        m("E_Suse_t1", "1166", "VA", "Total Consumption Apparent Power"),
        m("L_F", "50.00", "Hz", "Load Fequency"),
        m("B_ST1", "Discharging", "", "Battery Status"),
        m("B_P1", "1228", "W", "Battery Power"),
        m("B_V1", "52.56", "V", "Battery Voltage"),
        m("BMS_B_V1", "52.60", "V", "BMS Voltage"),
        m("BMS_C_V", "58.40", "V", "BMS Charge Voltage"),
        m("Etdy_dcg1", "5.60", "kWh", "Daily Discharging Energy"),
        m("Etdy_cg1", "5.50", "kWh", "Daily Charging Energy"),
        m("B_left_cap1", "79", "%", "SoC"),
        m("BRC", "110", "Ah", "Battery Rated Capacity"),
        m("BMS_SOC", "79", "%", "BMS_SOC"),
        m("BMST", "34.00", "℃", "BMS Temperature"),
        m("B_T1", "34.00", "℃", "Temperature- Battery"),
        m("AC_T", "51.20", "℃", "AC Temperature"),
        m("GRID_RELAY_ST1", "Break", "", "Grid Relay Status"),
        m("S_P_TO", "0", "W", "Total Smartload Power")
    )

    @Test
    fun theRealOutageIsDetected() {
        val verdict = GridAnalyzer.evaluate(GridAnalyzer.read(outage))!!
        assertTrue("this dump was taken during a real outage", verdict.gridDown)
        assertEquals("grid relay open", verdict.basis)
    }

    @Test
    fun gridVoltageComesFromTheGridRegisterNotTheInverterOutput() {
        assertEquals(0.0, GridAnalyzer.read(outage).gridVoltage!!, 0.001)
    }

    @Test
    fun gridFrequencyComesFromTheGridRegisterNotTheOutputOrTheLoad() {
        assertEquals(0.0, GridAnalyzer.read(outage).gridFrequency!!, 0.001)
    }

    @Test
    fun reactiveAndPerPhasePowerAreNotMistakenForTotalGridPower() {
        assertEquals(0.0, GridAnalyzer.read(outage).gridPowerW!!, 0.001)
    }

    @Test
    fun theRestOfTheReadingIsCorrect() {
        val reading = GridAnalyzer.read(outage)
        assertEquals(1228.0, reading.batteryPowerW!!, 0.001)
        assertEquals(0.0, reading.solarPowerW!!, 0.001)
        assertEquals(1168.0, reading.loadPowerW!!, 0.001)
        assertEquals(79.0, reading.soc!!, 0.001)
    }

    @Test
    fun theBatteryRatingsNeededForRuntimeAreFound() {
        val reading = GridAnalyzer.read(outage)
        assertEquals(52.56, reading.batteryVoltage!!, 0.001)
        assertEquals(110.0, reading.batteryCapacityAh!!, 0.001)
        assertEquals(5.60, reading.dischargedKwh!!, 0.001)
    }

    @Test
    fun allThreeGridPhasesAreRead() {
        val reading = GridAnalyzer.read(outage)
        assertEquals(listOf(0.0, 0.0, 0.0), reading.gridPhaseVolts)
    }

    @Test
    fun temperaturesComeFromTheInverterAndPackNotTheBms() {
        val reading = GridAnalyzer.read(outage)
        assertEquals(51.20, reading.inverterTempC!!, 0.001)
        assertEquals(34.00, reading.batteryTempC!!, 0.001)
    }

    @Test
    fun everySignalAgreesOnItsOwn() {
        fun only(vararg keys: String) =
            GridAnalyzer.evaluate(GridAnalyzer.read(outage.filter { it.key in keys }))

        assertTrue(only("GRID_RELAY_ST1")!!.gridDown)
        assertTrue(only("PG_F1")!!.gridDown)
        assertTrue(only("G_V_L1")!!.gridDown)
        assertTrue(only("PG_Pt1", "B_P1")!!.gridDown)
    }

    @Test
    fun theSameSystemBackOnGridReadsAsUp() {
        val restored = outage.map {
            when (it.key) {
                "GRID_RELAY_ST1" -> m("GRID_RELAY_ST1", "Closed", "", "Grid Relay Status")
                "PG_F1" -> m("PG_F1", "49.98", "Hz", "Grid Frequency")
                "G_V_L1" -> m("G_V_L1", "231.20", "V", "Grid Voltage L1")
                "PG_Pt1" -> m("PG_Pt1", "1240", "W", "Total Grid Power")
                "B_ST1" -> m("B_ST1", "Charging", "", "Battery Status")
                "B_P1" -> m("B_P1", "-800", "W", "Battery Power")
                else -> it
            }
        }

        val verdict = GridAnalyzer.evaluate(GridAnalyzer.read(restored))!!
        assertEquals(false, verdict.gridDown)
        assertEquals("grid relay closed", verdict.basis)
    }

    @Test
    fun aStatusStringWeDoNotUnderstandIsNeverGuessedAt() {
        assertNull(GridAnalyzer.parseGridStatus(m("ST_PG1", "Static", "", "Grid Status")))
    }
}
