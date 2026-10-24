package io.github.hafeee44lab.solargrid.data.local

import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Raw register images for the three Deye layouts, run through GridAnalyzer. The three-phase one
 * mirrors the SG05 outage dump in DeyeDumpTest, so a local poll must reach the same verdict and
 * the same numbers as the cloud did.
 */
class DeyeRegistersTest {

    private fun s16(value: Int) = value and 0xFFFF

    /** The SG05 at 22:35 on 25 Aug with the mains out, as registers. */
    private val sg05Outage = mapOf(
        0x0000 to 0x0005,
        0x0202 to 55, // charged 5.5 kWh
        0x0203 to 56, // discharged 5.6 kWh
        0x0208 to 123, // bought 12.3 kWh
        0x0209 to 4, // sold 0.4 kWh
        0x020E to 187, // 18.7 kWh through the load port
        0x0211 to 211, // produced 21.1 kWh
        0x021D to 1512, // 51.2 °C
        0x0228 to 0b0001, // inverter relay closed, grid relay (bit 2) open
        0x024A to 1340, // 34.0 °C
        0x024B to 5256, // 52.56 V
        0x024C to 79,
        0x024E to 1228, // discharging
        0x0256 to 0, 0x0257 to 0, 0x0258 to 0,
        0x0261 to 0,
        0x0271 to 0, 0x02B2 to 0,
        0x027C to 1168, 0x02B6 to 0,
        0x028D to 1168, 0x0293 to 0,
        0x026B to 0, 0x02C4 to 0,
        0x02A0 to 0, 0x02A1 to 0, 0x02A2 to 0, 0x02A3 to 0,
        0x0066 to 110
    )

    private fun read(layout: DeyeLayout, registers: Map<Int, Int>) =
        GridAnalyzer.read(DeyeRegisters.metrics(layout, registers))

    @Test
    fun theOutageReadsTheSameAsFromTheCloud() {
        val reading = read(DeyeLayout.THREE_PHASE_LV, sg05Outage)
        val verdict = GridAnalyzer.evaluate(reading)!!
        assertTrue(verdict.gridDown)
        assertEquals("grid relay open", verdict.basis)

        assertEquals(listOf(0.0, 0.0, 0.0), reading.gridPhaseVolts)
        assertEquals(0.0, reading.gridFrequency!!, 0.001)
        assertEquals(0.0, reading.gridPowerW!!, 0.001)
        assertEquals(1228.0, reading.batteryPowerW!!, 0.001)
        assertEquals(0.0, reading.solarPowerW!!, 0.001)
        assertEquals(1168.0, reading.loadPowerW!!, 0.001)
        assertEquals(79.0, reading.soc!!, 0.001)
        assertEquals(52.56, reading.batteryVoltage!!, 0.001)
        assertEquals(110.0, reading.batteryCapacityAh!!, 0.001)
        assertEquals(51.2, reading.inverterTempC!!, 0.001)
        assertEquals(34.0, reading.batteryTempC!!, 0.001)
        assertEquals(5.6, reading.dischargedKwh!!, 0.001)
    }

    @Test
    fun todaysTotalsAreRead() {
        val today = read(DeyeLayout.THREE_PHASE_LV, sg05Outage).today
        assertEquals(21.1, today.producedKwh!!, 0.001)
        // The day's balance, as Solarman shows it: 12.3 + 21.1 + 5.6 − 5.5 − 0.4.
        assertEquals(33.1, today.consumedKwh!!, 0.001)
        assertEquals(12.3, today.boughtKwh!!, 0.001)
        assertEquals(0.4, today.soldKwh!!, 0.001)
        assertEquals(5.5, today.chargedKwh!!, 0.001)
        assertEquals(5.6, today.dischargedKwh!!, 0.001)
    }

    @Test
    fun backOnGridWithNegativeBatteryPower() {
        val restored = sg05Outage + mapOf(
            0x0228 to 0b0101, // grid relay closed
            0x0256 to 2312, 0x0257 to 2305, 0x0258 to 2298,
            0x0261 to 4998,
            0x0271 to 1240,
            0x024E to s16(-800) // charging
        )
        val reading = read(DeyeLayout.THREE_PHASE_LV, restored)
        val verdict = GridAnalyzer.evaluate(reading)!!
        assertFalse(verdict.gridDown)
        assertEquals("grid relay closed", verdict.basis)
        assertEquals(listOf(231.2, 230.5, 229.8), reading.gridPhaseVolts)
        assertEquals(49.98, reading.gridFrequency!!, 0.001)
        assertEquals(1240.0, reading.gridPowerW!!, 0.001)
        assertEquals(-800.0, reading.batteryPowerW!!, 0.001)
    }

