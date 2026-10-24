package io.github.hafeee44lab.solargrid.data.cloud

import com.google.gson.JsonObject
import io.github.hafeee44lab.solargrid.data.DiscoveredDevice
import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.ReadingSource
import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Growatt's cloud (ShineServer), two ways in:
 *
 * - **API token**, Growatt's official OpenAPI V1 (`https://openapi.growatt.com/v1/`, header
 *   `token`). The owner makes the token in ShineServer under Settings → Account management →
 *   API key, or in ShinePhone under Me → (account) → API token. It reads SPH (type 5) and MIN /
 *   MIN-TL-XH / MOD-XH (type 7) inverters. Docs: https://www.showdoc.com.cn/262556420217021/0;
 *   client: growattServer's open_api_v1 (github.com/indykoning/PyPi_GrowattServer).
 * - **ShinePhone password**, the unofficial API the ShinePhone app uses, as growattServer's
 *   base_api.py and Home Assistant's growatt_server integration call it. It also reads SPF
 *   off-grid / storage inverters, which the token API can't.
 *
 * Both are rate limited per endpoint: V1 answers error 10012 to a second call within 5 minutes,
 * and ShinePhone locks accounts that sign in too often (`msg: "507"`). So every poll makes exactly
 * one data call, the ShinePhone session cookie is kept and reused, and [CloudBrand.GROWATT] polls
 * no faster than every 5 minutes. Growatt's Wi-Fi sticks upload every 5 minutes by default.
 */
