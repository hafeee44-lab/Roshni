package io.github.hafeee44lab.solargrid.domain

data class RuntimeEstimate(
    val minutesRemaining: Int,
    val usableWh: Double,
    val loadWatts: Double,
    val calibrated: Boolean
)

/**
 * Turns "75%" into "about 2h 50m".
 *
 * Nameplate capacity is only a starting point — a pack never delivers its rated watt-hours.
 * So the estimate re-learns the real figure by watching how much energy actually came out
 * of the battery for a given drop in state of charge.
 */
object RuntimeEstimator {

    /** The inverter stops discharging well before empty; this is what it holds back. */
    const val RESERVE_PERCENT = 20.0

    const val MIN_LOAD_W = 40.0
    const val MIN_CALIBRATION_DROP = 10.0
    const val LOAD_SMOOTHING = 0.3

    private const val MIN_PLAUSIBLE_WH = 500.0
    private const val MAX_PLAUSIBLE_WH = 100_000.0

    fun nameplateWh(capacityAh: Double?, voltage: Double?): Double? {
        if (capacityAh == null || voltage == null) return null
        val wh = capacityAh * voltage
        return if (wh in MIN_PLAUSIBLE_WH..MAX_PLAUSIBLE_WH) wh else null
    }

    fun estimate(
        soc: Double?,
        capacityWh: Double?,
        loadWatts: Double?,
        calibrated: Boolean,
        reservePercent: Double = RESERVE_PERCENT
    ): RuntimeEstimate? {
        if (soc == null || capacityWh == null || loadWatts == null) return null
        if (loadWatts < MIN_LOAD_W) return null

        val availablePercent = soc - reservePercent
        if (availablePercent <= 0.0) {
            return RuntimeEstimate(0, 0.0, loadWatts, calibrated)
        }

        val usableWh = capacityWh * (availablePercent / 100.0)
        val hours = usableWh / loadWatts

        return RuntimeEstimate(
            minutesRemaining = (hours * 60).toInt().coerceAtLeast(0),
            usableWh = usableWh,
            loadWatts = loadWatts,
            calibrated = calibrated
        )
    }

    /**
     * How long until the pack is full at the current charge rate. The last few percent go in
     * slower as the BMS tapers the current, so this is deliberately a little pessimistic.
     */
    fun minutesToFull(soc: Double?, capacityWh: Double?, chargeWatts: Double?): Int? {
        if (soc == null || capacityWh == null || chargeWatts == null) return null
        if (chargeWatts < MIN_LOAD_W || soc >= 99.5) return null

        val missingWh = capacityWh * ((100.0 - soc) / 100.0) * TAPER_ALLOWANCE
        return ((missingWh / chargeWatts) * 60).toInt().coerceAtLeast(1)
    }

    private const val TAPER_ALLOWANCE = 1.12

    fun smoothLoad(previous: Double?, sample: Double?): Double? {
        if (sample == null) return previous
        if (previous == null) return sample
        return previous * (1 - LOAD_SMOOTHING) + sample * LOAD_SMOOTHING
    }

    /**
     * Given the state of charge and cumulative discharge counter at the start of a
     * discharge run, and where they are now, work out what the pack is really worth.
     * Returns null until the run is long enough to mean anything.
     */
    fun learnCapacity(
        anchorSoc: Double,
        anchorDischargeKwh: Double,
        currentSoc: Double,
        currentDischargeKwh: Double
    ): Double? {
        val drop = anchorSoc - currentSoc
        val delivered = currentDischargeKwh - anchorDischargeKwh

        if (drop < MIN_CALIBRATION_DROP) return null
        if (delivered <= 0.0) return null

        val wh = (delivered * 1000.0) / (drop / 100.0)
        return if (wh in MIN_PLAUSIBLE_WH..MAX_PLAUSIBLE_WH) wh else null
    }

    fun blend(previous: Double?, observed: Double): Double {
        if (previous == null) return observed
        return (previous * 2 + observed) / 3
    }

    fun describe(minutes: Int): String {
        if (minutes <= 0) return "empty"
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours == 0 -> "${mins}m"
            mins == 0 -> "${hours}h"
            else -> "${hours}h ${mins}m"
        }
    }
}