    @Test
    fun thirtyTwoBitPowersCombineLowWordFirst() {
        // Exporting 1.5 kW: -1500 as a 32-bit value, split into its low and high words.
        val exporting = sg05Outage + mapOf(0x0271 to s16(-1500), 0x02B2 to 0xFFFF, 0x028D to (70_000 and 0xFFFF), 0x0293 to (70_000 ushr 16))
        val reading = read(DeyeLayout.THREE_PHASE_LV, exporting)
        assertEquals(-1500.0, reading.gridPowerW!!, 0.001)
        assertEquals(70_000.0, reading.loadPowerW!!, 0.001)
    }

    @Test
    fun loadIgnoresTheExternalCt() {
        val reading = read(DeyeLayout.THREE_PHASE_LV, sg05Outage + mapOf(0x026B to 400))
        assertEquals(1168.0, reading.loadPowerW!!, 0.001)
    }

    /**
     * Read off Hafi's SG05 over the LAN at 06:12 on 24 Oct, grid on. The external CTs carry the
     * grid feed with their phases rotated (their currents are the internal CTs' shifted by one), so
     * their power is wrong; the load must come from the load-port total, which balances against
     * the grid.
     */
    @Test
    fun theLiveSg05ReadingBalances() {
        val live = mapOf(
            0x0000 to 5,
            0x0202 to 2, 0x0203 to 0, 0x0208 to 50, 0x0209 to 0, 0x020E to 43, 0x0211 to 0,
            0x021D to 1480, 0x0228 to 13,
            0x024A to 1270, 0x024B to 5332, 0x024C to 98, 0x024E to s16(-16),
            0x0256 to 2380, 0x0257 to 2355, 0x0258 to 2384, 0x0261 to 5008,
            0x025F to 784, 0x0268 to 204, 0x0269 to 644, 0x026A to 152, 0x026B to 1000,
            0x0271 to 784, 0x027C to s16(-89), 0x028D to 695,
            0x02A0 to 0, 0x02A1 to 6, 0x02A2 to 0, 0x02A3 to 0,
            0x02B2 to 0, 0x02B6 to 0xFFFF, 0x02C4 to 0, 0x0293 to 0,
            0x0066 to 110
        )
        val reading = read(DeyeLayout.THREE_PHASE_LV, live)
        assertEquals(false, GridAnalyzer.evaluate(reading)!!.gridDown)
        assertEquals(listOf(238.0, 235.5, 238.4), reading.gridPhaseVolts)
        assertEquals(50.08, reading.gridFrequency!!, 0.001)
        assertEquals(784.0, reading.gridPowerW!!, 0.001)
        assertEquals(695.0, reading.loadPowerW!!, 0.001)
        assertEquals(-16.0, reading.batteryPowerW!!, 0.001)
        assertEquals(6.0, reading.solarPowerW!!, 0.001)
        assertEquals(98.0, reading.soc!!, 0.001)
        assertEquals(53.32, reading.batteryVoltage!!, 0.001)
        assertEquals(48.0, reading.inverterTempC!!, 0.001)
        assertEquals(27.0, reading.batteryTempC!!, 0.001)
        assertEquals(5.0, reading.today.boughtKwh!!, 0.001)
        // The Solarman app showed 4.8 kWh here; the inverter's load-port counter (526) said 4.3.
        assertEquals(4.8, reading.today.consumedKwh!!, 0.001)

        // Grid in = load + charging + inverter losses, within about a hundred watts.
        val losses = reading.gridPowerW!! + reading.solarPowerW!! - reading.loadPowerW!! + reading.batteryPowerW!!
        assertTrue("losses $losses W", losses in 0.0..120.0)
    }

    @Test
    fun solarIsTheSumOfEveryInput() {
        val reading = read(DeyeLayout.THREE_PHASE_LV, sg05Outage + mapOf(0x02A0 to 1500, 0x02A1 to 1200, 0x02A2 to 0, 0x02A3 to 0))
        assertEquals(2700.0, reading.solarPowerW!!, 0.001)
    }

    @Test
    fun highVoltageModelsUseTheirOwnScales() {
        val reading = read(DeyeLayout.THREE_PHASE_HV, sg05Outage + mapOf(0x024B to 4096, 0x024E to 123, 0x02A0 to 250))
        assertEquals(409.6, reading.batteryVoltage!!, 0.001)
        assertEquals(1230.0, reading.batteryPowerW!!, 0.001)
        assertEquals(2500.0, reading.solarPowerW!!, 0.001)
    }

