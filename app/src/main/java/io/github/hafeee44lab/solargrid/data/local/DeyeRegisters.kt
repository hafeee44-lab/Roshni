package io.github.hafeee44lab.solargrid.data.local

import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import java.util.Locale

/** A run of holding registers read in one Modbus request. */
data class RegisterBlock(val start: Int, val count: Int, val required: Boolean = true) {
    val end: Int get() = start + count - 1
}

/**
 * Deye hybrid register layouts, mapped onto the same keys Solarman's cloud uses (GRID_RELAY_ST1,
 * G_V_L1, PG_Pt1, B_P1, B_left_cap1 …) so GridAnalyzer reads a local poll exactly as it reads a
 * cloud one.
 *
 * Addresses, scales and signs follow the ha-solarman inverter definitions by David Rapan,
 * https://github.com/davidrapan/ha-solarman, custom_components/solarman/inverter_definitions/:
 *  - deye_p3.yaml ("SG0*LP3, SG0*HP3"), built from Deye's MODBUS RTU V105.1 document and tested
 *    there on a 12K-SG04LP3 and a 25K-SG01HP3 behind an LSW-3 stick;
 *  - deye_hybrid.yaml ("SG0*LP1"), built from Deye's Modbus protocol V118.
 * Where a value has several scales, ha-solarman picks one by device type (common.py,
 * AUTODETECTION_DEYE: LV three-phase is mod 0, HV three-phase mod 1); [DeyeRegisters.metrics]
 * does the same.
 * Register 0 holds the device type, read once to pick the layout.
 *
 * Signs match the cloud: battery power is positive while discharging, grid power is positive
 * while buying.
 */
enum class DeyeLayout(val label: String, val blocks: List<RegisterBlock>) {
    /** SUN-xK-SG04LP3 / SG05LP3 and other 48 V three-phase hybrids. */
    THREE_PHASE_LV("Deye three-phase LV hybrid", THREE_PHASE_BLOCKS),

    /** SUN-xK-SG01HP3 and other high-voltage-battery three-phase hybrids. */
    THREE_PHASE_HV("Deye three-phase HV hybrid", THREE_PHASE_BLOCKS),

    /** SUN-xK-SG0xLP1 single-phase hybrids. */
    SINGLE_PHASE("Deye single-phase hybrid", SINGLE_PHASE_BLOCKS);

    companion object {
        const val DEVICE_TYPE_REGISTER = 0x0000

        /** Register 0's device type, as ha-solarman's const.py AUTODETECTION_DEYE_* reads it. */
        fun fromDeviceType(code: Int): DeyeLayout? = when (code) {
            0x0005, 0x0500 -> THREE_PHASE_LV
            0x0006, 0x0007, 0x0600, 0x0008, 0x0601 -> THREE_PHASE_HV
            0x0003, 0x0300, 0x0103, 0x0104 -> SINGLE_PHASE
            else -> null
        }
    }
}

private val THREE_PHASE_BLOCKS = listOf(
    RegisterBlock(0x0202, 39), // 514–552: today's energy, temperatures, relay status
    RegisterBlock(0x024A, 90), // 586–675: battery, grid, output, load, PV
    RegisterBlock(0x02AF, 22), // 687–708: high words of the 32-bit power values
    RegisterBlock(0x0066, 1, required = false) // 102: battery capacity (Ah), a setting
)

private val SINGLE_PHASE_BLOCKS = listOf(
    RegisterBlock(0x0046, 39), // 70–108: today's energy, grid frequency, temperatures
    RegisterBlock(0x0096, 45), // 150–194: grid, load, battery, PV, grid status
    RegisterBlock(0x00CC, 1, required = false) // 204: battery capacity (Ah), a setting
)

object DeyeRegisters {