class GrowattSource(
    private val account: BrandAccount,
    private val session: SessionCache,
    private val http: CloudHttp = OkCloudHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault()
) : ReadingSource {

    private val server = baseUrl(account.field(SERVER))
    private val device = Device.parse(account.device)

    override suspend fun fetch(): FetchResult {
        val device = device ?: return FetchResult.Failed("The saved Growatt inverter is unreadable; choose it again in Settings", authProblem = true)
        return if (device.v1) fetchV1(device) else fetchClassic(device)
    }

    // ---------------- OpenAPI V1 (token) ----------------

    private suspend fun fetchV1(device: Device): FetchResult {
        val (path, field) = when (device.type) {
            TYPE_SPH -> "device/mix/mix_last_data" to "mix_sn"
            else -> "device/tlx/tlx_last_data" to "tlx_sn"
        }
        session.countCall()
        val reply = http.post(
            "${server}v1/$path",
            form(field to device.sn),
            FORM,
            mapOf("token" to account.field(TOKEN))
        )
        return parseV1(reply, device.type, zone)
    }

    // ---------------- ShinePhone (password) ----------------

    private suspend fun fetchClassic(device: Device): FetchResult {
        var cookie = when (val s = classicCookie(forceLogin = false)) {
            is Login.Ok -> s.cookie
            is Login.Failed -> return s.result
        }
        var reply = classicData(device, cookie)
        if (isSignedOut(reply)) {
            cookie = when (val s = classicCookie(forceLogin = true)) {
                is Login.Ok -> s.cookie
                is Login.Failed -> return s.result
            }
            reply = classicData(device, cookie)
            if (isSignedOut(reply)) return FetchResult.Failed("Growatt keeps signing Roshni out; check the username and password", authProblem = true)
        }
        return parseClassic(reply, device.type)
    }

    private suspend fun classicData(device: Device, cookie: String): HttpReply {
        session.countCall()
        val headers = mapOf("Cookie" to cookie, "User-Agent" to USER_AGENT)
        return when (device.type) {
            TYPE_MIX -> http.post("${server}newMixApi.do?op=getSystemStatus_KW&mixId=${enc(device.sn)}&plantId=${enc(device.plant)}", "", FORM, headers)
            TYPE_STORAGE -> http.get("${server}newStorageAPI.do?op=getStorageParams_sacolar&storageId=${enc(device.sn)}", headers)
            else -> http.get("${server}newTlxApi.do?op=getTlxDetailData&id=${enc(device.sn)}", headers)
        }
    }

    private sealed interface Login {
        data class Ok(val cookie: String) : Login
        data class Failed(val result: FetchResult.Failed) : Login
    }

    private suspend fun classicCookie(forceLogin: Boolean): Login {
        val cached = session.session
        if (!forceLogin && cached != null && clock() < cached.second) return Login.Ok(cached.first)

        session.countCall()
        return when (val result = classicLogin(http, server, account.field(USER), account.field(PASSWORD_HASH))) {
            is ClassicLogin.Ok -> {
                session.session = result.cookie to (clock() + SESSION_MS)
                Login.Ok(result.cookie)
            }
            is ClassicLogin.Failed -> {
                session.session = null
                Login.Failed(result.result)
            }
        }
    }

    /** Which inverter to read: `v1|type|sn` or `classic|type|sn|plantId`. */
    data class Device(val v1: Boolean, val type: String, val sn: String, val plant: String = "") {
        fun encode(): String = listOf(if (v1) "v1" else "classic", type, sn, plant).joinToString("|")

        companion object {
            fun parse(raw: String): Device? {
                val parts = raw.split("|")
                if (parts.size < 3 || parts[2].isBlank()) return null
                return Device(parts[0] == "v1", parts[1], parts[2], parts.getOrElse(3) { "" })
            }
        }
    }

    sealed interface ClassicLogin {
        data class Ok(val cookie: String, val plants: List<Pair<String, String>>) : ClassicLogin
        data class Failed(val result: FetchResult.Failed) : ClassicLogin
    }

    companion object {
        const val MODE = "mode"
        const val MODE_TOKEN = "token"
        const val MODE_PASSWORD = "password"
        const val SERVER = "server"
        const val TOKEN = "token"
        const val USER = "user"
        const val PASSWORD_HASH = "password_hash"

        const val DEFAULT_SERVER = "openapi.growatt.com"

        const val TYPE_SPH = "sph"
        const val TYPE_MIN = "min"
        const val TYPE_MIX = "mix"
        const val TYPE_STORAGE = "storage"
        const val TYPE_TLX = "tlx"

        /** V1 device types (growattServer open_api_v1 DeviceType). */
        private val V1_TYPES = mapOf(5 to TYPE_SPH, 7 to TYPE_MIN)

        /** ShinePhone device types Roshni can read: hybrids and storage that report grid and battery. */
        private val CLASSIC_TYPES = setOf(TYPE_MIX, TYPE_STORAGE, TYPE_TLX)

        private const val FORM = "application/x-www-form-urlencoded"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 13; io.github.hafeee44lab.solargrid)"

        /** How long a ShinePhone session cookie is trusted before signing in again. */
        private const val SESSION_MS = 6 * 60 * 60_000L

        /** "No privilege access", which is also what a revoked or mistyped token gets. */
        const val V1_NO_PRIVILEGE = 10011

        /** "Access frequency limitation of 5 minutes/time". */
        const val V1_RATE_LIMITED = 10012

        /** ShinePhone's login answers: 502 wrong username or password, 507 locked for signing in too often. */
        private const val CLASSIC_BAD_LOGIN = "502"
        private const val CLASSIC_LOCKED = "507"

        fun baseUrl(server: String): String {
            val host = server.trim().ifBlank { DEFAULT_SERVER }.removeSuffix("/")
            return (if (host.startsWith("http")) host else "https://$host") + "/"
        }

        /**
         * ShinePhone's password hash: MD5 hex with every '0' at an even index turned into 'c'
         * (growattServer base_api.hash_password). Only this is stored, never the password.
         */
        fun hashPassword(password: String): String {
            val chars = MessageDigest.getInstance("MD5").digest(password.toByteArray())
                .joinToString("") { "%02x".format(it) }.toCharArray()
            for (i in chars.indices step 2) if (chars[i] == '0') chars[i] = 'c'
            return String(chars)
        }

        /** Maps one V1 `*_last_data` reply. */
        fun parseV1(reply: HttpReply, type: String, zone: TimeZone): FetchResult {
            if (reply.code == 401 || reply.code == 403) return FetchResult.Failed("Growatt refused the API token", authProblem = true)
            if (reply.code == 429) return FetchResult.Failed("Growatt asked us to slow down")
            if (reply.code !in 200..299) return FetchResult.Failed("Growatt returned HTTP ${reply.code}")
            val json = jsonObject(reply.body) ?: return FetchResult.Failed("Growatt sent a reply Roshni can't read")
            when (json.int("error_code")) {
                0 -> Unit
                V1_NO_PRIVILEGE -> return FetchResult.Failed("Growatt refused the API token: ${json.str("error_msg") ?: "no privilege"}", authProblem = true)
                V1_RATE_LIMITED -> return FetchResult.Failed("Growatt allows one reading every 5 minutes; trying again later")
                else -> return FetchResult.Failed("Growatt rejected the request: ${json.str("error_msg") ?: json.str("error_code")}")
            }
            val data = json.obj("data") ?: return FetchResult.LoggerOffline
            val metrics = if (type == TYPE_SPH) mixMetrics(data, kw = false) else tlxMetrics(data)
            if (metrics.isEmpty()) return FetchResult.Failed("Growatt returned no readings for this inverter")
            return FetchResult.Ok(metrics, collectedAt = parseTime(data.str("time"), zone) ?: 0L, inverterAlarm = false)
        }

        /** Maps one ShinePhone status reply. */
        fun parseClassic(reply: HttpReply, type: String): FetchResult {
            if (reply.code !in 200..299) return FetchResult.Failed("Growatt returned HTTP ${reply.code}")
            val json = jsonObject(reply.body) ?: return FetchResult.Failed("Growatt sent a reply Roshni can't read")
            if (json.str("msg") == CLASSIC_LOCKED || json.obj("back")?.str("msg") == CLASSIC_LOCKED) return lockedOut()
            val metrics = when (type) {
                TYPE_MIX -> json.obj("obj")?.let { mixMetrics(it, kw = true) }
                TYPE_STORAGE -> (json.obj("storageDetailBean") ?: json.obj("obj")?.obj("storageDetailBean"))?.let(::storageMetrics)
                else -> (json.obj("data") ?: json.obj("obj"))?.let(::tlxMetrics)
            }
            if (metrics == null) return FetchResult.LoggerOffline
            if (metrics.isEmpty()) return FetchResult.Failed("Growatt returned no readings for this inverter")
            // The ShinePhone status calls carry no timestamp; the reading is as fresh as Growatt's last upload.
            return FetchResult.Ok(metrics, collectedAt = 0L, inverterAlarm = false)
        }

        private fun lockedOut() = FetchResult.Failed(
            "Growatt has paused this account for signing in too often. Checks pause for an hour.",
            quotaExhausted = true
        )

        /** Signed out shows up as a redirect, the HTML login page, or a JSON `result: -1`/`back.success: false`. */
        fun isSignedOut(reply: HttpReply): Boolean {
            if (reply.code in 300..399 || reply.code == 401 || reply.code == 403) return true
            if (reply.code !in 200..299) return false
            val json = jsonObject(reply.body) ?: return reply.body.contains("<html", ignoreCase = true)
            return json.int("result") == -1 || json.str("msg") == "501"
        }

        /**
         * SPH via V1 (`mix_last_data`) or MIX via ShinePhone (`getSystemStatus_KW`). Field names
         * from Home Assistant growatt_server sensor/sph.py (V1, watts) and sensor/mix.py plus
         * growattServer mix_system_status (ShinePhone, kilowatts).
         */
        internal fun mixMetrics(d: JsonObject, kw: Boolean): List<SolarMetric> {
            val scale = if (kw) 1000.0 else 1.0
            val charge = d.num("pcharge1", "chargePower")?.times(scale)
            val discharge = d.num("pdischarge1", "pdisCharge1", "pDischarge1")?.times(scale)
            val import = d.num("pacToUserR", "pacToUserTotal", "pactouser")?.times(scale)
            val export = d.num("pacToGridTotal", "pactogrid")?.times(scale)
            val solar = d.num("ppv")?.times(scale)
            val battery = if (charge != null || discharge != null) (discharge ?: 0.0) - (charge ?: 0.0) else null
            val grid = if (import != null || export != null) (import ?: 0.0) - (export ?: 0.0) else null
            val load = d.num("pacToLocalLoad", "pLocalLoad")?.times(scale)
                ?: if (solar != null && battery != null && grid != null) (solar + battery + grid).coerceAtLeast(0.0) else null

            return MetricsBuilder()
                .add("G_V_L1", d.num("vac1", "vAc1"), "V", "Grid Voltage")
                .add("PG_F1", d.num("fac", "fAc"), "Hz", "Grid Frequency")
                .add("PG_Pt1", grid, "W", "Total Grid Power")
                .add("B_P1", battery, "W", "Battery Power")
                .add("B_left_cap1", d.num("bmsSOC", "SOC", "soc"), "%", "SoC")
                .add("B_V1", d.num("vbat", "vBat"), "V", "Battery Voltage")
                .add("S_P_T", solar, "W", "Total Solar Power")
                .add("E_Puse_t1", load, "W", "Total Consumption Power")
                .add("AC_T", d.num("temp1"), "°C", "AC Temperature")
                .add("Etdy_ge1", d.num("epvtoday", "epvToday"), "kWh", "Daily Production")
                .add("Etdy_use1", d.num("elocalLoadToday"), "kWh", "Daily Consumption")
                .add("Etdy_pu1", d.num("etoUserToday"), "kWh", "Daily Energy Purchased")
                .add("Etdy_gn1", d.num("etoGridToday"), "kWh", "Daily Grid Feed-in")
                .add("Etdy_cg1", d.num("echarge1Today", "eBatChargeToday"), "kWh", "Daily Charging Energy")
                .add("Etdy_dcg1", d.num("edischarge1Today", "eBatDisChargeToday"), "kWh", "Daily Discharging Energy")
                .build()
        }

        /** MIN / MIN-TL-XH / MOD-XH, the "tlx" family. Field names from Home Assistant sensor/tlx.py (watts). */
        internal fun tlxMetrics(d: JsonObject): List<SolarMetric> {
            val charge = sum(d.num("bdc1ChargePower"), d.num("bdc2ChargePower"))
            val discharge = sum(d.num("bdc1DischargePower"), d.num("bdc2DischargePower"))
            val import = d.num("pacToUserTotal")
            val export = d.num("pacToGridTotal")
            return MetricsBuilder()
                .add("G_V_L1", d.num("vacr", "vacrs", "vac1"), "V", "Grid Voltage")
                .add("G_V_L2", d.num("vacs", "vac2"), "V", "Grid Voltage L2")
                .add("G_V_L3", d.num("vact", "vac3"), "V", "Grid Voltage L3")
                .add("PG_F1", d.num("fac"), "Hz", "Grid Frequency")
                .add("PG_Pt1", if (import != null || export != null) (import ?: 0.0) - (export ?: 0.0) else null, "W", "Total Grid Power")
                .add("B_P1", if (charge != null || discharge != null) (discharge ?: 0.0) - (charge ?: 0.0) else null, "W", "Battery Power")
                .add("B_left_cap1", d.num("bmsSoc", "soc", "SOC"), "%", "SoC")
                .add("B_V1", d.num("vbat", "bdc1Vbat"), "V", "Battery Voltage")
                .add("S_P_T", d.num("ppv"), "W", "Total Solar Power")
                .add("E_Puse_t1", d.num("pacToLocalLoad"), "W", "Total Consumption Power")
                .add("AC_T", d.num("temp1"), "°C", "AC Temperature")
                .add("Etdy_ge1", d.num("epvToday"), "kWh", "Daily Production")
                .add("Etdy_use1", d.num("elocalLoadToday"), "kWh", "Daily Consumption")
                .add("Etdy_pu1", d.num("etoUserToday"), "kWh", "Daily Energy Purchased")
                .add("Etdy_gn1", d.num("etoGridToday"), "kWh", "Daily Grid Feed-in")
                .add("Etdy_cg1", d.num("echargeToday"), "kWh", "Daily Charging Energy")
                .add("Etdy_dcg1", d.num("edischargeToday"), "kWh", "Daily Discharging Energy")
                .build()
        }

        /**
         * SPF off-grid / storage inverters (`storageDetailBean`). Field names from Home Assistant
         * sensor/storage.py. These report the mains as an AC input, which reads 0 V in a cut.
         * There is no discharge power field, so battery power is worked out from the flows.
         */
        internal fun storageMetrics(d: JsonObject): List<SolarMetric> {
            val solar = d.num("ppv")?.let { it + (d.num("ppv2") ?: 0.0) }
            val acIn = d.num("pAcInPut")
            val output = d.num("outPutPower")
            val charge = d.num("pCharge")
            val discharge = d.num("pDischarge", "pBat")
                ?: if (output != null && solar != null && acIn != null) (output + (charge ?: 0.0) - solar - acIn).coerceAtLeast(0.0) else null
            val battery = if (charge != null || discharge != null) (discharge ?: 0.0) - (charge ?: 0.0) else null
            return MetricsBuilder()
                .add("G_V_L1", d.num("vGrid"), "V", "Grid Voltage")
                .add("PG_F1", d.num("freqGrid"), "Hz", "Grid Frequency")
                .add("PG_Pt1", acIn, "W", "Total Grid Power")
                .add("B_P1", battery, "W", "Battery Power")
                .add("B_left_cap1", d.num("capacity"), "%", "SoC")
                .add("B_V1", d.num("vBat"), "V", "Battery Voltage")
                .add("S_P_T", solar, "W", "Total Solar Power")
                .add("E_Puse_t1", output, "W", "Total Consumption Power")
                .add("Etdy_ge1", d.num("epvToday"), "kWh", "Daily Production")
                .add("Etdy_pu1", d.num("eToUserToday"), "kWh", "Daily Energy Purchased")
                .add("Etdy_cg1", d.num("eChargeToday", "eacChargeToday"), "kWh", "Daily Charging Energy")
                .add("Etdy_dcg1", d.num("eBatDisChargeToday"), "kWh", "Daily Discharging Energy")
                .build()
        }

        private fun sum(a: Double?, b: Double?): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)

        /** Growatt stamps readings in the plant's local time, which for a home user is the phone's. */
        internal fun parseTime(raw: String?, zone: TimeZone): Long? {
            if (raw.isNullOrBlank()) return null
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = zone }.parse(raw.trim())?.time
            } catch (e: Exception) {
                null
            }
        }

        suspend fun classicLogin(http: CloudHttp, server: String, user: String, passwordHash: String): ClassicLogin {
            val reply = http.post(
                "${server}newTwoLoginAPI.do",
                form("userName" to user, "password" to passwordHash),
                FORM,
                mapOf("User-Agent" to USER_AGENT)
            )
            if (reply.code !in 200..299) return ClassicLogin.Failed(FetchResult.Failed("Growatt sign-in failed (HTTP ${reply.code})", authProblem = true))
            val back = jsonObject(reply.body)?.obj("back")
                ?: return ClassicLogin.Failed(FetchResult.Failed("Growatt sent a sign-in reply Roshni can't read", authProblem = true))
            if (back.get("success")?.asBoolean != true) {
                return ClassicLogin.Failed(
                    when (back.str("msg")) {
                        CLASSIC_LOCKED -> lockedOut()
                        CLASSIC_BAD_LOGIN -> FetchResult.Failed("Growatt sign-in rejected: wrong username or password", authProblem = true)
                        else -> FetchResult.Failed("Growatt sign-in rejected${back.str("msg")?.let { ": $it" } ?: ""}", authProblem = true)
                    }
                )
            }
            val cookie = reply.headerValues("set-cookie").map { it.substringBefore(';') }.filter { it.isNotBlank() }.joinToString("; ")
            if (cookie.isBlank()) return ClassicLogin.Failed(FetchResult.Failed("Growatt signed in but gave no session", authProblem = true))
            val plants = back.array("data")?.objects().orEmpty().mapNotNull { p ->
                val id = p.str("plantId") ?: return@mapNotNull null
                id to (p.str("plantName") ?: "Plant $id")
            }
            return ClassicLogin.Ok(cookie, plants)
        }

        /** Signs in and lists the readable inverters, for the setup picker. Calls each endpoint once. */
        suspend fun discover(fields: Map<String, String>, http: CloudHttp = OkCloudHttp): Result<List<DiscoveredDevice>> = try {
            val server = baseUrl(fields[SERVER].orEmpty())
            if (fields[MODE] == MODE_PASSWORD) discoverClassic(http, server, fields[USER].orEmpty(), fields[PASSWORD_HASH].orEmpty())
            else discoverV1(http, server, fields[TOKEN].orEmpty())
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Couldn't reach Growatt: ${e.localizedMessage ?: e.javaClass.simpleName}"))
        }

        private suspend fun discoverV1(http: CloudHttp, server: String, token: String): Result<List<DiscoveredDevice>> {
            val headers = mapOf("token" to token)
            val plantsReply = http.get("${server}v1/plant/list", headers)
            val plants = v1Data(plantsReply).getOrElse { return Result.failure(it) }
                .array("plants")?.objects().orEmpty()
            val devices = mutableListOf<DiscoveredDevice>()
            var unsupported = 0
            for (plant in plants) {
                val id = plant.str("plant_id") ?: continue
                val name = plant.str("name") ?: "Plant $id"
                val list = v1Data(http.get("${server}v1/device/list?plant_id=${enc(id)}", headers)).getOrElse { return Result.failure(it) }
                for (d in list.array("devices")?.objects().orEmpty()) {
                    val sn = d.str("device_sn") ?: continue
                    val type = V1_TYPES[d.int("type")]
                    if (type == null) {
                        unsupported++
                        continue
                    }
                    devices += DiscoveredDevice(sn, name, d.get("lost")?.takeIf { it.isJsonPrimitive }?.asBoolean?.not(), Device(true, type, sn).encode())
                }
            }
            if (devices.isEmpty() && unsupported > 0) {
                return Result.failure(IllegalStateException("The API token only reads SPH and MIN inverters. Sign in with your ShinePhone password instead."))
            }
            return Result.success(devices)
        }

        private fun v1Data(reply: HttpReply): Result<JsonObject> {
            if (reply.code !in 200..299) return Result.failure(IllegalStateException("Growatt returned HTTP ${reply.code}"))
            val json = jsonObject(reply.body) ?: return Result.failure(IllegalStateException("Growatt sent a reply Roshni can't read"))
            return when (json.int("error_code")) {
                0 -> Result.success(json.obj("data") ?: JsonObject())
                V1_NO_PRIVILEGE -> Result.failure(IllegalStateException("Growatt refused the API token. Check it, or make a new one."))
                V1_RATE_LIMITED -> Result.failure(IllegalStateException("Growatt allows one call every 5 minutes. Try again shortly."))
                else -> Result.failure(IllegalStateException("Growatt rejected the request: ${json.str("error_msg") ?: json.str("error_code")}"))
            }
        }

        private suspend fun discoverClassic(http: CloudHttp, server: String, user: String, hash: String): Result<List<DiscoveredDevice>> {
            val login = when (val l = classicLogin(http, server, user, hash)) {
                is ClassicLogin.Ok -> l
                is ClassicLogin.Failed -> return Result.failure(IllegalStateException(l.result.message))
            }
            val headers = mapOf("Cookie" to login.cookie, "User-Agent" to USER_AGENT)
            val devices = mutableListOf<DiscoveredDevice>()
            for ((plantId, plantName) in login.plants) {
                val reply = http.get("${server}newTwoPlantAPI.do?op=getAllDeviceList&plantId=${enc(plantId)}&language=1", headers)
                val list = jsonObject(reply.body)?.array("deviceList")?.objects().orEmpty()
                for (d in list) {
                    val sn = d.str("deviceSn") ?: continue
                    val type = d.str("deviceType")?.lowercase() ?: continue
                    if (type !in CLASSIC_TYPES) continue
                    devices += DiscoveredDevice(sn, plantName, d.get("lost")?.takeIf { it.isJsonPrimitive }?.asBoolean?.not(), Device(false, type, sn, plantId).encode())
                }
            }
            return Result.success(devices)
        }

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun form(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }
}
