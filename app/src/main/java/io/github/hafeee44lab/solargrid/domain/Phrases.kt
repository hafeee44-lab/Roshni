package io.github.hafeee44lab.solargrid.domain

import java.util.Locale

/**
 * Every sentence the domain layer writes: the live status, alerts, events and durations.
 * It lives here rather than in strings.xml so this layer stays plain Kotlin that the unit
 * tests run without a device. Screens use strings.xml as usual.
 */
interface Phrases {
    // Durations and ages
    fun underAMinute(): String
    fun hoursMinutes(hours: Int, minutes: Int): String
    fun batteryEmpty(): String
    fun ageUnknown(): String
    fun justNow(): String
    fun minutesAgo(minutes: Long): String
    fun hoursAgo(hours: Long): String
    fun overADayAgo(): String
    fun ringUntilStopped(): String
    fun ringSeconds(seconds: Int): String
    fun ringMinutes(minutes: Double): String

    // Live status
    fun starting(): String
    fun lastKnown(): String
    fun notReporting(): String
    fun mainsOff(): String
    fun mainsOn(): String
    fun cantTell(): String
    fun waitingForFirstReading(): String
    fun lastCheckFailed(): String
    fun notConfirmedSince(age: String): String
    fun silentWhileDown(age: String): String
    fun silentWhileUp(age: String): String
    fun runningOnBackup(): String
    fun batteryCharging(): String
    fun houseOnBattery(): String
    fun batteryStandby(): String
    fun noGridMetric(): String
    fun loadTooLowToEstimate(): String
    fun atCurrentLoad(): String
    fun fromRatedCapacity(): String
    fun voltageNotReported(): String
    fun readingIs(age: String): String
    fun noMainsDetected(): String
    fun withinNormalRange(): String
    fun allPhasesDead(): String
    fun phasesMissing(phases: List<String>): String
    fun unknown(): String
    fun idle(): String
    fun discharging(kw: String): String
    fun charging(kw: String): String
    fun batteryOut(kw: String): String
    fun batteryIn(kw: String): String
    fun notCheckedYet(): String
    fun checkFailedAgo(age: String): String
    fun checkedAgo(age: String): String
    fun firstReadingOnItsWay(): String
    fun backingOff(): String
    fun noUploadTime(): String
    fun inverterLastReported(age: String): String
    fun inverterReported(age: String): String

    // Alerts and events
    fun reportingAgain(): String
    fun outageTitle(): String
    fun outageBody(soc: String, runtime: String?): String
    fun outageRelay(soc: String, time: String): String
    fun outageEvent(basis: String): String
    fun restoredTitle(): String
    fun restoredBody(soc: String, lasted: String?): String
    fun restoredRelay(soc: String, lasted: String?, time: String): String
    fun restoredEvent(lasted: String?, basis: String): String
    fun monitoringStarted(): String
    fun criticalTitle(soc: String): String
    fun switchOffWhatYouCan(): String
    fun criticalRelay(soc: String, time: String): String
    fun criticalEvent(soc: String): String
    fun lowTitle(soc: String): String
    fun lowRelay(soc: String, time: String): String
    fun lowEvent(soc: String): String
    fun overloadTitle(): String
    fun overloadBody(kw: String): String
    fun overloadEvent(kw: String): String
    fun fullTitle(): String
    fun fullSurplus(kw: String): String
    fun fullCharged(soc: String): String
    fun hotTitle(temp: String): String
    fun hotBody(): String
    fun hotEvent(temp: String): String
    fun faultTitle(): String
    fun faultBody(): String
    fun faultEvent(): String
    fun silentTitle(): String
    fun silentBodyDown(duration: String): String
    fun silentBodyUp(duration: String): String
    fun silentEvent(duration: String): String
    fun headsUpTitle(time: String): String
    fun headsUpBody(seen: Int, observed: Int, usually: String?, cover: String): String
    fun batteryShouldCover(): String
    fun batteryFallsShort(runtime: String): String
    fun summaryTitle(): String
    fun summaryCuts(count: Int, onBattery: String): String
    fun summarySolar(kwh: String): String
    fun summarySelfPowered(percent: String): String
    fun runsOutAround(time: String): String
    fun onlyLeft(soc: String): String

    companion object {
        fun of(locale: Locale): Phrases = if (locale.language == "ur") Urdu else English
    }

