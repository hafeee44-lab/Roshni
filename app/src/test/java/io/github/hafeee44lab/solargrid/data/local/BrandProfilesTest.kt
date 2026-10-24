package io.github.hafeee44lab.solargrid.data.local

import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import io.github.hafeee44lab.solargrid.domain.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every bundled profile, fed synthetic register images written the way each maker's document
 * says the inverter writes them (its own scales, word order and signs), and read back through
 * GridAnalyzer. Each brand gets a grid-on and a grid-off image, so the registers that decide the
 * alarm are exercised, and each checks Roshni's signs: grid positive while buying, battery
 * positive while discharging.
 */
class BrandProfilesTest {

    private fun s16(v: Int) = v and 0xFFFF
    private fun lo(v: Int) = v and 0xFFFF
    private fun hi(v: Int) = (v ushr 16) and 0xFFFF

    private fun read(id: String, holding: Map<Int, Int> = emptyMap(), input: Map<Int, Int> = emptyMap()): Reading =
        GridAnalyzer.read(TestProfiles.profile(id).read(TestProfiles.holding(holding) + TestProfiles.input(input)))

    private fun assertGrid(reading: Reading, down: Boolean, basis: String? = null) {
        val verdict = GridAnalyzer.evaluate(reading)
        assertNotNull("no verdict from\n${reading.dump}", verdict)
        assertEquals(reading.dump, down, verdict!!.gridDown)
        basis?.let { assertEquals(it, verdict.basis) }
    }

    /** Importing 1.2 kW, charging the battery at 0.5 kW, SoC 80 %. */
    private fun assertOnGridFlows(r: Reading) {
        assertEquals(r.dump, 1200.0, r.gridPowerW!!, 0.01)
        assertEquals(r.dump, -500.0, r.batteryPowerW!!, 0.01)
        assertEquals(r.dump, 80.0, r.soc!!, 0.01)
    }

    @Test
    fun deyeSinglePhaseTreatsAnyNonZeroGridFlagAsOnGrid() {
        // The old hard-coded map read register 194 as "non-zero = on grid"; keep that.
        assertGrid(read("deye-1p", mapOf(0x00C2 to 2, 0x00B8 to 50, 0x00BE to 0, 0x00A9 to 0)), down = false, basis = "grid relay closed")
    }

    @Test
    fun sofarG3() {
        val on = mapOf(
            0x0404 to 2, 0x041A to 41,
            0x0484 to 5002, 0x0488 to s16(-120), 0x048D to 2301, 0x0498 to 2312, 0x04A3 to 2323, 0x04AF to 70,
            0x05C4 to 25,
            0x0604 to 512, 0x0606 to 50, 0x0607 to 24, 0x0608 to 80,
            0x0684 to 0, 0x0685 to 1234, 0x068C to 0, 0x068D to 512
        )
        val r = read("sofar-g3-3ph", on)
        assertGrid(r, down = false, basis = "grid relay closed")
        assertOnGridFlows(r)
        assertEquals(listOf(230.1, 231.2, 232.3), r.gridPhaseVolts)
        assertEquals(50.02, r.gridFrequency!!, 0.001)
        assertEquals(2500.0, r.solarPowerW!!, 0.01)
        assertEquals(700.0, r.loadPowerW!!, 0.01)
        assertEquals(51.2, r.batteryVoltage!!, 0.001)
        assertEquals(12.34, r.today.producedKwh!!, 0.001)
        assertEquals(5.12, r.today.boughtKwh!!, 0.001)

        val off = on + mapOf(0x0404 to 3, 0x0484 to 0, 0x0488 to 0, 0x048D to 0, 0x0498 to 0, 0x04A3 to 0, 0x0606 to s16(-80))
        val cut = read("sofar-g3-3ph", off)
        assertGrid(cut, down = true, basis = "grid relay open")
        assertEquals(800.0, cut.batteryPowerW!!, 0.01)

        // The single-phase variant shows only L1, so a single-phase house isn't told two phases are missing.
        assertEquals(listOf(230.1), read("sofar-g3-1ph", on).gridPhaseVolts)
        assertGrid(read("sofar-g3-1ph", off), down = true)
    }