    @Test
    fun singlePhaseHybridOnGrid() {
        val registers = mapOf(
            0x0046 to 31, 0x0047 to 42, 0x004C to 75, 0x004D to 2, 0x0054 to 96, 0x006C to 88,
            0x004F to 5002,
            0x005B to 1435,
            0x0096 to 2287,
            0x00A9 to 640,
            0x00B2 to 1210,
            0x00B6 to 1290,
            0x00B7 to 5310,
            0x00B8 to 64,
            0x00BA to 977, 0x00BB to 512, 0x00BC to 0, 0x00BD to 0,
            0x00BE to s16(-300),
            0x00C2 to 1,
            0x00CC to 200
        )
        val reading = read(DeyeLayout.SINGLE_PHASE, registers)
        assertFalse(GridAnalyzer.evaluate(reading)!!.gridDown)
        assertEquals(listOf(228.7), reading.gridPhaseVolts)
        assertEquals(50.02, reading.gridFrequency!!, 0.001)
        assertEquals(640.0, reading.gridPowerW!!, 0.001)
        assertEquals(1210.0, reading.loadPowerW!!, 0.001)
        assertEquals(1489.0, reading.solarPowerW!!, 0.001)
        assertEquals(-300.0, reading.batteryPowerW!!, 0.001)
        assertEquals(64.0, reading.soc!!, 0.001)
        assertEquals(53.1, reading.batteryVoltage!!, 0.001)
        assertEquals(43.5, reading.inverterTempC!!, 0.001)
        assertEquals(29.0, reading.batteryTempC!!, 0.001)
        assertEquals(200.0, reading.batteryCapacityAh!!, 0.001)
        assertEquals(8.8, reading.today.producedKwh!!, 0.001)

        val cut = read(DeyeLayout.SINGLE_PHASE, registers + mapOf(0x00C2 to 0, 0x0096 to 0, 0x004F to 0, 0x00A9 to 0))
        assertTrue(GridAnalyzer.evaluate(cut)!!.gridDown)
    }

    @Test
    fun everyRegisterTheMapUsesIsInARequestedBlock() {
        for (layout in DeyeLayout.entries) {
            val covered = layout.blocks.flatMap { it.start..it.end }.associateWith { 1 }
            val keys = DeyeRegisters.metrics(layout, covered).map { it.key }.toSet()
            val expected = setOf(
                "GRID_RELAY_ST1", "G_V_L1", "PG_F1", "PG_Pt1", "B_P1", "B_left_cap1", "B_V1", "B_T1", "AC_T",
                "S_P_T", "E_Puse_t1", "BRC", "Etdy_cg1", "Etdy_dcg1", "Etdy_pu1", "Etdy_gn1", "Etdy_use1", "Etdy_ge1"
            )
            assertTrue("$layout misses ${expected - keys}", keys.containsAll(expected))
        }
    }

    @Test
    fun blocksStayWithinWhatOneModbusRequestCanCarry() {
        for (layout in DeyeLayout.entries) {
            assertTrue(layout.blocks.all { it.count in 1..125 })
        }
    }

    @Test
    fun aMissingBlockLeavesItsValuesOutInsteadOfZero() {
        val keys = DeyeRegisters.metrics(DeyeLayout.THREE_PHASE_LV, sg05Outage - 0x0066).map { it.key }
        assertFalse("BRC" in keys)
        assertNull(read(DeyeLayout.THREE_PHASE_LV, sg05Outage - 0x0066).batteryCapacityAh)
    }

    @Test
    fun numbersAreWrittenWithLatinDigitsEvenOnAnUrduPhone() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ur-PK"))
            val reading = read(DeyeLayout.THREE_PHASE_LV, sg05Outage)
            assertEquals(52.56, reading.batteryVoltage!!, 0.001)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun deviceTypesPickTheLayoutHaSolarmanWould() {
        assertEquals(DeyeLayout.THREE_PHASE_LV, DeyeLayout.fromDeviceType(0x0005))
        assertEquals(DeyeLayout.THREE_PHASE_LV, DeyeLayout.fromDeviceType(0x0500))
        assertEquals(DeyeLayout.THREE_PHASE_HV, DeyeLayout.fromDeviceType(0x0006))
        assertEquals(DeyeLayout.SINGLE_PHASE, DeyeLayout.fromDeviceType(0x0003))
        assertNull("string inverter", DeyeLayout.fromDeviceType(0x0002))
        assertNull("microinverter", DeyeLayout.fromDeviceType(0x0004))
    }
}
