package io.github.hafeee44lab.solargrid.data.cloud

import com.google.gson.JsonObject
import io.github.hafeee44lab.solargrid.data.DiscoveredDevice
import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.ReadingSource
import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SolisCloud's official API (`https://www.soliscloud.com:13333`), with the KeyID and KeySecret
 * the owner gets from soliscloud.com → API management after asking Solis service to enable it.
 * Every request is signed: `Authorization: API <KeyID>:<base64 HMAC-SHA1(secret, "POST\n<Content-MD5>\n<Content-Type>\n<Date>\n<path>")>`.
 *
 * Reference: SolisCloud Platform API document (V2.0) and the Home Assistant integration
 * github.com/hultenvp/solis-sensor (soliscloud_api.py: signing, inverterList, inverterDetail
 * field names, the kW/W unit strings and the battery sign fix from its issue #158).
 *
 * Solis allows two calls a second; the logger uploads every 5 minutes. Each poll makes one
 * `inverterDetail` call, which carries its own data timestamp, so the usual cadence learning
 * applies. The phone's clock has to be right: Solis refuses requests whose Date is off (HTTP 408).
 */
class SolisSource(
    private val account: BrandAccount,
    private val session: SessionCache,
    private val http: CloudHttp = OkCloudHttp,
    private val clock: () -> Long = System::currentTimeMillis
) : ReadingSource {

    override suspend fun fetch(): FetchResult {
        val (id, sn) = account.device.split("|", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        if (id.isBlank() && sn.isBlank()) return FetchResult.Failed("The saved Solis inverter is unreadable; choose it again in Settings", authProblem = true)
        val body = JsonObject().apply {
            if (id.isNotBlank()) addProperty("id", id)
            if (sn.isNotBlank()) addProperty("sn", sn)
        }.toString()
        session.countCall()
        val reply = signedPost(http, account.fields, DETAIL, body, clock())
        return parseDetail(reply)
    }

    companion object {
        const val API_URL = "api_url"
        const val KEY_ID = "key_id"
        const val KEY_SECRET = "key_secret"
        const val DEFAULT_API_URL = "https://www.soliscloud.com:13333"

        private const val DETAIL = "/v1/api/inverterDetail"
        private const val LIST = "/v1/api/inverterList"
        private const val CONTENT_TYPE = "application/json"

        /** inverterDetail `state`: 1 online, 2 offline, 3 alarm. */
        private const val STATE_OFFLINE = 2
        private const val STATE_ALARM = 3

        fun baseUrl(raw: String): String {
            val url = raw.trim().ifBlank { DEFAULT_API_URL }.removeSuffix("/")
            return "https://" + url.removePrefix("https://").removePrefix("http://")
        }

        fun httpDate(ms: Long): String =
            SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))

        fun contentMd5(body: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body.toByteArray()))

        fun sign(secret: String, md5: String, date: String, path: String): String {
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
            return Base64.getEncoder().encodeToString(mac.doFinal("POST\n$md5\n$CONTENT_TYPE\n$date\n$path".toByteArray()))
        }

        suspend fun signedPost(http: CloudHttp, fields: Map<String, String>, path: String, body: String, now: Long): HttpReply {
            val md5 = contentMd5(body)
            val date = httpDate(now)
            return http.post(
                baseUrl(fields[API_URL].orEmpty()) + path,
                body,
                CONTENT_TYPE,
                mapOf(
                    "Content-MD5" to md5,
                    "Date" to date,
                    "Authorization" to "API ${fields[KEY_ID].orEmpty().trim()}:${sign(fields[KEY_SECRET].orEmpty().trim(), md5, date, path)}"
                )
            )
        }

        private fun httpFailure(reply: HttpReply, what: String): FetchResult.Failed? = when {
            reply.code == 401 || reply.code == 403 -> FetchResult.Failed("Solis refused the KeyID or KeySecret", authProblem = true)
            reply.code == 408 -> FetchResult.Failed("Solis refused the request because this phone's clock is off. Set the time automatically and try again.")
            reply.code == 429 -> FetchResult.Failed("Solis asked us to slow down")
            reply.code !in 200..299 -> FetchResult.Failed("Solis returned HTTP ${reply.code} for $what")
            else -> null
        }

        fun parseDetail(reply: HttpReply): FetchResult {
            httpFailure(reply, "the inverter")?.let { return it }
            val json = jsonObject(reply.body) ?: return FetchResult.Failed("Solis sent a reply Roshni can't read")
            if (json.str("code") != "0") return FetchResult.Failed("Solis rejected the request: ${json.str("msg") ?: json.str("code")}")
            val data = json.obj("data") ?: return FetchResult.LoggerOffline
            val state = data.int("state")
            if (state == STATE_OFFLINE) return FetchResult.LoggerOffline
            val metrics = metrics(data)
            if (metrics.isEmpty()) return FetchResult.Failed("Solis returned no readings for this inverter")
            return FetchResult.Ok(metrics, collectedAt = data.num("dataTimestamp")?.toLong() ?: 0L, inverterAlarm = state == STATE_ALARM)
        }

        /** A power in W whatever unit Solis chose for it (the `<key>Str` field says "W" or "kW"). */
        private fun JsonObject.watts(key: String): Double? = num(key)?.let { if (str("${key}Str") == "kW") it * 1000.0 else it }

        /** An energy in kWh whatever unit Solis chose ("kWh", "MWh", "GWh"). */
        private fun JsonObject.kwh(key: String): Double? = num(key)?.let {
            when (str("${key}Str")) {
                "MWh" -> it * 1000.0
                "GWh" -> it * 1_000_000.0
                "Wh" -> it / 1000.0
                else -> it
            }
        }

        /**
         * Signs follow Roshni (grid positive when buying, battery positive when discharging).
         * Solis reports psum positive when selling, and batteryPower as a magnitude whose
         * direction is the sign of the battery current (negative while discharging).
         */
        internal fun metrics(d: JsonObject): List<SolarMetric> {
            val batteryMagnitude = d.watts("batteryPower")
            val batteryCurrent = d.num("storageBatteryCurrent")
            val battery = if (batteryMagnitude != null && batteryCurrent != null) {
                -Math.copySign(kotlin.math.abs(batteryMagnitude), batteryCurrent)
            } else null
            val solar = (1..32).mapNotNull { d.num("pow$it") }.takeIf { it.isNotEmpty() }?.sum()
            // Unused phases still come as 0 V and 0 A; L1 always stays, so a cut still reads 0 V.
            fun phase(n: Int): Double? = d.num("uAc$n")?.takeIf { n == 1 || it != 0.0 || (d.num("iAc$n") ?: 0.0) != 0.0 }

            return MetricsBuilder()
                .add("G_V_L1", phase(1), "V", "Grid Voltage")
                .add("G_V_L2", phase(2), "V", "Grid Voltage L2")
                .add("G_V_L3", phase(3), "V", "Grid Voltage L3")
                .add("PG_F1", d.num("fac"), "Hz", "Grid Frequency")
                .add("PG_Pt1", d.watts("psum")?.let { -it }, "W", "Total Grid Power")
                .add("B_P1", battery, "W", "Battery Power")
                .add("B_left_cap1", d.num("batteryCapacitySoc"), "%", "SoC")
                .add("B_V1", d.num("storageBatteryVoltage"), "V", "Battery Voltage")
                .add("S_P_T", solar, "W", "Total Solar Power")
                .add("E_Puse_t1", d.watts("familyLoadPower"), "W", "Total Consumption Power")
                .add("AC_T", d.num("inverterTemperature"), "°C", "AC Temperature")
                .add("Etdy_ge1", d.kwh("eToday"), "kWh", "Daily Production")
                .add("Etdy_use1", d.kwh("homeLoadTodayEnergy"), "kWh", "Daily Consumption")
                .add("Etdy_pu1", d.kwh("gridPurchasedTodayEnergy"), "kWh", "Daily Energy Purchased")
                .add("Etdy_gn1", d.kwh("gridSellTodayEnergy"), "kWh", "Daily Grid Feed-in")
                .add("Etdy_cg1", d.kwh("batteryTodayChargeEnergy"), "kWh", "Daily Charging Energy")
                .add("Etdy_dcg1", d.kwh("batteryTodayDischargeEnergy"), "kWh", "Daily Discharging Energy")
                .build()
        }

        /** Lists the account's inverters (first 100), for the setup picker. One call. */
        suspend fun discover(fields: Map<String, String>, http: CloudHttp = OkCloudHttp, now: Long = System.currentTimeMillis()): Result<List<DiscoveredDevice>> {
            val reply = try {
                signedPost(http, fields, LIST, """{"pageNo":1,"pageSize":100}""", now)
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("Couldn't reach Solis: ${e.localizedMessage ?: e.javaClass.simpleName}"))
            }
            httpFailure(reply, "the inverter list")?.let { return Result.failure(IllegalStateException(it.message)) }
            val json = jsonObject(reply.body) ?: return Result.failure(IllegalStateException("Solis sent a reply Roshni can't read"))
            if (json.str("code") != "0") return Result.failure(IllegalStateException("Solis rejected the request: ${json.str("msg") ?: json.str("code")}"))
            val records = json.obj("data")?.obj("page")?.array("records")?.objects().orEmpty()
            return Result.success(records.mapNotNull { r ->
                val sn = r.str("sn") ?: return@mapNotNull null
                DiscoveredDevice(sn, r.str("stationName") ?: r.str("sno") ?: "", r.int("state")?.let { it != STATE_OFFLINE }, "${r.str("id").orEmpty()}|$sn")
            })
        }
    }
}