    @Test
    fun sofarStatusesItDoesNotKnowFallBackToFrequency() {
        val standbyNoGrid = mapOf(0x0404 to 0, 0x0484 to 0, 0x048D to 0, 0x0608 to 40, 0x0606 to 0, 0x0488 to 0)
        assertGrid(read("sofar-g3-1ph", standbyNoGrid), down = true, basis = "grid frequency 0.0Hz")
    }

    @Test
    fun sofarHydEs() {
        val on = mapOf(
            0x0200 to 2, 0x0206 to 2295, 0x020C to 4998, 0x020D to s16(-50), 0x020E to 5230, 0x0210 to 80,
            0x0212 to 120, 0x0213 to 65, 0x0215 to 0, 0x021A to 820
        )
        val r = read("sofar-hyd-es", on)
        assertGrid(r, down = false)
        assertOnGridFlows(r)
        assertEquals(229.5, r.gridVoltage!!, 0.001)
        assertEquals(52.3, r.batteryVoltage!!, 0.001)
        assertEquals(650.0, r.loadPowerW!!, 0.01)
        assertEquals(8.2, r.today.boughtKwh!!, 0.001)
        assertGrid(read("sofar-hyd-es", on + mapOf(0x0200 to 5, 0x0206 to 0, 0x020C to 0, 0x0212 to 0, 0x020D to 80)), down = true, basis = "grid relay open")
    }

    @Test
    fun solis() {
        val on = mapOf(
            33035 to 154, 33057 to 0, 33058 to 3100, 33073 to 2304, 33074 to 2299, 33075 to 2310,
            33093 to 385, 33094 to 4999, 33116 to 0,
            33130 to hi(-1200), 33131 to lo(-1200), // the meter: negative while importing
            33133 to 521, 33135 to 0, 33139 to 80, 33147 to 900, 33148 to 300, 33149 to 0, 33150 to 500,
            33171 to 64
        )
        val r = read("solis-rhi-3p", input = on)
        assertGrid(r, down = false, basis = "grid relay closed")
        assertOnGridFlows(r)
        assertEquals(listOf(230.4, 229.9, 231.0), r.gridPhaseVolts)
        assertEquals(3100.0, r.solarPowerW!!, 0.01)
        assertEquals(1200.0, r.loadPowerW!!, 0.01)
        assertEquals(38.5, r.inverterTempC!!, 0.001)
        assertEquals(15.4, r.today.producedKwh!!, 0.001)
        assertEquals(6.4, r.today.boughtKwh!!, 0.001)

        val off = on + mapOf(33116 to 0b1, 33073 to 0, 33094 to 0, 33130 to 0, 33131 to 0, 33135 to 1, 33150 to 800)
        val cut = read("solis-rhi-1p", input = off)
        assertGrid(cut, down = true, basis = "grid relay open")
        assertEquals(800.0, cut.batteryPowerW!!, 0.01)
        assertEquals(listOf(0.0), cut.gridPhaseVolts)

        // Another grid fault (overvoltage, bit 1) is not "no grid".
        assertGrid(read("solis-rhi-1p", input = on + mapOf(33116 to 0b10)), down = false)
        // A firmware that writes the battery power signed still gets its sign from 33135.
        assertEquals(-500.0, read("solis-rhi-1p", input = on + mapOf(33149 to hi(-500), 33150 to lo(-500))).batteryPowerW!!, 0.01)
    }

    @Test
    fun kstar() {
        val on = mapOf(
            0x0BD0 to 1500, 0x0BD1 to 700, 0x0BDC to 99,
            0x0BE7 to 2, 0x0BF7 to 5321, 0x0BF9 to s16(-500), 0x0BFA to 800, 0x0BFB to 251,
            0x0C19 to 2310, 0x0C1A to 5001, 0x0C1C to s16(-1200), 0x0C20 to 0, 0x0C24 to 0, 0x0C48 to 1700
        )
        val r = read("kstar-hybrid", input = on)
        assertGrid(r, down = false, basis = "grid relay closed")
        assertOnGridFlows(r)
        assertEquals(2200.0, r.solarPowerW!!, 0.01)
        assertEquals(53.21, r.batteryVoltage!!, 0.001)
        assertEquals(25.1, r.batteryTempC!!, 0.001)
        assertEquals(9.9, r.today.producedKwh!!, 0.001)
        assertGrid(read("kstar-hybrid", input = on + mapOf(0x0BE7 to 1, 0x0C19 to 0, 0x0C1A to 0, 0x0C1C to 0)), down = true, basis = "grid relay open")
        assertGrid(read("kstar-hybrid", input = on + mapOf(0x0BE7 to 4)), down = true)
    }

