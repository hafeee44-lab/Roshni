package io.github.hafeee44lab.solargrid.domain

import io.github.hafeee44lab.solargrid.data.SolarSnapshot
import io.github.hafeee44lab.solargrid.data.describeAge
import java.util.Locale
import kotlin.math.abs

enum class StateRole { UP, DOWN, NEUTRAL }

enum class BatteryMode { CHARGING, DISCHARGING, IDLE, UNKNOWN }

data class PhaseReading(val label: String, val volts: String, val live: Boolean)

const val PHASE_LIVE_MIN_V = 50.0
const val INVERTER_HOT_C = 65.0

data class FlowSpec(val live: Boolean, val reversed: Boolean = false, val watts: Double = 0.0)

/** Watts for the power-flow picture. Null means "not reported", never "zero". */
data class FlowPowers(
    val solarW: Double?,
    val loadW: Double?,
    val gridW: Double?,
    val batteryW: Double?
)

/**
 * Everything the live screen shows, worked out once and testable without a device. The rule
 * that matters: a stale or failed reading never claims a grid state.
 */
data class HomeUi(
    val heroState: String,
    val heroRole: StateRole,
    val heroSub: String,
    val outageSince: Long?,
    val socText: String,
    val soc: Double?,
    val runtime: String?,
    val runtimeNote: String,
    val emptyAt: Long?,
    val fullIn: String?,
    val fullAt: Long?,
    val socFraction: Float,
    val batteryMode: BatteryMode,
    val voltText: String,
    val voltUnit: String,
    val voltNote: String,
    val frequencyText: String?,
    val phases: List<PhaseReading>,
    val phaseNote: String?,
    val inverterTemp: String?,
    val inverterTempHot: Boolean,
    val batteryTemp: String?,
    val basis: String,
    val batteryFlow: String,
    val houseLoad: String,
    val freshPrimary: String,
    val freshSecondary: String,
    val freshAlert: Boolean,
    val gridLabel: String,
    val solarLabel: String,
    val batteryLabel: String,
    val loadLabel: String,
    val gridLink: FlowSpec,
    val solarLink: FlowSpec,
    val batteryLink: FlowSpec,
    val powers: FlowPowers,
    val today: DailyEnergy,
    val trusted: Boolean,
    val running: Boolean
)

private const val FLOW_THRESHOLD_W = 50.0

private fun phaseNote(volts: List<Double>): String? {
    if (volts.size < 2) return null

    val live = volts.count { it >= PHASE_LIVE_MIN_V }

    return when (live) {
        0 -> "All phases dead"
        volts.size -> null
        else -> {
            val missing = volts.mapIndexedNotNull { i, v ->
                if (v < PHASE_LIVE_MIN_V) "L${i + 1}" else null
            }
            "${missing.joinToString(" and ")} missing — partial supply"
        }
    }
}

fun kw(watts: Double?): String =
    if (watts == null) "—" else String.format(Locale.US, "%.2f kW", watts / 1000.0)

