package io.github.hafeee44lab.solargrid.domain

import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import kotlin.math.abs

data class Reading(
    val gridConnected: Boolean?,
    val gridVoltage: Double?,
    val gridPhaseVolts: List<Double>,
    val gridFrequency: Double?,
    val gridPowerW: Double?,
    val batteryPowerW: Double?,
    val solarPowerW: Double?,
    val loadPowerW: Double?,
    val batteryVoltage: Double?,
    val inverterTempC: Double?,
    val batteryTempC: Double?,
    val batteryCapacityAh: Double?,
    val dischargedKwh: Double?,
    val soc: Double?,
    val dump: String,
    val today: DailyEnergy = DailyEnergy()
)

/** The inverter's own day counters. They reset at midnight inverter time. */
data class DailyEnergy(
    val producedKwh: Double? = null,
    val consumedKwh: Double? = null,
    val boughtKwh: Double? = null,
    val soldKwh: Double? = null,
    val chargedKwh: Double? = null,
    val dischargedKwh: Double? = null
) {
    val hasAny: Boolean
        get() = listOf(producedKwh, consumedKwh, boughtKwh, soldKwh, chargedKwh, dischargedKwh)
            .any { it != null }

    /** Share of today's consumption that did not come from the grid. */
    val selfSufficiency: Double?
        get() {
            val used = consumedKwh ?: return null
            val bought = boughtKwh ?: return null
            if (used <= 0.05) return null
            return (1.0 - bought / used).coerceIn(0.0, 1.0)
        }
}

enum class Confidence { DEFINITIVE, INFERRED }

data class Verdict(
    val gridDown: Boolean,
    val basis: String,
    val confidence: Confidence = Confidence.DEFINITIVE
)

object GridAnalyzer {

    const val GRID_VOLTAGE_MIN = 50.0
    const val GRID_FREQUENCY_MIN = 40.0
    const val GRID_POWER_EPSILON_W = 25.0
    const val BATTERY_FLOW_MIN_W = 50.0

    fun read(metrics: List<SolarMetric>): Reading {
        val relay = metrics.match(
            exactKeys = listOf("GRID_RELAY_ST1", "GS_A1"),
            requireAll = listOf("grid", "relay")
        )

        val phaseVolts = listOf("G_V_L1", "G_V_L2", "G_V_L3").mapNotNull { key ->
            metrics.firstOrNull { it.key.equals(key, true) }.asDouble()
        }.ifEmpty {
            listOfNotNull(
                metrics.match(
                    exactKeys = emptyList(),
                    requireAll = listOf("grid", "voltage"),
                    forbid = listOf("load", "output", "bms", "smartload", "battery")
                ).asDouble()
            )
        }

        // On three phases the mains are only truly gone when every leg is dead. Judging by
        // L1 alone would call a single blown phase a full outage.
        val gridVoltage = phaseVolts.maxOrNull()

        val gridFrequency = metrics.match(
            exactKeys = listOf("PG_F1"),
            requireAll = listOf("grid", "frequency"),
            forbid = listOf("load", "output")
        ).asDouble()

        val gridPower = metrics.match(
            exactKeys = listOf("PG_Pt1"),
            requireAll = listOf("grid", "power"),
            forbid = listOf("reactive", "load", "external", "internal", "phase")
        ).asDouble()

        val batteryPower = metrics.match(
            exactKeys = listOf("B_P1"),
            requireAll = listOf("battery", "power"),
            forbid = listOf("reactive", "apparent")
        ).asDouble()

        val solarPower = (
            metrics.match(
                exactKeys = listOf("S_P_T"),
                requireAll = listOf("solar", "power"),
                forbid = listOf("daily", "smartload")
            ) ?: metrics.match(
                exactKeys = emptyList(),
                requireAll = listOf("total", "pv", "power"),
                forbid = listOf("daily", "reactive")
            )
            ).asDouble()

        val loadPower = metrics.match(
            exactKeys = listOf("E_Puse_t1"),
            requireAll = listOf("consumption", "power"),
            forbid = listOf("apparent", "daily")
        ).asDouble()

        val batteryVoltage = metrics.match(
            exactKeys = listOf("B_V1"),
            requireAll = listOf("battery", "voltage"),
            forbid = listOf("bms", "charge", "discharge")
        ).asDouble()

        val batteryCapacityAh = metrics.match(
            exactKeys = listOf("BRC"),
            requireAll = listOf("battery", "rated", "capacity")
        ).asDouble()

        val inverterTemp = metrics.match(
            exactKeys = listOf("AC_T"),
            requireAll = listOf("ac", "temperature"),
            forbid = listOf("bms", "battery")
        ).asDouble()

        val batteryTemp = metrics.match(
            exactKeys = listOf("B_T1"),
            requireAll = listOf("temperature", "battery"),
            forbid = listOf("bms")
        ).asDouble()

        val dischargedKwh = metrics.match(
            exactKeys = listOf("Etdy_dcg1"),
            requireAll = listOf("daily", "discharging", "energy")
        ).asDouble()

        val soc = (
            metrics.match(
                exactKeys = listOf("B_left_cap1", "BMS_SOC"),
                requireAll = listOf("soc"),
                forbid = listOf("bms")
            ) ?: metrics.match(
                exactKeys = emptyList(),
                requireAll = listOf("state", "charge")
            ) ?: metrics.firstOrNull { it.unit == "%" && it.name?.contains("soc", true) == true }
            ).asDouble()

        val today = DailyEnergy(
            producedKwh = metrics.match(
                exactKeys = listOf("Etdy_ge1", "Etdy_ge0"),
                requireAll = listOf("daily", "production"),
                forbid = listOf("active", "reactive")
            ).asDouble(),
            consumedKwh = metrics.match(
                exactKeys = listOf("Etdy_use1"),
                requireAll = listOf("daily", "consumption")
            ).asDouble(),
            boughtKwh = metrics.match(
                exactKeys = listOf("Etdy_pu1"),
                requireAll = listOf("daily", "energy", "purchased")
            ).asDouble() ?: metrics.match(
                exactKeys = emptyList(),
                requireAll = listOf("daily", "energy", "bought")
            ).asDouble(),
            soldKwh = metrics.match(
                exactKeys = listOf("Etdy_gn1"),
                requireAll = listOf("daily", "grid", "feed")
            ).asDouble() ?: metrics.match(
                exactKeys = emptyList(),
                requireAll = listOf("daily", "energy", "sold")
            ).asDouble(),
            chargedKwh = metrics.match(
                exactKeys = listOf("Etdy_cg1"),
                requireAll = listOf("daily", "charging", "energy")
            ).asDouble(),
            dischargedKwh = dischargedKwh
        )

        return Reading(
            gridConnected = parseGridStatus(relay),
            gridVoltage = gridVoltage,
            gridPhaseVolts = phaseVolts,
            gridFrequency = gridFrequency,
            gridPowerW = gridPower,
            batteryPowerW = batteryPower,
            solarPowerW = solarPower,
            loadPowerW = loadPower,
            batteryVoltage = batteryVoltage,
            inverterTempC = inverterTemp,
            batteryTempC = batteryTemp,
            batteryCapacityAh = batteryCapacityAh,
            dischargedKwh = dischargedKwh,
            soc = soc?.coerceIn(0.0, 100.0),
            dump = metrics.joinToString("\n") {
                "${it.key} = ${it.value ?: "-"} ${it.unit ?: ""}   (${it.name ?: "?"})"
            },
            today = today
        )
    }