    object English : Phrases {
        override fun underAMinute() = "under a minute"
        override fun hoursMinutes(hours: Int, minutes: Int) = when {
            hours == 0 -> "${minutes}m"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
        override fun batteryEmpty() = "empty"
        override fun ageUnknown() = "unknown"
        override fun justNow() = "just now"
        override fun minutesAgo(minutes: Long) = "$minutes min ago"
        override fun hoursAgo(hours: Long) = if (hours == 1L) "1 hour ago" else "$hours hours ago"
        override fun overADayAgo() = "over a day ago"
        override fun ringUntilStopped() = "Until I stop it"
        override fun ringSeconds(seconds: Int) = "$seconds s"
        override fun ringMinutes(minutes: Double) =
            (if (minutes % 1.0 == 0.0) "%.0f" else "%.1f").format(Locale.US, minutes) + " min"

        override fun starting() = "Starting"
        override fun lastKnown() = "Last known"
        override fun notReporting() = "Not reporting"
        override fun mainsOff() = "Mains off"
        override fun mainsOn() = "Mains on"
        override fun cantTell() = "Can't tell"
        override fun waitingForFirstReading() = "Waiting for the first reading"
        override fun lastCheckFailed() = "Last check failed"
        override fun notConfirmedSince(age: String) = "Not confirmed since $age"
        override fun silentWhileDown(age: String) = "No data since $age — the battery may have run out"
        override fun silentWhileUp(age: String) = "No data since $age — mains may be down if the router isn't on backup"
        override fun runningOnBackup() = "Running on battery backup"
        override fun batteryCharging() = "Battery charging"
        override fun houseOnBattery() = "House running off the battery"
        override fun batteryStandby() = "Battery on standby"
        override fun noGridMetric() = "No grid metric in this inverter's response"
        override fun loadTooLowToEstimate() = "load too low to estimate"
        override fun atCurrentLoad() = "at the current load"
        override fun fromRatedCapacity() = "estimated from the rated capacity"
        override fun voltageNotReported() = "Not reported by this inverter"
        override fun readingIs(age: String) = "Reading is $age"
        override fun noMainsDetected() = "No mains detected"
        override fun withinNormalRange() = "Within normal range"
        override fun allPhasesDead() = "All phases dead"
        override fun phasesMissing(phases: List<String>) = "${phases.joinToString(" and ")} missing — partial supply"
        override fun unknown() = "unknown"
        override fun idle() = "idle"
        override fun discharging(kw: String) = "$kw · discharging"
        override fun charging(kw: String) = "$kw · charging"
        override fun batteryOut(kw: String) = "$kw out"
        override fun batteryIn(kw: String) = "$kw in"
        override fun notCheckedYet() = "Not checked yet"
        override fun checkFailedAgo(age: String) = "Last check failed $age"
        override fun checkedAgo(age: String) = "Checked $age"
        override fun firstReadingOnItsWay() = "The first reading is on its way"
        override fun backingOff() = "Backing off until it recovers"
        override fun noUploadTime() = "No upload time reported"
        override fun inverterLastReported(age: String) = "Inverter last reported $age"
        override fun inverterReported(age: String) = "Inverter reported $age"

        override fun reportingAgain() = "Inverter is reporting again"
        override fun outageTitle() = "Mains off — on battery"
        override fun outageBody(soc: String, runtime: String?) =
            "Running on battery at $soc%" + (runtime?.let { " · lasts about $it" } ?: "") + "."
        override fun outageRelay(soc: String, time: String) =
            "⚠️ *Grid outage*\nMains power is down, running on battery.\nBattery: $soc%\n$time"
        override fun outageEvent(basis: String) = "Grid failed — $basis"
        override fun restoredTitle() = "Mains back on"
        override fun restoredBody(soc: String, lasted: String?) =
            "Mains are back" + (lasted?.let { " after $it" } ?: "") + ". Battery at $soc%."
        override fun restoredRelay(soc: String, lasted: String?, time: String) =
            "⚡ *Grid restored*\nMains power is back" + (lasted?.let { " after $it" } ?: "") + ".\nBattery: $soc%\n$time"
        override fun restoredEvent(lasted: String?, basis: String) =
            "Grid restored" + (lasted?.let { " after $it" } ?: "") + " — $basis"
        override fun monitoringStarted() = "Monitoring started — grid online"
        override fun criticalTitle(soc: String) = "Battery critical · $soc%"
        override fun switchOffWhatYouCan() = "Switch off what you can."
        override fun criticalRelay(soc: String, time: String) = "🪫 *Battery critical — $soc%*\nGrid is still down.\n$time"
        override fun criticalEvent(soc: String) = "Battery critical at $soc% during outage"
        override fun lowTitle(soc: String) = "Battery low · $soc%"
        override fun lowRelay(soc: String, time: String) = "🔋 *Battery low — $soc%*\nGrid is still down.\n$time"
        override fun lowEvent(soc: String) = "Battery low at $soc% during outage"
        override fun overloadTitle() = "Heavy load on battery"
        override fun overloadBody(kw: String) = "The house is drawing $kw."
        override fun overloadEvent(kw: String) = "Heavy load on battery: $kw"
        override fun fullTitle() = "Battery full"
        override fun fullSurplus(kw: String) = "Solar has $kw to spare — a good time for heavy appliances."
        override fun fullCharged(soc: String) = "The battery is charged to $soc%."
        override fun hotTitle(temp: String) = "Inverter running hot · $temp°C"
        override fun hotBody() = "Check that its vents are clear and it isn't in direct sun."
        override fun hotEvent(temp: String) = "Inverter at $temp°C"
        override fun faultTitle() = "Inverter reports a fault"
        override fun faultBody() = "Solarman flags an alarm on your inverter. Open the Solarman app for the code."
        override fun faultEvent() = "Solarman reports an inverter alarm"
        override fun silentTitle() = "Inverter has gone quiet"
        override fun silentBodyDown(duration: String) =
            "No data for $duration during the outage. The battery may have run out, or the Wi-Fi is down."
        override fun silentBodyUp(duration: String) =
            "No data for $duration. If your router isn't on backup power, the mains may be down."
        override fun silentEvent(duration: String) = "Inverter silent for $duration"
        override fun headsUpTitle(time: String) = "Outage likely around $time"
        override fun headsUpBody(seen: Int, observed: Int, usually: String?, cover: String) =
            "It has gone off near this time on $seen of the last $observed days" +
                (usually?.let { " for about $it" } ?: "") + "." + cover
        override fun batteryShouldCover() = " Your battery should cover it."
        override fun batteryFallsShort(runtime: String) = " At this load the battery lasts about $runtime — less than usual."
        override fun summaryTitle() = "Today on your system"
        override fun summaryCuts(count: Int, onBattery: String) = when (count) {
            0 -> "No outages"
            1 -> "1 outage · $onBattery on battery"
            else -> "$count outages · $onBattery on battery"
        }
        override fun summarySolar(kwh: String) = "solar $kwh kWh"
        override fun summarySelfPowered(percent: String) = "$percent% self-powered"
        override fun runsOutAround(time: String) = "Power runs out around $time at this load."
        override fun onlyLeft(soc: String) = "Only $soc% left and the grid is still down."
    }

