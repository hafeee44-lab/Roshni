package io.github.hafeee44lab.solargrid.domain

/** How long the battery would last if the house cut back to its usual low load. */
data class Stretch(val loadWatts: Double, val minutes: Int)

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

    /** Readings needed before "your usual low load" means anything: about a day at 5-minute uploads. */
    const val MIN_BASELINE_SAMPLES = 200
    private const val LOW_LOAD_PERCENTILE = 0.2

    /**
     * The household's usual low load: the 20th percentile of recent readings. It is what the
     * house really draws when the heavy appliances are off, taken from its own history rather
     * than a guess.
     */
    fun typicalLowLoad(loads: List<Double?>): Double? {
        val valid = loads.filterNotNull().filter { it.isFinite() && it >= MIN_LOAD_W }.sorted()
        if (valid.size < MIN_BASELINE_SAMPLES) return null
        return valid[(valid.size * LOW_LOAD_PERCENTILE).toInt()]
    }

    /**
     * The runtime at the usual low load, but only when cutting back would buy real time:
     * the low load is clearly below today's draw and adds at least half an hour.
     */
    fun stretch(
        soc: Double?,
        capacityWh: Double?,
        currentLoadW: Double?,
        lowLoadW: Double?,
        reservePercent: Double = RESERVE_PERCENT
    ): Stretch? {
        if (currentLoadW == null || lowLoadW == null || lowLoadW > currentLoadW * 0.75) return null
        val now = estimate(soc, capacityWh, currentLoadW, false, reservePercent) ?: return null
        val low = estimate(soc, capacityWh, lowLoadW, false, reservePercent) ?: return null
        if (low.minutesRemaining - now.minutesRemaining < 30) return null
        return Stretch(lowLoadW, low.minutesRemaining)
    }

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

    fun describe(minutes: Int, p: Phrases = Phrases.English): String {
        if (minutes <= 0) return p.batteryEmpty()
        return p.hoursMinutes(minutes / 60, minutes % 60)
    }
}