    @Test
    fun afore() {
        val on = mapOf(
            0x01FB to 2300, 0x01FC to 2310, 0x01FD to 2320, 0x0201 to 5000,
            0x0217 to 0, 0x0218 to 1200, 0x0223 to 0, 0x0224 to 1650, 0x0229 to 0, 0x022A to 950,
            0x03EB to 77,
            0x07D1 to 263, 0x07D2 to 80, 0x07D4 to 528, 0x07D8 to s16(-500),
            0x09C4 to 7, 0x09D3 to 402
        )
        val r = read("afore-hybrid-3p", input = on)
        assertGrid(r, down = false, basis = "grid relay closed")
        assertOnGridFlows(r)
        assertEquals(listOf(230.0, 231.0, 232.0), r.gridPhaseVolts)
        assertEquals(950.0, r.solarPowerW!!, 0.01)
        assertEquals(1650.0, r.loadPowerW!!, 0.01)
        assertEquals(7.7, r.today.boughtKwh!!, 0.001)

        val off = on + mapOf(0x09C4 to 6, 0x01FB to 0, 0x01FC to 0, 0x01FD to 0, 0x0201 to 0, 0x0218 to 0, 0x07D8 to 800)
        assertGrid(read("afore-hybrid-3p", input = off), down = true, basis = "grid relay open")
        assertGrid(read("afore-hybrid-1p", input = off + mapOf(0x09C4 to 4)), down = true)
        assertEquals(listOf(230.0), read("afore-hybrid-1p", input = on).gridPhaseVolts)
    }

    @Test
    fun megarevo() {
        val on = mapOf(
            0x3110 to 2300, 0x3113 to 2300, 0x3116 to 2300, 0x3119 to 5000,
            0x3112 to 400, 0x3115 to 400, 0x3118 to 400,
            0x3122 to 300, 0x3126 to 300, 0x312A to 400,
            0x3140 to 512, 0x3145 to 800, 0x3146 to 250, 0x314A to s16(-500),
            0x316B to 4321, 0x316C to 0
        )
        val r = read("megarevo-r3h", on)
        assertGrid(r, down = false, basis = "grid frequency 50.0Hz")
        assertOnGridFlows(r)
        assertEquals(1000.0, r.loadPowerW!!, 0.01)
        assertEquals(4.32, r.today.boughtKwh!!, 0.001)
        assertGrid(read("megarevo-r3h", on + mapOf(0x3119 to 0, 0x3110 to 0, 0x3113 to 0, 0x3116 to 0)), down = true)
    }

    @Test
    fun renon() {
        val on = mapOf(0x3110 to 2300, 0x3119 to 5000, 0x2284 to 0, 0x2285 to 1200, 0x314A to s16(-500), 0x3145 to 800, 0x2288 to 0, 0x2289 to 640)
        val r = read("renon-ifl", on)
        assertGrid(r, down = false)
        assertOnGridFlows(r)
        assertEquals(640.0, r.loadPowerW!!, 0.01)
        assertGrid(read("renon-ifl", on + mapOf(0x3110 to 0, 0x3119 to 0, 0x2285 to 0)), down = true)
    }

    @Test
    fun invt() {
        val on = mapOf(
            0x3811 to 2300, 0x3812 to 2300, 0x3813 to 2300, 0x381A to 5000,
            0x381D to hi(5000), 0x381E to lo(5000), // inverter output 500 W
            0x3827 to hi(12000), 0x3828 to lo(12000), // grid 1200 W in
            0x3908 to hi(-5000), 0x3909 to lo(-5000), 0x390A to 520, 0x393B to 80,
            0x3B01 to 15000
        ) + (0x3B02..0x3B0C).associateWith { 0 }
        val r = read("invt-hybrid-3p", on)
        assertGrid(r, down = false)
        assertOnGridFlows(r)
        assertEquals(1700.0, r.loadPowerW!!, 0.01)
        assertEquals(1500.0, r.solarPowerW!!, 0.01)
        assertEquals(listOf(230.0), read("invt-hybrid-1p", on).gridPhaseVolts)
        assertGrid(read("invt-hybrid-1p", on + mapOf(0x3811 to 0, 0x381A to 0, 0x3827 to 0, 0x3828 to 0)), down = true)
    }

