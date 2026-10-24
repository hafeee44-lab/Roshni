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
 * DESS Monitor, the Eybond cloud behind the SmartESS and WatchPower (Wi-Fi) apps. Most
 * Voltronic / Axpert-family inverters sold in Pakistan (Inverex, Fronus, Knox and other rebrands)
 * report here through an Eybond Wi-Fi collector. There is no official public API; this follows
 * the app's own API as two open-source Home Assistant integrations use it:
 * github.com/andreas-glaser/ha-dessmonitor (api.py: authSource, signing, error handling,
 * webQueryDeviceEs) and github.com/Antoxa1081/home-assistant-dess-monitor (sdk/: signing,
 * querySPDeviceLastData with its `gts` timestamp, err 10 / 3 / 9).
 *
 * Signing: every request is `?sign=<sha1>&salt=<ms>[&token=<token>]&action=...&params`, where
 * the sign is sha1(salt + sha1(password) + "&action=...") to log in and
 * sha1(salt + secret + token + "&action=...") afterwards. Only sha1(password) is stored.
 *
 * Polling: the collector uploads every 5 minutes (1 minute with a paid option), and newer polls
 * can't see anything sooner, so [CloudBrand.DESS] polls at most every 2 minutes, one call each.
 */
class DessSource(
    private val account: BrandAccount,
    private val session: SessionCache,
    private val http: CloudHttp = OkCloudHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zones: ServerZone = ServerZone.shared
) : ReadingSource {

    override suspend fun fetch(): FetchResult {
        val device = Device.parse(account.device)
            ?: return FetchResult.Failed("The saved DESS inverter is unreadable; choose it again in Settings", authProblem = true)
        var auth = when (val a = auth(force = false)) {
            is AuthResult.Ok -> a.auth
            is AuthResult.Failed -> return a.result
        }
        var reply = lastData(auth, device)
        if (jsonObject(reply.body)?.int("err") == ERR_TOKEN) {
            auth = when (val a = auth(force = true)) {
                is AuthResult.Ok -> a.auth
                is AuthResult.Failed -> return a.result
            }
            reply = lastData(auth, device)
        }
        return parseLastData(reply, clock(), device.pn, zones)
    }

    private suspend fun lastData(auth: Auth, device: Device): HttpReply {
        session.countCall()
        val params = listOf(
            "action" to "querySPDeviceLastData", "i18n" to "en_US", "source" to "1",
            "pn" to device.pn, "devcode" to device.devcode, "devaddr" to device.devaddr, "sn" to device.sn
        )
        return http.get(signedUrl(params, clock(), auth), HEADERS)
    }

    data class Auth(val token: String, val secret: String)

    private sealed interface AuthResult {
        data class Ok(val auth: Auth) : AuthResult
        data class Failed(val result: FetchResult.Failed) : AuthResult
    }

    private suspend fun auth(force: Boolean): AuthResult {
        val cached = session.session
        if (!force && cached != null && clock() < cached.second) {
            val (token, secret) = cached.first.split("\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (token.isNotBlank() && secret.isNotBlank()) return AuthResult.Ok(Auth(token, secret))
        }
        session.countCall()
        return when (val login = login(http, account.field(USER), account.field(PASSWORD_SHA1), clock())) {
            is Login.Ok -> {
                // Renew a little before DESS's own expiry.
                session.session = "${login.auth.token}\n${login.auth.secret}" to (clock() + (login.expiresInS * 1000L - 10 * 60_000L).coerceAtLeast(60_000L))
                AuthResult.Ok(login.auth)
            }
            is Login.Failed -> {
                session.session = null
                AuthResult.Failed(login.result)
            }
        }
    }

    /** The collector (pn) and the inverter behind it, as DESS addresses it. */
    data class Device(val pn: String, val devcode: String, val devaddr: String, val sn: String) {
        fun encode() = listOf(pn, devcode, devaddr, sn).joinToString("|")

        companion object {
            fun parse(raw: String): Device? = raw.split("|").takeIf { it.size == 4 && it.all(String::isNotBlank) }
                ?.let { Device(it[0], it[1], it[2], it[3]) }
        }
    }

    /**
     * DESS writes `gts` in its server's local time with no zone. The offset is learned from the
     * readings themselves, as Antoxa1081's integration does, but keeping the smallest lag seen,
     * so a first reading that is already hours old can't make every later one look fresh.
     */
    class ServerZone {
        private val offsets = HashMap<String, Long>()

        @Synchronized
        fun correct(key: String, stampedAsUtc: Long, now: Long): Long {
            // Whole hours, allowing the phone's clock up to 10 minutes behind the server's.
            val estimate = Math.floorDiv(now - stampedAsUtc + SKEW_MS, HOUR_MS) * HOUR_MS
            val offset = offsets[key]?.let { minOf(it, estimate) } ?: estimate
            offsets[key] = offset
            return stampedAsUtc + offset
        }

        companion object {
            val shared = ServerZone()
            private const val HOUR_MS = 60 * 60_000L
            private const val SKEW_MS = 10 * 60_000L
        }
    }

    sealed interface Login {
        data class Ok(val auth: Auth, val expiresInS: Long) : Login
        data class Failed(val result: FetchResult.Failed) : Login
    }

    companion object {
        const val USER = "user"
        const val PASSWORD_SHA1 = "password_sha1"

        const val BASE_URL = "https://api.dessmonitor.com/public/"

        /** The DessMonitor platform's company key, the one both integrations send. */
        private const val COMPANY_KEY = "bnrl_frRFjEz8Mkn"

        const val ERR_TOKEN = 10
        private val ERR_RATE = setOf(3, 9)

        /** ShineMonitor's "no record" code (ERR_NO_RECORD, not seen in a recorded reply yet): nothing to show. */
        private const val ERR_NO_RECORD = 12

        private val HEADERS = mapOf("Referer" to "https://www.dessmonitor.com/")

        fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Query-string encoding, with '@' left alone as the upstream signer expects. */
        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("%40", "@")

        fun actionString(params: List<Pair<String, String>>): String =
            params.joinToString("") { (k, v) -> "&${enc(k)}=${enc(v)}" }

        /** Before login the key is sha1(password); after it, secret + token, and the token rides along. */
        fun signedUrl(params: List<Pair<String, String>>, saltMs: Long, auth: Auth? = null, passwordSha1: String = ""): String {
            val action = actionString(params)
            val salt = saltMs.toString()
            val sign = if (auth != null) sha1(salt + auth.secret + auth.token + action) else sha1(salt + passwordSha1 + action)
            val token = auth?.let { "&token=${enc(it.token)}" }.orEmpty()
            return "${BASE_URL}?sign=$sign&salt=$salt$token$action"
        }

        suspend fun login(http: CloudHttp, user: String, passwordSha1: String, now: Long): Login {
            val params = listOf("action" to "authSource", "usr" to user, "source" to "1", "company-key" to COMPANY_KEY)
            val reply = http.get(signedUrl(params, now, passwordSha1 = passwordSha1), HEADERS)
            if (reply.code !in 200..299) return Login.Failed(FetchResult.Failed("DESS sign-in failed (HTTP ${reply.code})", authProblem = true))
            val json = jsonObject(reply.body) ?: return Login.Failed(FetchResult.Failed("DESS sent a sign-in reply Roshni can't read", authProblem = true))
            val err = json.int("err")
            if (err != 0) {
                return Login.Failed(
                    if (err in ERR_RATE) FetchResult.Failed("DESS asked us to slow down")
                    else FetchResult.Failed("DESS sign-in rejected — check the username and password (${json.str("desc") ?: "err $err"})", authProblem = true)
                )
            }
            val dat = json.obj("dat")
            val token = dat?.str("token")
            val secret = dat?.str("secret")
            if (token.isNullOrBlank() || secret.isNullOrBlank()) return Login.Failed(FetchResult.Failed("DESS signed in but gave no session", authProblem = true))
            return Login.Ok(Auth(token, secret), dat.num("expire")?.toLong() ?: 3600L)
        }

        fun parseLastData(reply: HttpReply, now: Long, deviceKey: String, zones: ServerZone): FetchResult {
            if (reply.code !in 200..299) return FetchResult.Failed("DESS returned HTTP ${reply.code}")
            val json = jsonObject(reply.body) ?: return FetchResult.Failed("DESS sent a reply Roshni can't read")
            when (val err = json.int("err")) {
                0 -> Unit
                ERR_TOKEN -> return FetchResult.Failed("DESS keeps signing Roshni out; check the username and password", authProblem = true)
                in ERR_RATE -> return FetchResult.Failed("DESS asked us to slow down; trying again later")
                ERR_NO_RECORD -> return FetchResult.LoggerOffline
                else -> return FetchResult.Failed("DESS rejected the request: ${json.str("desc") ?: "err $err"}")
            }
            val dat = json.obj("dat") ?: return FetchResult.LoggerOffline
            val values = dat.obj("pars")?.entrySet().orEmpty().flatMap { (_, list) ->
                if (list.isJsonArray) list.asJsonArray.objects() else emptyList()
            }.mapNotNull { p -> (p.str("par") ?: p.str("title"))?.let { Triple(it, p.str("val"), p.str("unit")) } }
            val metrics = metrics(values)
            if (metrics.isEmpty()) return FetchResult.LoggerOffline
            val stamped = parseGts(dat.str("gts"))
            val collectedAt = stamped?.let { zones.correct(deviceKey, it, now) } ?: 0L
            return FetchResult.Ok(metrics, collectedAt, inverterAlarm = false)
        }

        private fun parseGts(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            raw.trim().toLongOrNull()?.let { return if (it > 100_000_000_000L) it else it * 1000L }
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(raw.trim())?.time
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Maps DESS's titled values (titles differ by collector devcode; seen in the recorded
         * replies of both integrations) to Roshni's keys. The grid is the inverter's AC input:
         * 0 V and 0 Hz in a cut. "Invert Mode" alone is not a cut, it is also SBU running on
         * battery with the mains present. Battery current is signed (negative discharging) on
         * some collectors and split into charging / discharge current on others.
         */
        internal fun metrics(values: List<Triple<String, String?, String?>>): List<SolarMetric> {
            val byTitle = values.associateBy({ it.first.trim().lowercase() }, { it })
            fun raw(vararg titles: String): Triple<String, String?, String?>? = titles.firstNotNullOfOrNull { byTitle[it] }
            fun n(vararg titles: String): Double? = raw(*titles)?.second?.let(::parseNumber)
            fun watts(vararg titles: String): Double? = raw(*titles)?.let { t -> parseNumber(t.second)?.let { if (t.third.equals("kW", true)) it * 1000.0 else it } }

            val volts = n("battery voltage")
            val signedCurrent = n("battery current", "batt current")
            val charging = n("battery charging current")
            val discharging = n("battery discharge current")
            val battery = when {
                volts == null -> null
                signedCurrent != null -> -signedCurrent * volts
                charging != null || discharging != null -> ((discharging ?: 0.0) - (charging ?: 0.0)) * volts
                else -> null
            }
            val pvParts = listOfNotNull(
                watts("pv1 input power", "pv1 charging power", "pv1 charger power"),
                watts("pv2 input power", "pv2 charging power", "pv2 charger power")
            )
            val solar = watts("pv total power", "pv power", "pv input power", "pv total charger power", "pv charging power", "pv charge power")
                ?: pvParts.takeIf { it.isNotEmpty() }?.sum()
            val grid = watts("grid power", "pgrid") ?: watts("solar feed to grid power")?.let { -it }

            return MetricsBuilder()
                .add("G_V_L1", n("ac input voltage", "grid voltage"), "V", "Grid Voltage")
                .add("PG_F1", n("ac input frequency", "grid frequency"), "Hz", "Grid Frequency")
                .add("PG_Pt1", grid, "W", "Total Grid Power")
                .add("B_P1", battery, "W", "Battery Power")
                .add("B_left_cap1", n("battery capacity", "soc", "state of charge"), "%", "SoC")
                .add("B_V1", volts, "V", "Battery Voltage")
                .add("S_P_T", solar, "W", "Total Solar Power")
                .add("E_Puse_t1", watts("output active power", "ac output active power", "pload"), "W", "Total Consumption Power")
                .add("AC_T", n("inv module termperature", "inverter heat sink temperature", "inverter temperature"), "°C", "AC Temperature")
                .text("WORK_MODE", raw("working state", "operating mode")?.second, "Working State")
                .build()
        }

        /** Signs in and lists the account's inverters, for the setup picker. */
        suspend fun discover(fields: Map<String, String>, http: CloudHttp = OkCloudHttp, now: Long = System.currentTimeMillis()): Result<List<DiscoveredDevice>> {
            return try {
                val auth = when (val l = login(http, fields[USER].orEmpty(), fields[PASSWORD_SHA1].orEmpty(), now)) {
                    is Login.Ok -> l.auth
                    is Login.Failed -> return Result.failure(IllegalStateException(l.result.message))
                }
                val params = listOf("action" to "webQueryDeviceEs", "i18n" to "en_US", "source" to "1", "page" to "0", "pagesize" to "50")
                val json = jsonObject(http.get(signedUrl(params, now, auth), HEADERS).body)
                    ?: return Result.failure(IllegalStateException("DESS sent a device list Roshni can't read"))
                json.int("err")?.takeIf { it != 0 }?.let { return Result.failure(IllegalStateException("DESS refused the device list: ${json.str("desc") ?: "err $it"}")) }
                Result.success(json.obj("dat")?.array("device")?.objects().orEmpty().mapNotNull { d -> device(d) })
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Couldn't reach DESS: ${e.localizedMessage ?: e.javaClass.simpleName}"))
            }
        }

        /** webQueryDeviceEs `status`: 1 is offline (0 normal, 2 fault, 3 standby, 4 warning). */
        private fun device(d: JsonObject): DiscoveredDevice? {
            val pn = d.str("pn") ?: return null
            val sn = d.str("sn") ?: return null
            val devcode = d.str("devcode") ?: return null
            val devaddr = d.str("devaddr") ?: return null
            return DiscoveredDevice(sn, d.str("devalias") ?: pn, d.int("status")?.let { it != 1 }, Device(pn, devcode, devaddr, sn).encode())
        }
    }
}