    object Urdu : Phrases {
        private fun hours(n: Int) = if (n == 1) "1 گھنٹہ" else "$n گھنٹے"

        override fun underAMinute() = "ایک منٹ سے کم"
        override fun hoursMinutes(hours: Int, minutes: Int) = when {
            hours == 0 -> "$minutes منٹ"
            minutes == 0 -> hours(hours)
            else -> "${hours(hours)} $minutes منٹ"
        }
        override fun batteryEmpty() = "ختم"
        override fun ageUnknown() = "نامعلوم"
        override fun justNow() = "ابھی ابھی"
        override fun minutesAgo(minutes: Long) = "$minutes منٹ پہلے"
        override fun hoursAgo(hours: Long) = "${hours(hours.toInt())} پہلے"
        override fun overADayAgo() = "ایک دن سے زیادہ پہلے"
        override fun ringUntilStopped() = "جب تک میں بند نہ کروں"
        override fun ringSeconds(seconds: Int) = "$seconds سیکنڈ"
        override fun ringMinutes(minutes: Double) =
            (if (minutes % 1.0 == 0.0) "%.0f" else "%.1f").format(Locale.US, minutes) + " منٹ"

        override fun starting() = "شروع ہو رہا ہے"
        override fun lastKnown() = "آخری معلوم حالت"
        override fun notReporting() = "رپورٹ نہیں آ رہی"
        override fun mainsOff() = "بجلی بند"
        override fun mainsOn() = "بجلی موجود"
        override fun cantTell() = "معلوم نہیں ہو سکا"
        override fun waitingForFirstReading() = "پہلی ریڈنگ کا انتظار ہے"
        override fun lastCheckFailed() = "پچھلی جانچ ناکام رہی"
        override fun notConfirmedSince(age: String) = "آخری تصدیق $age"
        override fun silentWhileDown(age: String) = "آخری ڈیٹا $age آیا — شاید بیٹری ختم ہو گئی ہے"
        override fun silentWhileUp(age: String) = "آخری ڈیٹا $age آیا — اگر راؤٹر بیک اپ پر نہیں تو شاید بجلی گئی ہے"
        override fun runningOnBackup() = "بیٹری بیک اپ پر چل رہا ہے"
        override fun batteryCharging() = "بیٹری چارج ہو رہی ہے"
        override fun houseOnBattery() = "گھر بیٹری پر چل رہا ہے"
        override fun batteryStandby() = "بیٹری تیار ہے"
        override fun noGridMetric() = "اس انورٹر کے جواب میں گرڈ کی معلومات نہیں"
        override fun loadTooLowToEstimate() = "لوڈ اتنا کم ہے کہ اندازہ نہیں ہو سکتا"
        override fun atCurrentLoad() = "موجودہ لوڈ پر"
        override fun fromRatedCapacity() = "بیٹری کی درج شدہ گنجائش سے اندازہ"
        override fun voltageNotReported() = "یہ انورٹر نہیں بتاتا"
        override fun readingIs(age: String) = "ریڈنگ $age کی ہے"
        override fun noMainsDetected() = "بجلی نہیں آ رہی"
        override fun withinNormalRange() = "معمول کے مطابق"
        override fun allPhasesDead() = "تمام فیز بند ہیں"
        override fun phasesMissing(phases: List<String>) = "${phases.joinToString(" اور ")} غائب — بجلی جزوی ہے"
        override fun unknown() = "نامعلوم"
        override fun idle() = "رکی ہوئی"
        override fun discharging(kw: String) = "$kw · خرچ ہو رہی ہے"
        override fun charging(kw: String) = "$kw · چارج ہو رہی ہے"
        override fun batteryOut(kw: String) = "$kw خرچ"
        override fun batteryIn(kw: String) = "$kw چارج"
        override fun notCheckedYet() = "ابھی جانچ نہیں ہوئی"
        override fun checkFailedAgo(age: String) = "پچھلی جانچ $age ناکام رہی"
        override fun checkedAgo(age: String) = "آخری جانچ $age"
        override fun firstReadingOnItsWay() = "پہلی ریڈنگ آنے والی ہے"
        override fun backingOff() = "ٹھیک ہونے تک وقفے وقفے سے کوشش"
        override fun noUploadTime() = "اپ لوڈ کا وقت نہیں بتایا گیا"
        override fun inverterLastReported(age: String) = "انورٹر نے آخری بار $age رپورٹ کیا"
        override fun inverterReported(age: String) = "انورٹر نے $age رپورٹ کیا"