    @Test
    fun anenji() {
        val on = mapOf(0x00CA to 2300, 0x00CB to 5000, 0x00CC to 1200, 0x00D5 to 700, 0x00D7 to 260, 0x00D9 to s16(-500), 0x00E5 to 80)
        val r = read("anenji-hybrid", on)
        assertGrid(r, down = false)
        assertOnGridFlows(r)
        assertEquals(26.0, r.batteryVoltage!!, 0.001)
        assertGrid(read("anenji-hybrid", on + mapOf(0x00CA to 0, 0x00CB to 0, 0x00CC to 0)), down = true)
    }

    @Test
    fun everyProfileHasABrandTest() {
        val tested = setOf(
            "deye-3p-lv", "deye-3p-hv", "deye-1p", "sofar-g3-3ph", "sofar-g3-1ph", "sofar-hyd-es", "solis-rhi-1p", "solis-rhi-3p",
            "kstar-hybrid", "afore-hybrid-3p", "afore-hybrid-1p", "megarevo-r3h", "renon-ifl", "invt-hybrid-1p", "invt-hybrid-3p", "anenji-hybrid"
        )
        assertEquals(tested, TestProfiles.catalog.profiles.map { it.id }.toSet())
    }

    @Test
    fun everyProfileYieldsWhatTheAlarmNeedsFromItsOwnBlocks() {
        for (p in TestProfiles.catalog.profiles) {
            val image = p.blocks.filter { it.required }.flatMap { b -> (b.start..b.end).map { InverterProfile.key(b.function, it) } }.associateWith { 1 }
            val keys = p.read(image).map { it.key }.toSet()
            assertTrue("${p.id}: ${InverterProfile.ESSENTIAL_KEYS - keys}", keys.containsAll(InverterProfile.ESSENTIAL_KEYS))
            assertTrue("${p.id} has no grid signal", (InverterProfile.GRID_KEYS - "GRID_RELAY_ST1").any { it in keys })
        }
    }

    @Test
    fun everyRequestFitsAModbusFrame() {
        for (p in TestProfiles.catalog.profiles) {
            assertTrue(p.id, p.blocks.all { it.count in 1..125 && it.function in setOf(3, 4) })
            assertTrue(p.id, p.blocks.size <= 12)
        }
    }

    @Test
    fun theDocumentationOnlyProfilesSayWhereTheyCameFrom() {
        for (p in TestProfiles.catalog.profiles) {
            assertTrue(p.id, p.source.contains("ha-solarman"))
        }
        assertEquals(listOf("deye-3p-lv"), TestProfiles.catalog.profiles.filter { it.verified == InverterProfile.Verification.DEVICE }.map { it.id })
    }

    @Test
    fun pickerEntriesAreGroupedByBrandAndSearchable() {
        val entries = TestProfiles.catalog.entries
        val brands = entries.map { it.brand }
        assertEquals(brands.sortedWith(String.CASE_INSENSITIVE_ORDER), brands)
        assertTrue(listOf("Deye", "Sunsynk", "Sol-Ark", "Sofar", "Solis", "KSTAR", "Afore", "Megarevo", "INVT").all { it in brands })
        assertEquals(setOf("deye-1p"), entries.filter { it.matches("sol-ark 12k") }.map { it.profile.id }.toSet())
        assertEquals(setOf("solis-rhi-1p", "solis-rhi-3p"), entries.filter { it.matches("SOLIS") }.map { it.profile.id }.toSet())
        assertTrue(entries.filter { it.matches("three-phase lv") }.any { it.profile.id == "deye-3p-lv" })
        assertFalse(entries.any { it.matches("growatt") })
    }
}
