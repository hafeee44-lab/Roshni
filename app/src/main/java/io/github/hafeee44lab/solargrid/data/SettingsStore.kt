package io.github.hafeee44lab.solargrid.data

import android.content.Context
import android.content.SharedPreferences
import io.github.hafeee44lab.solargrid.domain.AlertSettings

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun from(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Where live readings come from when demo mode is off. */
enum class DataSource {
    /** Solarman's cloud OpenAPI: needs an App ID and secret from Solarman. */
    CLOUD,

    /** The Wi-Fi logger itself, on the home network: no account, no quota. */
    LOCAL;

    companion object {
        fun from(name: String?): DataSource = entries.firstOrNull { it.name == name } ?: CLOUD
    }
}

data class RelaySettings(
    val enabled: Boolean = false,
    val url: String = "",
    val secret: String = ""
) {
    val isUsable: Boolean
        get() = enabled && url.startsWith("http") && secret.length >= 16
}

data class AppSettings(
    val monitoring: Boolean = false,
    val demoMode: Boolean = false,
    val onboarded: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val source: DataSource = DataSource.CLOUD,
    val alerts: AlertSettings = AlertSettings(),
    val relay: RelaySettings = RelaySettings()
) {
    val profile: Profile get() = if (demoMode) Profile.DEMO else Profile.LIVE
}

/** Which data set the app is looking at. Demo data lives in its own files and never mixes in. */
enum class Profile(val suffix: String) { LIVE("live"), DEMO("demo") }

class SettingsStore(context: Context) {

    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val p = prefs
        val defaults = AlertSettings()
        return AppSettings(
            monitoring = p.getBoolean(MONITORING, false),
            demoMode = p.getBoolean(DEMO, false),
            onboarded = p.getBoolean(ONBOARDED, false),
            theme = ThemeMode.from(p.getString(THEME, null)),
            source = DataSource.from(p.getString(SOURCE, null)),
            alerts = AlertSettings(
                alarmOnOutage = p.getBoolean("alarm_on_outage", defaults.alarmOnOutage),
                ringInSilentMode = p.getBoolean("ring_in_silent", defaults.ringInSilentMode),
                ringSeconds = p.getInt("ring_seconds", defaults.ringSeconds),
                notifyRestored = p.getBoolean("notify_restored", defaults.notifyRestored),
                lowBatteryPercent = p.getInt("low_battery", defaults.lowBatteryPercent),
                criticalBatteryPercent = p.getInt("critical_battery", defaults.criticalBatteryPercent),
                criticalAlarm = p.getBoolean("critical_alarm", defaults.criticalAlarm),
                notifyBatteryFull = p.getBoolean("notify_full", defaults.notifyBatteryFull),
                notifyHealth = p.getBoolean("notify_health", defaults.notifyHealth),
                inverterHotC = p.getInt("inverter_hot", defaults.inverterHotC),
                overloadWatts = p.getInt("overload_watts", defaults.overloadWatts),
                headsUp = p.getBoolean("heads_up", defaults.headsUp),
                headsUpLeadMinutes = p.getInt("heads_up_lead", defaults.headsUpLeadMinutes),
                dailySummary = p.getBoolean("daily_summary", defaults.dailySummary),
                summaryMinuteOfDay = p.getInt("summary_minute", defaults.summaryMinuteOfDay),
                quietHours = p.getBoolean("quiet_hours", defaults.quietHours),
                quietStartMinute = p.getInt("quiet_start", defaults.quietStartMinute),
                quietEndMinute = p.getInt("quiet_end", defaults.quietEndMinute),
                batteryReservePercent = p.getInt("battery_reserve", defaults.batteryReservePercent)
            ),
            relay = RelaySettings(
                enabled = p.getBoolean("relay_enabled", false),
                url = p.getString("relay_url", "") ?: "",
                secret = SecureBox.open(p.getString("relay_secret", null)) ?: ""
            )
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        save(transform(load()))
    }

    fun save(settings: AppSettings) {
        val a = settings.alerts
        prefs.edit()
            .putBoolean(MONITORING, settings.monitoring)
            .putBoolean(DEMO, settings.demoMode)
            .putBoolean(ONBOARDED, settings.onboarded)
            .putString(THEME, settings.theme.name)
            .putString(SOURCE, settings.source.name)
            .putBoolean("alarm_on_outage", a.alarmOnOutage)
            .putBoolean("ring_in_silent", a.ringInSilentMode)
            .putInt("ring_seconds", a.ringSeconds)
            .putBoolean("notify_restored", a.notifyRestored)
            .putInt("low_battery", a.lowBatteryPercent)
            .putInt("critical_battery", a.criticalBatteryPercent)
            .putBoolean("critical_alarm", a.criticalAlarm)
            .putBoolean("notify_full", a.notifyBatteryFull)
            .putBoolean("notify_health", a.notifyHealth)
            .putInt("inverter_hot", a.inverterHotC)
            .putInt("overload_watts", a.overloadWatts)
            .putBoolean("heads_up", a.headsUp)
            .putInt("heads_up_lead", a.headsUpLeadMinutes)
            .putBoolean("daily_summary", a.dailySummary)
            .putInt("summary_minute", a.summaryMinuteOfDay)
            .putBoolean("quiet_hours", a.quietHours)
            .putInt("quiet_start", a.quietStartMinute)
            .putInt("quiet_end", a.quietEndMinute)
            .putInt("battery_reserve", a.batteryReservePercent)
            .putBoolean("relay_enabled", settings.relay.enabled)
            .putString("relay_url", settings.relay.url.trim())
            .putString("relay_secret", SecureBox.seal(settings.relay.secret.trim()))
            .apply()
    }

    private companion object {
        const val MONITORING = "monitoring"
        const val DEMO = "demo_mode"
        const val ONBOARDED = "onboarded"
        const val THEME = "theme"
        const val SOURCE = "data_source"
    }
}
