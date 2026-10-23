package io.github.hafeee44lab.solargrid.data

import android.content.Context
import android.content.SharedPreferences
import io.github.hafeee44lab.solargrid.domain.MonitorState
import io.github.hafeee44lab.solargrid.domain.OutageState
import org.json.JSONObject

/** The monitor's working memory for one [Profile]: last reading, state machine, learned models. */
class StateStore(context: Context, profile: Profile) {

    val prefs: SharedPreferences =
        context.getSharedPreferences("state_${profile.suffix}", Context.MODE_PRIVATE)

    var snapshot: SolarSnapshot
        get() = SolarSnapshot.load(prefs)
        set(value) = value.save(prefs)

    var monitor: MonitorState
        get() = decode(prefs.getString(MONITOR, null))
        set(value) {
            prefs.edit().putString(MONITOR, encode(value)).apply()
        }

    var dump: String
        get() = prefs.getString(DUMP, "") ?: ""
        set(value) {
            prefs.edit().putString(DUMP, value).apply()
        }

    var errorStreak: Int
        get() = prefs.getInt(ERROR_STREAK, 0)
        set(value) {
            prefs.edit().putInt(ERROR_STREAK, value).apply()
        }

    var uploadIntervalMs: Long?
        get() = prefs.getLong(UPLOAD_INTERVAL, 0L).takeIf { it > 0L }
        set(value) {
            prefs.edit().putLong(UPLOAD_INTERVAL, value ?: 0L).apply()
        }

    var lastCollectionMs: Long
        get() = prefs.getLong(LAST_COLLECTION, 0L)
        set(value) {
            prefs.edit().putLong(LAST_COLLECTION, value).apply()
        }

    var smoothedLoadW: Double?
        get() = prefs.getFloat(SMOOTHED_LOAD, 0f).toDouble().takeIf { it > 0.0 }
        set(value) {
            prefs.edit().putFloat(SMOOTHED_LOAD, (value ?: 0.0).toFloat()).apply()
        }

    var learnedCapacityWh: Double?
        get() = prefs.getFloat(CAPACITY, 0f).toDouble().takeIf { it > 0.0 }
        set(value) {
            prefs.edit().putFloat(CAPACITY, (value ?: 0.0).toFloat()).apply()
        }

    /** Start of the discharge run being used to learn the real capacity. */
    var calibrationAnchor: Pair<Double, Double>?
        get() {
            val soc = prefs.getFloat(CAL_SOC, -1f).toDouble()
            val kwh = prefs.getFloat(CAL_KWH, -1f).toDouble()
            return if (soc < 0 || kwh < 0) null else soc to kwh
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(CAL_SOC).remove(CAL_KWH).apply()
            } else {
                prefs.edit().putFloat(CAL_SOC, value.first.toFloat()).putFloat(CAL_KWH, value.second.toFloat()).apply()
            }
        }

    var token: Pair<String, Long>?
        get() {
            val token = prefs.getString(TOKEN, null) ?: return null
            return token to prefs.getLong(TOKEN_EXPIRY, 0L)
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(TOKEN).remove(TOKEN_EXPIRY).apply()
            } else {
                prefs.edit().putString(TOKEN, value.first).putLong(TOKEN_EXPIRY, value.second).apply()
            }
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(s: MonitorState): String = JSONObject()
        .put("committedDown", s.outage.committedDown ?: JSONObject.NULL)
        .put("pendingDown", s.outage.pendingDown ?: JSONObject.NULL)
        .put("pendingCount", s.outage.pendingCount)
        .put("lastUploadAt", s.lastUploadAt)
        .put("stateChangedAt", s.stateChangedAt)
        .put("outageSince", s.outageSince)
        .put("lowSent", s.lowSent)
        .put("criticalSent", s.criticalSent)
        .put("overloadSent", s.overloadSent)
        .put("fullSent", s.fullSent)
        .put("hotSent", s.hotSent)
        .put("faultSent", s.faultSent)
        .put("silentSent", s.silentSent)
        .put("headsUpFor", s.headsUpFor)
        .put("summaryDay", s.summaryDay)
        .toString()

    private fun decode(raw: String?): MonitorState {
        raw ?: return MonitorState()
        return try {
            val j = JSONObject(raw)
            MonitorState(
                outage = OutageState(
                    committedDown = if (j.isNull("committedDown")) null else j.getBoolean("committedDown"),
                    pendingDown = if (j.isNull("pendingDown")) null else j.getBoolean("pendingDown"),
                    pendingCount = j.optInt("pendingCount", 0)
                ),
                lastUploadAt = j.optLong("lastUploadAt"),
                stateChangedAt = j.optLong("stateChangedAt"),
                outageSince = j.optLong("outageSince"),
                lowSent = j.optBoolean("lowSent"),
                criticalSent = j.optBoolean("criticalSent"),
                overloadSent = j.optBoolean("overloadSent"),
                fullSent = j.optBoolean("fullSent"),
                hotSent = j.optBoolean("hotSent"),
                faultSent = j.optBoolean("faultSent"),
                silentSent = j.optBoolean("silentSent"),
                headsUpFor = j.optLong("headsUpFor"),
                summaryDay = j.optLong("summaryDay")
            )
        } catch (e: Exception) {
            MonitorState()
        }
    }

    private companion object {
        const val MONITOR = "monitor_state"
        const val DUMP = "raw_dump"
        const val ERROR_STREAK = "error_streak"
        const val UPLOAD_INTERVAL = "upload_interval_ms"
        const val LAST_COLLECTION = "last_collection_ms"
        const val SMOOTHED_LOAD = "smoothed_load_w"
        const val CAPACITY = "capacity_wh"
        const val CAL_SOC = "cal_anchor_soc"
        const val CAL_KWH = "cal_anchor_kwh"
        const val TOKEN = "access_token"
        const val TOKEN_EXPIRY = "access_token_expiry"
    }
}
