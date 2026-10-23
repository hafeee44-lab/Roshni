package io.github.hafeee44lab.solargrid.domain

/** Everything the user can tune about what wakes them up. Pure data so the planner stays testable. */
data class AlertSettings(
    val alarmOnOutage: Boolean = true,
    val ringInSilentMode: Boolean = false,
    /** How long the alarm rings before stopping by itself. 0 rings until it's stopped or the mains return. */
    val ringSeconds: Int = 90,
    val notifyRestored: Boolean = true,
    val lowBatteryPercent: Int = 20,
    val criticalBatteryPercent: Int = 10,
    val criticalAlarm: Boolean = true,
    val notifyBatteryFull: Boolean = false,
    val notifyHealth: Boolean = true,
    val inverterHotC: Int = 65,
    val overloadWatts: Int = 0,
    val headsUp: Boolean = true,
    val headsUpLeadMinutes: Int = 15,
    val dailySummary: Boolean = false,
    val summaryMinuteOfDay: Int = 21 * 60,
    val quietHours: Boolean = false,
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 7 * 60,
    val batteryReservePercent: Int = 20
) {

    companion object {
        /** The choices offered in Settings, in order; 0 means "until I stop it". */
        val RING_CHOICES = listOf(30, 60, 90, 120, 180, 300, 600, 0)

        fun describeRing(seconds: Int): String = when {
            seconds <= 0 -> "Until I stop it"
            seconds < 60 -> "$seconds\u00A0s"
            seconds % 60 == 0 -> "${seconds / 60}\u00A0min"
            else -> "%.1f\u00A0min".format(seconds / 60.0)
        }
    }

    /** Quiet hours may wrap past midnight, which is the usual case (23:00 → 07:00). */
    fun isQuiet(minuteOfDay: Int): Boolean {
        if (!quietHours || quietStartMinute == quietEndMinute) return false
        return if (quietStartMinute < quietEndMinute) {
            minuteOfDay in quietStartMinute until quietEndMinute
        } else {
            minuteOfDay >= quietStartMinute || minuteOfDay < quietEndMinute
        }
    }
}
