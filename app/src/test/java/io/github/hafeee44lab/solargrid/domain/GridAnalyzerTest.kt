package io.github.hafeee44lab.solargrid.domain

import io.github.hafeee44lab.solargrid.data.api.SolarMetric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridAnalyzerTest {

    private fun m(key: String, value: String?, unit: String? = null, name: String? = null) =
        SolarMetric(key = key, name = name ?: key, value = value, unit = unit)

    @Test
    fun explicitOffGridStatusIsAnOutage() {
        val reading = GridAnalyzer.read(
            listOf(
                m("GS_A1", "Off-Grid", name = "Grid Status"),
                m("B_left_cap1", "78", "%", "Battery SOC")
            )
        )
        val verdict = GridAnalyzer.evaluate(reading)!!
        assertTrue(verdict.gridDown)
        assertEquals(78.0, reading.soc!!, 0.01)
    }

    @Test
    fun explicitOnGridStatusIsNormal() {
        val reading = GridAnalyzer.read(listOf(m("GS_A1", "On-Grid", name = "Grid Status")))
        assertEquals(false, GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun zeroVoltageStillMeansDown() {
        val reading = GridAnalyzer.read(
            listOf(
                m("G_V_L1", "0.0", "V", "Grid Voltage L1"),
                m("PG_Pt1", "0", "W", "Total Grid Power")
            )
        )
        val verdict = GridAnalyzer.evaluate(reading)!!
        assertTrue(verdict.gridDown)
        assertTrue(verdict.basis.contains("voltage"))
    }

    @Test
    fun oneDeadPhaseIsNotAFullOutage() {
        val reading = GridAnalyzer.read(
            listOf(
                m("G_V_L1", "231.0", "V", "Grid Voltage L1"),
                m("G_V_L2", "0.00", "V", "Grid Voltage L2"),
                m("G_V_L3", "229.5", "V", "Grid Voltage L3")
            )
        )

        assertEquals(3, reading.gridPhaseVolts.size)
        assertEquals(false, GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun allPhasesDeadIsAnOutage() {
        val reading = GridAnalyzer.read(
            listOf(
                m("G_V_L1", "0.00", "V", "Grid Voltage L1"),
                m("G_V_L2", "0.00", "V", "Grid Voltage L2"),
                m("G_V_L3", "0.00", "V", "Grid Voltage L3")
            )
        )
        assertTrue(GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun aSinglePhaseSystemStillWorks() {
        val reading = GridAnalyzer.read(listOf(m("XX_1", "0.00", "V", "Grid Voltage")))
        assertEquals(1, reading.gridPhaseVolts.size)
        assertTrue(GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun realGridVoltageIsTrustedInBothDirections() {
        val up = GridAnalyzer.read(listOf(m("G_V_L1", "231.4", "V", "Grid Voltage L1")))
        assertEquals(false, GridAnalyzer.evaluate(up)!!.gridDown)

        val down = GridAnalyzer.read(listOf(m("G_V_L1", "0.00", "V", "Grid Voltage L1")))
        assertTrue(GridAnalyzer.evaluate(down)!!.gridDown)
    }

    @Test
    fun inverterOutputIsNeverMistakenForTheGrid() {
        val reading = GridAnalyzer.read(
            listOf(
                m("AV1", "229.90", "V", "AC Voltage R/U/A"),
                m("A_Fo1", "50.00", "Hz", "AC Output Frequency R"),
                m("C_V_L1", "229.20", "V", "Load Voltage  L1"),
                m("L_F", "50.00", "Hz", "Load Fequency")
            )
        )
        assertNull(reading.gridVoltage)
        assertNull(reading.gridFrequency)
        assertNull(GridAnalyzer.evaluate(reading))
    }

    @Test
    fun gridFrequencyOutranksVoltage() {
        val down = GridAnalyzer.read(
            listOf(m("G_V_L1", "230", "V", "Grid Voltage L1"), m("PG_F1", "0.00", "Hz", "Grid Frequency"))
        )
        assertTrue(GridAnalyzer.evaluate(down)!!.gridDown)
    }

    @Test
    fun realGridFlowProvesTheGridIsUp() {
        val reading = GridAnalyzer.read(
            listOf(m("PG_Pt1", "1240", "W", "Total Grid Power"), m("B_P1", "600", "W", "Battery Power"))
        )
        assertEquals(false, GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun exportingToGridIsNotAnOutage() {
        val reading = GridAnalyzer.read(
            listOf(
                m("PG_Pt1", "-2400", "W", "Total Grid Power"),
                m("B_P1", "-800", "W", "Battery Power")
            )
        )
        assertEquals(false, GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun zeroGridPowerWithBatteryDischargeIsAnOutage() {
        val reading = GridAnalyzer.read(
            listOf(
                m("PG_Pt1", "0", "W", "Total Grid Power"),
                m("B_P1", "1500", "W", "Battery Power")
            )
        )
        assertTrue(GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun zeroGridPowerWithIdleBatteryIsHonestlyUnknown() {
        val reading = GridAnalyzer.read(
            listOf(
                m("PG_Pt1", "0", "W", "Total Grid Power"),
                m("B_P1", "0", "W", "Battery Power")
            )
        )
        assertNull(GridAnalyzer.evaluate(reading))
    }

    @Test
    fun missingGridMetricsReportUnknownNotOutage() {
        val reading = GridAnalyzer.read(
            listOf(
                m("Etdy_pv1", "12.4", "kWh", "Daily Production"),
                m("B_left_cap1", "64", "%", "Battery SOC")
            )
        )
        assertNull(GridAnalyzer.evaluate(reading))
    }

    @Test
    fun missingBatteryMetricDoesNotSilentlyBlockDetection() {
        val reading = GridAnalyzer.read(listOf(m("PG_Pt1", "0", "W", "Total Grid Power")))
        assertNull(GridAnalyzer.evaluate(reading))
    }

    @Test
    fun socIsPickedByPercentUnitNotByRatedCapacity() {
        val reading = GridAnalyzer.read(
            listOf(
                m("B_Cap1", "200", "Ah", "Battery Capacity"),
                m("B_left_cap1", "41", "%", "Battery SOC")
            )
        )
        assertEquals(41.0, reading.soc!!, 0.01)
    }

    @Test
    fun unknownKeysAreMatchedByHumanReadableName() {
        val reading = GridAnalyzer.read(
            listOf(
                m("XY_99", "0", "V", "Grid Voltage"),
                m("ZZ_01", "55", "%", "State Of Charge")
            )
        )
        assertTrue(GridAnalyzer.evaluate(reading)!!.gridDown)
        assertEquals(55.0, reading.soc!!, 0.01)
    }

    @Test
    fun relayBreakStateIsAnOutage() {
        assertEquals(false, GridAnalyzer.parseGridStatus(m("GRID_RELAY_ST1", "Break")))
        assertEquals(true, GridAnalyzer.parseGridStatus(m("GRID_RELAY_ST1", "Closed")))
        assertNull(GridAnalyzer.parseGridStatus(m("GRID_RELAY_ST1", null)))
    }

    @Test
    fun nullKeysDoNotCrashExtraction() {
        val reading = GridAnalyzer.read(
            listOf(SolarMetric(key = null, name = null, value = null, unit = null))
        )
        assertNull(GridAnalyzer.evaluate(reading))
    }

    @Test
    fun solarPowerIsExtractedByKeyOrName() {
        val byKey = GridAnalyzer.read(listOf(m("S_P_T", "3120", "W", "Total Solar Power")))
        assertEquals(3120.0, byKey.solarPowerW!!, 0.01)

        val byName = GridAnalyzer.read(listOf(m("QQ_7", "2480", "W", "Total PV Power")))
        assertEquals(2480.0, byName.solarPowerW!!, 0.01)
    }

    @Test
    fun missingSolarPowerIsNullNotZero() {
        val reading = GridAnalyzer.read(listOf(m("G_V_L1", "231", "V", "Grid Voltage L1")))
        assertNull(reading.solarPowerW)
    }

    @Test
    fun outOfRangeSocIsClamped() {
        val reading = GridAnalyzer.read(listOf(m("B_left_cap1", "120", "%", "Battery SOC")))
        assertEquals(100.0, reading.soc!!, 0.01)
    }
}
