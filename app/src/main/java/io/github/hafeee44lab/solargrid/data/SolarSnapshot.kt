package io.github.hafeee44lab.solargrid.data

import android.content.SharedPreferences
import io.github.hafeee44lab.solargrid.domain.DailyEnergy
import org.json.JSONArray
import org.json.JSONObject

/** The last reading, flattened for the UI, the widget, the tile and the notification. */
data class SolarSnapshot(
    val gridDown: Boolean? = null,
    val gridPowerW: Double? = null,
    val gridVoltage: Double? = null,
    val gridFrequency: Double? = null,
    val gridPhaseVolts: List<Double> = emptyList(),
    val inverterTempC: Double? = null,
    val batteryTempC: Double? = null,
    val batteryPowerW: Double? = null,
    val batteryVoltage: Double? = null,
    val solarPowerW: Double? = null,
    val loadPowerW: Double? = null,
    val soc: Double? = null,
    val runtimeMinutes: Int? = null,
    val runtimeCalibrated: Boolean = false,
    val minutesToFull: Int? = null,
    val capacityWh: Double? = null,
    val basis: String = "",
    val checkedAt: Long = 0L,
    val collectedAt: Long = 0L,
    val outageSince: Long = 0L,
    val inverterAlarm: Boolean = false,
    val today: DailyEnergy = DailyEnergy(),
    val error: String? = null
) {

    val hasData: Boolean
        get() = checkedAt > 0L

    fun ageMillis(now: Long = System.currentTimeMillis()): Long =
        if (checkedAt > 0L) now - checkedAt else Long.MAX_VALUE

    fun inverterAgeMillis(now: Long = System.currentTimeMillis()): Long =
        if (collectedAt > 0L) now - collectedAt else -1L

    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        ageMillis(now) < STALE_AFTER_MS

    fun inverterIsStale(now: Long = System.currentTimeMillis()): Boolean {
        val age = inverterAgeMillis(now)
        return age >= 0L && age > INVERTER_STALE_AFTER_MS
    }

    /** Long enough without an upload that the reading says nothing about the grid now. */
    fun inverterIsSilent(now: Long = System.currentTimeMillis()): Boolean {
        val age = inverterAgeMillis(now)
        return age >= 0L && age > INVERTER_SILENT_AFTER_MS
    }

    fun toJson(): String = SafeJson()
        .put("gridDown", gridDown ?: JSONObject.NULL)
        .put("gridPowerW", gridPowerW ?: JSONObject.NULL)
        .put("gridVoltage", gridVoltage ?: JSONObject.NULL)
        .put("gridFrequency", gridFrequency ?: JSONObject.NULL)
        .put("gridPhaseVolts", JSONArray().also { a -> gridPhaseVolts.forEach { a.put(it) } })
        .put("inverterTempC", inverterTempC ?: JSONObject.NULL)
        .put("batteryTempC", batteryTempC ?: JSONObject.NULL)
        .put("batteryPowerW", batteryPowerW ?: JSONObject.NULL)
        .put("batteryVoltage", batteryVoltage ?: JSONObject.NULL)
        .put("solarPowerW", solarPowerW ?: JSONObject.NULL)
        .put("loadPowerW", loadPowerW ?: JSONObject.NULL)
        .put("soc", soc ?: JSONObject.NULL)
        .put("runtimeMinutes", runtimeMinutes ?: JSONObject.NULL)
        .put("runtimeCalibrated", runtimeCalibrated)
        .put("minutesToFull", minutesToFull ?: JSONObject.NULL)
        .put("capacityWh", capacityWh ?: JSONObject.NULL)
        .put("basis", basis)
        .put("checkedAt", checkedAt)
        .put("collectedAt", collectedAt)
        .put("outageSince", outageSince)
        .put("inverterAlarm", inverterAlarm)
        .put(
            "today",
            SafeJson()
                .put("produced", today.producedKwh ?: JSONObject.NULL)
                .put("consumed", today.consumedKwh ?: JSONObject.NULL)
                .put("bought", today.boughtKwh ?: JSONObject.NULL)
                .put("sold", today.soldKwh ?: JSONObject.NULL)
                .put("charged", today.chargedKwh ?: JSONObject.NULL)
                .put("discharged", today.dischargedKwh ?: JSONObject.NULL)
        )
        .put("error", error ?: JSONObject.NULL)
        .toString()

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString(KEY, toJson()).apply()
    }

    companion object {
        const val STALE_AFTER_MS = 5 * 60_000L
        const val INVERTER_STALE_AFTER_MS = 15 * 60_000L
        const val INVERTER_SILENT_AFTER_MS = 25 * 60_000L

        const val KEY = "last_snapshot"

        fun load(prefs: SharedPreferences): SolarSnapshot =
            fromJson(prefs.getString(KEY, null))

        fun fromJson(raw: String?): SolarSnapshot {
            raw ?: return SolarSnapshot()

            return try {
                val json = JSONObject(raw)
                val today = json.optJSONObject("today")
                SolarSnapshot(
                    gridDown = json.optBooleanOrNull("gridDown"),
                    gridPowerW = json.optDoubleOrNull("gridPowerW"),
                    gridVoltage = json.optDoubleOrNull("gridVoltage"),
                    gridFrequency = json.optDoubleOrNull("gridFrequency"),
                    gridPhaseVolts = json.optJSONArray("gridPhaseVolts")?.let { a ->
                        (0 until a.length()).map { a.getDouble(it) }
                    } ?: emptyList(),
                    inverterTempC = json.optDoubleOrNull("inverterTempC"),
                    batteryTempC = json.optDoubleOrNull("batteryTempC"),
                    batteryPowerW = json.optDoubleOrNull("batteryPowerW"),
                    batteryVoltage = json.optDoubleOrNull("batteryVoltage"),
                    solarPowerW = json.optDoubleOrNull("solarPowerW"),
                    loadPowerW = json.optDoubleOrNull("loadPowerW"),
                    soc = json.optDoubleOrNull("soc"),
                    runtimeMinutes = if (json.isNull("runtimeMinutes")) null else json.optInt("runtimeMinutes"),
                    runtimeCalibrated = json.optBoolean("runtimeCalibrated", false),
                    minutesToFull = if (json.isNull("minutesToFull")) null else json.optInt("minutesToFull"),
                    capacityWh = json.optDoubleOrNull("capacityWh"),
                    basis = json.optString("basis", ""),
                    checkedAt = json.optLong("checkedAt", 0L),
                    collectedAt = json.optLong("collectedAt", 0L),
                    outageSince = json.optLong("outageSince", 0L),
                    inverterAlarm = json.optBoolean("inverterAlarm", false),
                    today = if (today == null) DailyEnergy() else DailyEnergy(
                        producedKwh = today.optDoubleOrNull("produced"),
                        consumedKwh = today.optDoubleOrNull("consumed"),
                        boughtKwh = today.optDoubleOrNull("bought"),
                        soldKwh = today.optDoubleOrNull("sold"),
                        chargedKwh = today.optDoubleOrNull("charged"),
                        dischargedKwh = today.optDoubleOrNull("discharged")
                    ),
                    error = if (json.isNull("error")) null else json.optString("error")
                )
            } catch (e: Exception) {
                SolarSnapshot()
            }
        }

        private fun JSONObject.optDoubleOrNull(name: String): Double? =
            if (!has(name) || isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }

        private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
            if (!has(name) || isNull(name)) null else optBoolean(name)
    }
}

fun describeAge(millis: Long): String = when {
    millis < 0L -> "unknown"
    millis < 45_000L -> "just now"
    millis < 90_000L -> "1 min ago"
    millis < 60 * 60_000L -> "${millis / 60_000L} min ago"
    millis < 2 * 60 * 60_000L -> "1 hour ago"
    millis < 24 * 60 * 60_000L -> "${millis / (60 * 60_000L)} hours ago"
    else -> "over a day ago"
}

/** org.json refuses NaN and Infinity outright; store them as "no reading" instead of failing the save. */
private class SafeJson : JSONObject() {
    override fun put(name: String, value: Any?): JSONObject =
        super.put(name, if (value is Double && !value.isFinite()) NULL else value)
}