    /** Turns raw register values (address → value) into Solarman-style metrics. */
    fun metrics(layout: DeyeLayout, registers: Map<Int, Int>): List<SolarMetric> {
        val r = Reader(registers)
        val out = Builder()
        out.text("LOCAL_MODEL", layout.label, "Inverter (read on the local network)")

        when (layout) {
            DeyeLayout.THREE_PHASE_LV, DeyeLayout.THREE_PHASE_HV -> {
                val hv = layout == DeyeLayout.THREE_PHASE_HV
                // 552 "Device Relay": bit 2 is the grid relay (deye_p3.yaml, item "Grid").
                r.u16(0x0228)?.let { out.text("GRID_RELAY_ST1", if (it and 0b100 != 0) "Closed" else "Open", "Grid Relay Status") }
                out.num("G_V_L1", r.u16(0x0256), 0.1, "V", "Grid Voltage L1")
                out.num("G_V_L2", r.u16(0x0257), 0.1, "V", "Grid Voltage L2")
                out.num("G_V_L3", r.u16(0x0258), 0.1, "V", "Grid Voltage L3")
                out.num("PG_F1", r.u16(0x0261), 0.01, "Hz", "Grid Frequency")
                out.num("PG_Pt1", r.s32(0x0271, 0x02B2), 1.0, "W", "Total Grid Power")
                out.num("B_P1", r.s16(0x024E), if (hv) 10.0 else 1.0, "W", "Battery Power")
                out.num("B_left_cap1", r.u16(0x024C), 1.0, "%", "SoC")
                out.num("B_V1", r.u16(0x024B), if (hv) 0.1 else 0.01, "V", "Battery Voltage")
                out.num("B_T1", r.u16(0x024A)?.minus(1000), 0.1, "℃", "Temperature- Battery")
                out.num("AC_T", r.s16(0x021D)?.minus(1000), 0.1, "℃", "AC Temperature")
                out.num("S_P_T", r.sum(0x02A0, 0x02A1, 0x02A2, 0x02A3), if (hv) 10.0 else 1.0, "W", "Total Solar Power")
                // ha-solarman's "Load Power": inverter output plus whatever the external CT sees.
                out.num("E_Puse_t1", r.s32(0x027C, 0x02B6)?.let { o -> r.s32(0x026B, 0x02C4)?.let { o + it } }, 1.0, "W", "Total Consumption Power")
                out.num("BRC", r.u16(0x0066), 1.0, "Ah", "Battery Rated Capacity")
                out.num("Etdy_cg1", r.u16(0x0202), 0.1, "kWh", "Daily Charging Energy")
                out.num("Etdy_dcg1", r.u16(0x0203), 0.1, "kWh", "Daily Discharging Energy")
                out.num("Etdy_pu1", r.u16(0x0208), 0.1, "kWh", "Daily Energy Purchased")
                out.num("Etdy_gn1", r.u16(0x0209), 0.1, "kWh", "Daily Grid Feed-in")
                out.num("Etdy_use1", r.u16(0x020E), 0.1, "kWh", "Daily Consumption")
                out.num("Etdy_ge1", r.u16(0x0211), 0.1, "kWh", "Daily Production")
            }
            DeyeLayout.SINGLE_PHASE -> {
                // 194 "Grid" (deye_hybrid.yaml): non-zero while the inverter is on the grid.
                r.u16(0x00C2)?.let { out.text("GRID_RELAY_ST1", if (it != 0) "Closed" else "Open", "Grid Relay Status") }
                out.num("G_V_L1", r.u16(0x0096), 0.1, "V", "Grid Voltage L1")
                out.num("PG_F1", r.u16(0x004F), 0.01, "Hz", "Grid Frequency")
                out.num("PG_Pt1", r.s16(0x00A9), 1.0, "W", "Total Grid Power")
                out.num("B_P1", r.s16(0x00BE), 1.0, "W", "Battery Power")
                out.num("B_left_cap1", r.u16(0x00B8), 1.0, "%", "SoC")
                out.num("B_V1", r.u16(0x00B7), 0.01, "V", "Battery Voltage")
                out.num("B_T1", r.u16(0x00B6)?.minus(1000), 0.1, "℃", "Temperature- Battery")
                out.num("AC_T", r.s16(0x005B)?.minus(1000), 0.1, "℃", "AC Temperature")
                out.num("S_P_T", r.sum(0x00BA, 0x00BB, 0x00BC, 0x00BD), 1.0, "W", "Total Solar Power")
                out.num("E_Puse_t1", r.s16(0x00B2), 1.0, "W", "Total Consumption Power")
                out.num("BRC", r.u16(0x00CC), 1.0, "Ah", "Battery Rated Capacity")
                out.num("Etdy_cg1", r.u16(0x0046), 0.1, "kWh", "Daily Charging Energy")
                out.num("Etdy_dcg1", r.u16(0x0047), 0.1, "kWh", "Daily Discharging Energy")
                out.num("Etdy_pu1", r.u16(0x004C), 0.1, "kWh", "Daily Energy Purchased")
                out.num("Etdy_gn1", r.u16(0x004D), 0.1, "kWh", "Daily Grid Feed-in")
                out.num("Etdy_use1", r.u16(0x0054), 0.1, "kWh", "Daily Consumption")
                out.num("Etdy_ge1", r.u16(0x006C), 0.1, "kWh", "Daily Production")
            }
        }
        return out.metrics
    }

    private class Reader(private val registers: Map<Int, Int>) {
        fun u16(address: Int): Int? = registers[address]?.and(0xFFFF)

        fun s16(address: Int): Int? = u16(address)?.let { if (it >= 0x8000) it - 0x10000 else it }

        /** Low word first, as ha-solarman's rule 4 combines them. */
        fun s32(low: Int, high: Int): Int? {
            val lo = u16(low) ?: return null
            val hi = u16(high) ?: return null
            return (hi shl 16) or lo
        }

        fun sum(vararg addresses: Int): Int? {
            val values = addresses.toList().mapNotNull { u16(it) }
            return if (values.isEmpty()) null else values.sum()
        }
    }

    private class Builder {
        val metrics = mutableListOf<SolarMetric>()

        fun text(key: String, value: String, name: String) {
            metrics += SolarMetric(key = key, name = name, value = value, unit = "")
        }

        /** Always formatted with dots: an Urdu phone would otherwise write Eastern Arabic digits. */
        fun num(key: String, raw: Int?, scale: Double, unit: String, name: String) {
            raw ?: return
            val value = raw * scale
            val text = if (scale >= 1.0) "%.0f".format(Locale.US, value) else "%.2f".format(Locale.US, value)
            metrics += SolarMetric(key = key, name = name, value = text, unit = unit)
        }
    }
}