        override fun reportingAgain() = "انورٹر دوبارہ رپورٹ کر رہا ہے"
        override fun outageTitle() = "بجلی چلی گئی — بیٹری پر"
        override fun outageBody(soc: String, runtime: String?) =
            "بیٹری $soc% پر چل رہا ہے" + (runtime?.let { " · تقریباً $it چلے گی" } ?: "") + "۔"
        override fun outageRelay(soc: String, time: String) =
            "⚠️ *بجلی چلی گئی*\nگھر بیٹری پر چل رہا ہے۔\nبیٹری: $soc%\n$time"
        override fun outageEvent(basis: String) = "بجلی گئی — $basis"
        override fun restoredTitle() = "بجلی واپس آ گئی"
        override fun restoredBody(soc: String, lasted: String?) =
            (lasted?.let { "بجلی $it بعد واپس آ گئی" } ?: "بجلی واپس آ گئی") + "۔ بیٹری $soc% پر۔"
        override fun restoredRelay(soc: String, lasted: String?, time: String) =
            "⚡ *بجلی واپس آ گئی*\n" + (lasted?.let { "$it بعد بجلی بحال ہوئی۔" } ?: "بجلی بحال ہو گئی۔") + "\nبیٹری: $soc%\n$time"
        override fun restoredEvent(lasted: String?, basis: String) =
            (lasted?.let { "بجلی $it بعد واپس آئی" } ?: "بجلی واپس آئی") + " — $basis"
        override fun monitoringStarted() = "نگرانی شروع — بجلی موجود ہے"
        override fun criticalTitle(soc: String) = "بیٹری بہت کم · $soc%"
        override fun switchOffWhatYouCan() = "جو کچھ بند کر سکتے ہیں، بند کر دیں۔"
        override fun criticalRelay(soc: String, time: String) = "🪫 *بیٹری بہت کم — $soc%*\nبجلی ابھی تک بند ہے۔\n$time"
        override fun criticalEvent(soc: String) = "بجلی بند ہونے کے دوران بیٹری $soc% — بہت کم"
        override fun lowTitle(soc: String) = "بیٹری کم · $soc%"
        override fun lowRelay(soc: String, time: String) = "🔋 *بیٹری کم — $soc%*\nبجلی ابھی تک بند ہے۔\n$time"
        override fun lowEvent(soc: String) = "بجلی بند ہونے کے دوران بیٹری $soc% — کم"
        override fun overloadTitle() = "بیٹری پر بھاری لوڈ"
        override fun overloadBody(kw: String) = "گھر $kw استعمال کر رہا ہے۔"
        override fun overloadEvent(kw: String) = "بیٹری پر بھاری لوڈ: $kw"
        override fun fullTitle() = "بیٹری فل ہے"
        override fun fullSurplus(kw: String) = "سولر کے پاس $kw فالتو ہے — بھاری آلات چلانے کا اچھا وقت ہے۔"
        override fun fullCharged(soc: String) = "بیٹری $soc% چارج ہے۔"
        override fun hotTitle(temp: String) = "انورٹر گرم ہو رہا ہے · $temp°C"
        override fun hotBody() = "دیکھیں کہ ہوا کے سوراخ کھلے ہوں اور اس پر سیدھی دھوپ نہ پڑے۔"
        override fun hotEvent(temp: String) = "انورٹر $temp°C پر"
        override fun faultTitle() = "انورٹر میں خرابی"
        override fun faultBody() = "سولرمین آپ کے انورٹر پر الارم دکھا رہا ہے۔ کوڈ دیکھنے کے لیے سولرمین ایپ کھولیں۔"
        override fun faultEvent() = "سولرمین نے انورٹر الارم رپورٹ کیا"
        override fun silentTitle() = "انورٹر خاموش ہو گیا ہے"
        override fun silentBodyDown(duration: String) =
            "بجلی بند ہونے کے دوران $duration سے کوئی ڈیٹا نہیں۔ شاید بیٹری ختم ہو گئی ہے یا وائی فائی بند ہے۔"
        override fun silentBodyUp(duration: String) =
            "$duration سے کوئی ڈیٹا نہیں۔ اگر راؤٹر بیک اپ پر نہیں تو شاید بجلی گئی ہے۔"
        override fun silentEvent(duration: String) = "انورٹر $duration سے خاموش"
        override fun headsUpTitle(time: String) = "تقریباً $time بجے بجلی جانے کا امکان"
        override fun headsUpBody(seen: Int, observed: Int, usually: String?, cover: String) =
            "پچھلے $observed دنوں میں سے $seen دن اس وقت کے قریب بجلی گئی" +
                (usually?.let { "، عموماً $it کے لیے" } ?: "") + "۔" + cover
        override fun batteryShouldCover() = " آپ کی بیٹری کافی ہونی چاہیے۔"
        override fun batteryFallsShort(runtime: String) = " اس لوڈ پر بیٹری تقریباً $runtime چلے گی — معمول سے کم۔"
        override fun summaryTitle() = "آج آپ کا سسٹم"
        override fun summaryCuts(count: Int, onBattery: String) = when (count) {
            0 -> "کوئی لوڈ شیڈنگ نہیں"
            else -> "$count بار بجلی گئی · $onBattery بیٹری پر"
        }
        override fun summarySolar(kwh: String) = "سولر $kwh kWh"
        override fun summarySelfPowered(percent: String) = "$percent% اپنی بجلی"
        override fun runsOutAround(time: String) = "اس لوڈ پر بجلی تقریباً $time تک چلے گی۔"
        override fun onlyLeft(soc: String) = "صرف $soc% باقی ہے اور بجلی ابھی تک بند ہے۔"
    }
}
