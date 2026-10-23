package io.github.hafeee44lab.solargrid.data.demo

import io.github.hafeee44lab.solargrid.data.DayEnergy
import io.github.hafeee44lab.solargrid.data.Sample
import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import io.github.hafeee44lab.solargrid.domain.DailyEnergy
import io.github.hafeee44lab.solargrid.domain.OutageLog
import io.github.hafeee44lab.solargrid.domain.OutageRecord
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A believable hybrid system for demo mode: a 6 kW array, a 10 kWh pack, a household load
 * curve and a load-shedding schedule that mostly sticks to its slots. Deterministic per day,
 * so the chart, the history and the "live" reading all agree with each other.
 */
class DemoInverter(
    private val forcedOutage: () -> LongRange? = { null },
    private val changedAt: () -> Long = { 0L }
) {

    data class Point(
        val at: Long,
        val solarW: Double,
        val loadW: Double,
        val gridW: Double,
        val batteryW: Double,
        val soc: Double,
        val gridDown: Boolean,
        val today: DailyEnergy
    )

    private val cache = HashMap<String, List<Point>>()

    fun pointAt(time: Long): Point {
        val dayStart = OutageLog.startOfDay(time)
        val points = day(dayStart)
        val index = ((time - dayStart) / STEP_MS).toInt().coerceIn(0, points.lastIndex)
        return points[index]
    }

    /** The metric list a Deye would send, so demo mode runs through the real analyzer. */
    fun metricsAt(time: Long): List<SolarMetric> {
        val p = pointAt(time)
        val volts = if (p.gridDown) 0.0 else 228.0 + wobble(time, 3.0)
        val inverterTemp = 38.0 + p.loadW / 250.0 + p.solarW / 600.0

        fun m(key: String, value: Double, unit: String, name: String) =
            SolarMetric(key, name, "%.2f".format(java.util.Locale.US, value), unit)

        return listOf(
            SolarMetric("GRID_RELAY_ST1", "Grid Relay Status", if (p.gridDown) "Break" else "Closed", ""),
            m("PG_F1", if (p.gridDown) 0.0 else 50.0 + wobble(time, 0.08), "Hz", "Grid Frequency"),
            m("G_V_L1", volts, "V", "Grid Voltage L1"),
            m("G_V_L2", if (p.gridDown) 0.0 else volts - 1.3, "V", "Grid Voltage L2"),
            m("G_V_L3", if (p.gridDown) 0.0 else volts + 0.8, "V", "Grid Voltage L3"),
            m("PG_Pt1", p.gridW, "W", "Total Grid Power"),
            m("S_P_T", p.solarW, "W", "Total Solar Power"),
            m("E_Puse_t1", p.loadW, "W", "Total Consumption Power"),
            m("B_P1", p.batteryW, "W", "Battery Power"),
            m("B_V1", 49.0 + p.soc * 0.055, "V", "Battery Voltage"),
            m("BRC", CAPACITY_AH, "Ah", "Battery Rated Capacity"),
            m("B_left_cap1", p.soc, "%", "SoC"),
            m("AC_T", inverterTemp, "℃", "AC Temperature"),
            m("B_T1", 29.0 + p.soc / 40.0, "℃", "Temperature- Battery"),
            m("Etdy_ge1", p.today.producedKwh ?: 0.0, "kWh", "Daily Production"),
            m("Etdy_use1", p.today.consumedKwh ?: 0.0, "kWh", "Daily Consumption"),
            m("Etdy_pu1", p.today.boughtKwh ?: 0.0, "kWh", "Daily Energy Purchased"),
            m("Etdy_gn1", p.today.soldKwh ?: 0.0, "kWh", "Daily Grid Feed-in"),
            m("Etdy_cg1", p.today.chargedKwh ?: 0.0, "kWh", "Daily Charging Energy"),
            m("Etdy_dcg1", p.today.dischargedKwh ?: 0.0, "kWh", "Daily Discharging Energy")
        )
    }

    /**
     * Uploads land on 5-minute boundaries like a real logger — except right after the demo
     * scenario changes, which counts as a fresh upload so the change shows up immediately.
     */
    fun collectionTime(time: Long): Long {
        val bucket = time / STEP_MS * STEP_MS
        val changed = changedAt()
        return if (changed in (bucket + 1)..time) changed else bucket
    }

    /** History for the demo database: [days] whole days up to now. */
    fun history(days: Int, now: Long): Triple<List<Sample>, List<OutageRecord>, List<DayEnergy>> =
        history(from = OutageLog.startOfDay(now) - days * OutageLog.DAY_MS, now = now)

    /**
     * Everything the simulated system did after [from] and up to [now]: readings, outages that
     * both began and ended inside the window, and totals for days that finished inside it.
     */
    fun history(from: Long, now: Long): Triple<List<Sample>, List<OutageRecord>, List<DayEnergy>> {
        val samples = mutableListOf<Sample>()
        val outages = mutableListOf<OutageRecord>()
        val totals = mutableListOf<DayEnergy>()
        val today = OutageLog.startOfDay(now)
        val days = ((today - OutageLog.startOfDay(from)) / OutageLog.DAY_MS).toInt().coerceAtLeast(0)

        for (back in days downTo 0) {
            val dayStart = OutageLog.startOfDay(today - back * OutageLog.DAY_MS + 3_600_000L)
            val points = day(dayStart).filter { it.at in (from + 1)..now }
            if (points.isEmpty()) continue

            points.forEach {
                samples += Sample(it.at, it.soc, it.solarW, it.loadW, it.gridW, it.batteryW, it.gridDown)
            }

            var open: Point? = null
            points.forEach { p ->
                if (p.gridDown && open == null) open = p
                if (!p.gridDown && open != null) {
                    outages += OutageRecord(startedAt = open!!.at, endedAt = p.at, startSoc = open!!.soc, endSoc = p.soc)
                    open = null
                }
            }
            open?.let { if (dayStart < today) outages += OutageRecord(startedAt = it.at, endedAt = dayStart + OutageLog.DAY_MS, startSoc = it.soc, endSoc = points.last().soc) }

            if (dayStart < today && points.last().at >= dayStart + OutageLog.DAY_MS - STEP_MS) {
                totals += DayEnergy(dayStart, points.last().today)
            }
        }

        return Triple(samples, outages, totals)
    }

    // ---------------- the simulation ----------------

    private fun day(dayStart: Long): List<Point> {
        val forced = forcedOutage()?.takeIf { it.last >= dayStart && it.first < dayStart + OutageLog.DAY_MS }
        return cache.getOrPut("$dayStart:$forced") { simulate(dayStart, forced) }
    }

    private fun simulate(dayStart: Long, forced: LongRange?): List<Point> {
        val seed = (dayStart / OutageLog.DAY_MS).toInt()
        val cloudiness = 0.15 + 0.5 * unit(seed * 31 + 7)
        val outages = schedule(dayStart, seed)

        var soc = 55.0 + 20.0 * unit(seed * 13 + 1)
        var produced = 0.0
        var consumed = 0.0
        var bought = 0.0
        var sold = 0.0
        var charged = 0.0
        var discharged = 0.0

        val steps = (OutageLog.DAY_MS / STEP_MS).toInt()
        val hours = STEP_MS / 3_600_000.0

        return (0 until steps).map { i ->
            val at = dayStart + i * STEP_MS
            val h = i * STEP_MS / 3_600_000.0
            val down = outages.any { at in it } || (forced != null && forced.first < at + STEP_MS && forced.last >= at)

            val load = load(h, at)
            // Off-grid with a full pack, the inverter throttles the panels to what the house uses.
            val solar = solar(h, cloudiness, at).let { if (down && soc >= 99.5) minOf(it, load) else it }

            var battery: Double
            var grid: Double

            if (down) {
                val reserveFloor = 12.0
                val need = load - solar
                battery = if (soc > reserveFloor) need else min(need, 0.0)
                grid = 0.0
            } else {
                val surplus = solar - load
                when {
                    surplus > 0 && soc < 100.0 -> {
                        val charge = min(surplus, MAX_CHARGE_W)
                        battery = -charge
                        grid = -(surplus - charge)
                    }
                    surplus > 0 -> {
                        battery = 0.0
                        grid = -surplus
                    }
                    else -> {
                        // Cheap trick a real Deye does too: top the pack up from the grid
                        // when it has been run low, so it is ready for the next cut.
                        val topUp = if (soc < 45.0) 900.0 else 0.0
                        battery = -topUp
                        grid = -surplus + topUp
                    }
                }
            }

            val deltaWh = -battery * hours
            soc = (soc + deltaWh / CAPACITY_WH * 100.0).coerceIn(10.0, 100.0)
            if (soc >= 100.0 && battery < 0) battery = 0.0

            produced += solar * hours / 1000.0
            consumed += load * hours / 1000.0
            if (grid > 0) bought += grid * hours / 1000.0 else sold += -grid * hours / 1000.0
            if (battery < 0) charged += -battery * hours / 1000.0 else discharged += battery * hours / 1000.0

            Point(
                at = at,
                solarW = solar,
                loadW = load,
                gridW = grid,
                batteryW = battery,
                soc = soc,
                gridDown = down,
                today = DailyEnergy(produced, consumed, bought, sold, charged, discharged)
            )
        }
    }

    /** Three load-shedding slots that mostly hold, shifting a little from day to day. */
    private fun schedule(dayStart: Long, seed: Int): List<LongRange> {
        val slots = listOf(
            Triple(8.0, 1.5, 0.85),
            Triple(14.0, 1.0, 0.55),
            Triple(20.0, 1.5, 0.9)
        )

        return slots.mapIndexedNotNull { index, (hour, length, chance) ->
            if (unit(seed * 97 + index * 11) > chance) return@mapIndexedNotNull null
            val shift = (unit(seed * 53 + index * 5) - 0.5) * 0.5
            val stretch = (unit(seed * 71 + index * 3) - 0.5) * 0.6
            val start = dayStart + ((hour + shift) * 3_600_000).toLong()
            val end = start + ((length + stretch) * 3_600_000).toLong()
            start until end
        }
    }

    private fun solar(h: Double, cloudiness: Double, at: Long): Double {
        if (h <= 6.0 || h >= 19.0) return 0.0
        // Rounding can put the angle a hair past π at the edges; a negative base would make pow() NaN.
        val shape = sin(PI * (h - 6.0) / 13.0).coerceAtLeast(0.0).pow(1.4)
        val clouds = 1.0 - cloudiness * (0.5 + 0.5 * sin(at / 5_400_000.0) * sin(at / 2_300_000.0 + 1.3))
        return max(0.0, PEAK_SOLAR_W * shape * clouds)
    }

    private fun load(h: Double, at: Long): Double {
        var w = 420.0
        if (h in 6.5..9.0) w += 850.0
        if (h in 12.5..17.0) w += 1300.0 * sin(PI * (h - 12.5) / 4.5)
        if (h in 18.0..23.5) w += 1100.0 + 500.0 * sin(PI * (h - 18.0) / 5.5)
        if (h < 5.0 || h > 23.5) w += 600.0
        return w + 120.0 * sin(at / 2_600_000.0) + 60.0 * sin(at / 1_050_000.0)
    }

    private fun wobble(at: Long, amount: Double): Double =
        amount * (sin(at / 970_000.0) * 0.6 + sin(at / 410_000.0) * 0.4)

    /** A cheap, stable pseudo-random number in 0..1. */
    private fun unit(n: Int): Double {
        var x = n.toLong() * 2654435761L xor 0x5DEECE66DL
        x = (x xor (x ushr 17)) * 0x45D9F3BL
        x = x xor (x ushr 15)
        return ((x and 0xFFFFFF).toDouble() / 0xFFFFFF.toDouble())
    }

    companion object {
        const val STEP_MS = 5 * 60_000L
        private const val PEAK_SOLAR_W = 5600.0
        private const val CAPACITY_AH = 200.0
        private const val CAPACITY_WH = 10_240.0
        private const val MAX_CHARGE_W = 3500.0
        const val DEVICE_SN = "DEMO-0001"
    }
}