    fun evaluate(reading: Reading): Verdict? {
        reading.gridConnected?.let {
            return Verdict(!it, if (it) "grid relay closed" else "grid relay open")
        }

        reading.gridFrequency?.let {
            return Verdict(it < GRID_FREQUENCY_MIN, "grid frequency ${"%.1f".format(it)}Hz")
        }

        reading.gridVoltage?.let {
            return Verdict(it < GRID_VOLTAGE_MIN, "grid voltage ${"%.0f".format(it)}V")
        }

        val grid = reading.gridPowerW
        val battery = reading.batteryPowerW

        if (grid != null && abs(grid) >= GRID_POWER_EPSILON_W) {
            return Verdict(false, "grid carrying ${"%.2f".format(abs(grid) / 1000.0)}kW")
        }

        if (grid != null && battery != null && battery > BATTERY_FLOW_MIN_W) {
            return Verdict(
                gridDown = true,
                basis = "no grid flow, battery supplying the house",
                confidence = Confidence.INFERRED
            )
        }

        return null
    }

    fun parseGridStatus(metric: SolarMetric?): Boolean? {
        val raw = metric?.value?.trim()?.lowercase() ?: return null
        return when {
            raw.contains("break") || raw.contains("open") -> false
            raw.contains("off-grid") || raw.contains("off grid") -> false
            raw.contains("island") || raw.contains("disconnect") -> false
            raw.contains("closed") || raw.contains("close") -> true
            raw.contains("on-grid") || raw.contains("on grid") -> true
            raw.contains("connect") -> true
            raw == "0" -> false
            raw == "1" -> true
            else -> null
        }
    }

    /**
     * Key codes differ between inverters and are easy to guess wrong, so an exact code is
     * only trusted when it is one we have actually seen. Otherwise we match the
     * human-readable name, which has to contain every required word and none of the
     * forbidden ones — that is what keeps "AC Output Frequency" from being mistaken for
     * "Grid Frequency".
     */
    private fun List<SolarMetric>.match(
        exactKeys: List<String>,
        requireAll: List<String> = emptyList(),
        forbid: List<String> = emptyList()
    ): SolarMetric? {
        for (key in exactKeys) {
            firstOrNull { it.key.equals(key, true) && !it.value.isNullOrBlank() }?.let { return it }
        }

        if (requireAll.isEmpty()) return null

        return firstOrNull { metric ->
            val name = metric.name?.lowercase() ?: return@firstOrNull false
            !metric.value.isNullOrBlank() &&
                requireAll.all { name.contains(it) } &&
                forbid.none { name.contains(it) }
        }
    }

    /** Loggers do send "NaN" and "Infinity" now and then; neither is a reading. */
    private fun SolarMetric?.asDouble(): Double? = this?.value?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
}
