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

        fun describeRing(seconds: Int, p: Phrases = Phrases.English): String = when {
            seconds <= 0 -> p.ringUntilStopped()
            seconds < 60 -> p.ringSeconds(seconds)
            else -> p.ringMinutes(seconds / 60.0)
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