fun buildHomeUi(
    snapshot: SolarSnapshot,
    running: Boolean,
    now: Long = System.currentTimeMillis()
): HomeUi {
    val fresh = snapshot.isFresh(now)
    val failed = snapshot.error != null
    val silent = snapshot.inverterIsSilent(now)
    val trusted = snapshot.hasData && fresh && !failed && !silent

    val role = when {
        !trusted -> StateRole.NEUTRAL
        snapshot.gridDown == true -> StateRole.DOWN
        snapshot.gridDown == false -> StateRole.UP
        else -> StateRole.NEUTRAL
    }

    val hero = when {
        !snapshot.hasData -> "Starting"
        failed || !fresh -> "Last known"
        silent -> "Not reporting"
        snapshot.gridDown == true -> "Mains off"
        snapshot.gridDown == false -> "Mains on"
        else -> "Can't tell"
    }

    val battery = snapshot.batteryPowerW
    val charging = battery != null && battery < -FLOW_THRESHOLD_W
    val discharging = battery != null && battery > FLOW_THRESHOLD_W
    val inverterAge = snapshot.inverterAgeMillis(now)

    val sub = when {
        !snapshot.hasData -> "Waiting for the first reading"
        failed -> snapshot.error ?: "Last check failed"
        !fresh -> "Not confirmed since ${describeAge(snapshot.ageMillis(now))}"
        silent && snapshot.gridDown == true -> "No data since ${describeAge(inverterAge)} — the battery may have run out"
        silent -> "No data since ${describeAge(inverterAge)} — mains may be down if the router isn't on backup"
        snapshot.gridDown == true -> "Running on battery backup"
        snapshot.gridDown == false && charging -> "Battery charging"
        snapshot.gridDown == false && discharging -> "House running off the battery"
        snapshot.gridDown == false -> "Battery on standby"
        else -> "No grid metric in this inverter's response"
    }

    val runtimeMinutes = snapshot.runtimeMinutes?.takeIf { trusted && it > 0 }
    val fullMinutes = snapshot.minutesToFull?.takeIf { trusted && charging }

    val solar = snapshot.solarPowerW
    val grid = snapshot.gridPowerW

    return HomeUi(
        heroState = hero,
        heroRole = role,
        heroSub = sub,
        outageSince = snapshot.outageSince.takeIf { trusted && snapshot.gridDown == true && it > 0L },
        socText = snapshot.soc?.let { String.format(Locale.US, "%.0f", it) } ?: "—",
        soc = snapshot.soc,
        runtime = runtimeMinutes?.let { RuntimeEstimator.describe(it) },
        runtimeNote = when {
            !trusted -> "—"
            snapshot.runtimeMinutes == null -> "load too low to estimate"
            snapshot.runtimeCalibrated -> "at the current load"
            else -> "estimated from the rated capacity"
        },
        emptyAt = runtimeMinutes?.takeIf { discharging }?.let { now + it * 60_000L },
        fullIn = fullMinutes?.let { RuntimeEstimator.describe(it) },
        fullAt = fullMinutes?.let { now + it * 60_000L },
        socFraction = ((snapshot.soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
        batteryMode = when {
            battery == null -> BatteryMode.UNKNOWN
            charging -> BatteryMode.CHARGING
            discharging -> BatteryMode.DISCHARGING
            else -> BatteryMode.IDLE
        },
        voltText = snapshot.gridVoltage?.let { String.format(Locale.US, "%.0f", it) } ?: "—",
        voltUnit = if (snapshot.gridVoltage != null) "V" else "",
        voltNote = when {
            snapshot.gridVoltage == null -> "Not reported by this inverter"
            !fresh -> "Reading is ${describeAge(snapshot.ageMillis(now))}"
            snapshot.gridVoltage < GridAnalyzer.GRID_VOLTAGE_MIN -> "No mains detected"
            else -> "Within normal range"
        },
        frequencyText = snapshot.gridFrequency?.let { String.format(Locale.US, "%.2f Hz", it) },
        phases = snapshot.gridPhaseVolts.mapIndexed { index, volts ->
            PhaseReading(
                label = "L${index + 1}",
                volts = String.format(Locale.US, "%.0f", volts),
                live = volts >= PHASE_LIVE_MIN_V
            )
        },
        phaseNote = phaseNote(snapshot.gridPhaseVolts),
        inverterTemp = snapshot.inverterTempC?.let { String.format(Locale.US, "%.0f°C", it) },
        inverterTempHot = (snapshot.inverterTempC ?: 0.0) >= INVERTER_HOT_C,
        batteryTemp = snapshot.batteryTempC?.let { String.format(Locale.US, "%.0f°C", it) },
        basis = snapshot.basis.ifBlank { "—" },
        batteryFlow = when {
            battery == null -> "unknown"
            discharging -> "${kw(battery)} · discharging"
            charging -> "${kw(abs(battery))} · charging"
            else -> "idle"
        },
        houseLoad = if (trusted) kw(snapshot.loadPowerW) else "—",
        freshPrimary = when {
            !snapshot.hasData -> "Not checked yet"
            failed -> "Last check failed ${describeAge(snapshot.ageMillis(now))}"
            else -> "Checked ${describeAge(snapshot.ageMillis(now))}"
        },
        freshSecondary = when {
            !snapshot.hasData -> "The first reading is on its way"
            failed -> "Backing off until it recovers"
            inverterAge < 0L -> "No upload time reported"
            snapshot.inverterIsStale(now) -> "Inverter last reported ${describeAge(inverterAge)}"
            else -> "Inverter reported ${describeAge(inverterAge)}"
        },
        freshAlert = snapshot.hasData && (failed || !fresh || snapshot.inverterIsStale(now)),
        gridLabel = if (trusted) kw(grid?.let { abs(it) }) else "—",
        solarLabel = if (trusted) kw(solar) else "—",
        batteryLabel = when {
            !trusted || battery == null -> "—"
            discharging -> "${kw(battery)} out"
            charging -> "${kw(abs(battery))} in"
            else -> "idle"
        },
        loadLabel = if (trusted) kw(snapshot.loadPowerW) else "—",
        gridLink = FlowSpec(
            live = trusted && snapshot.gridDown == false && abs(grid ?: 0.0) > FLOW_THRESHOLD_W,
            reversed = (grid ?: 0.0) < -FLOW_THRESHOLD_W,
            watts = if (trusted) abs(grid ?: 0.0) else 0.0
        ),
        solarLink = FlowSpec(
            live = trusted && (solar ?: 0.0) > FLOW_THRESHOLD_W,
            watts = if (trusted) solar ?: 0.0 else 0.0
        ),
        batteryLink = FlowSpec(
            live = trusted && (charging || discharging),
            reversed = discharging,
            watts = if (trusted) abs(battery ?: 0.0) else 0.0
        ),
        powers = if (trusted) {
            FlowPowers(solar, snapshot.loadPowerW, grid, battery)
        } else {
            FlowPowers(null, null, null, null)
        },
        today = snapshot.today,
        trusted = trusted,
        running = running
    )
}
