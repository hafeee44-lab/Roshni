package io.github.hafeee44lab.solargrid.data.cloud

import com.google.gson.JsonObject
import io.github.hafeee44lab.solargrid.data.DiscoveredDevice
import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.ReadingSource
import io.github.hafeee44lab.solargrid.data.api.SolarMetric

/**
 * Huawei FusionSolar through its Northbound API ("OpenAPI", `https://<region>.fusionsolar.huawei.com/thirdData/`).
 *
 * Why this and not the alternatives: the kiosk link a home owner can make shows only solar
 * production, cached for 30 minutes, with no grid or battery, so it can't see a cut; the web
 * login used by unofficial clients needs RSA and captchas and changes without notice. A
 * Northbound account is made by the installer in two minutes (Company management → Northbound
 * management, with "Basic APIs" and the device data of the inverter, battery and power sensor
 * ticked), and is Huawei's supported way in.
 *
 * Reference: Huawei SmartPVMS Northbound API Reference (support.huawei.com, EDOC1100261860:
 * login, getStationList / stations, getDevList, getDevRealKpi) and the Home Assistant
 * integration github.com/tijsverkoyen/HomeAssistant-FusionSolar (openapi_api.py,
 * device_real_kpi_coordinator.py, sensor.py for the dataItemMap keys).
 *
 * Limits: one `getDevRealKpi` call per minute per account (failCode 407 otherwise), and the same
 * endpoint serves every device type. So each poll asks for one device type, in the order
 * grid, battery, grid, inverter, and fills in the others from the last time they were read.
 * Readings on FusionSolar refresh every 5 minutes. A login session (`xsrf-token`) is reused
 * until Huawei says it has expired (failCode 305); Huawei allows one session per account, so a
 * second app using the same account will keep signing Roshni out.
 */
class HuaweiSource(
    private val account: BrandAccount,
    private val session: SessionCache,
    private val http: CloudHttp = OkCloudHttp,
    private val memory: Memory = Memory.shared,
    private val clock: () -> Long = System::currentTimeMillis
) : ReadingSource {

    private val server = baseUrl(account.field(SERVER))
    private val plan = Plan.parse(account.device)

    override suspend fun fetch(): FetchResult {
        val plan = plan ?: return FetchResult.Failed("The saved Huawei inverter is unreadable; choose it again in Settings", authProblem = true)
        val group = plan.next(memory.turn(plan.key))
        var token = when (val t = token(forceLogin = false)) {
            is Token.Ok -> t.value
            is Token.Failed -> return t.result
        }

        var reply = realKpi(token, group)
        if (needsLogin(reply)) {
            token = when (val t = token(forceLogin = true)) {
                is Token.Ok -> t.value
                is Token.Failed -> return t.result
            }
            reply = realKpi(token, group)
        }

        val items = when (val parsed = parseRealKpi(reply)) {
            is KpiResult.Ok -> parsed.items
            is KpiResult.Failed -> return parsed.result
        }
        val map = items[group.id] ?: return FetchResult.Failed("Huawei returned no data for device ${group.id}; check the account can read it")
        val now = clock()
        memory.remember(plan.key, group.role, map, now)
        return compose(plan, memory.snapshot(plan.key, now))
    }

    private suspend fun realKpi(token: String, group: Group): HttpReply {
        session.countCall()
        return http.post(
            "${server}thirdData/getDevRealKpi",
            """{"devIds":"${group.id}","devTypeId":${group.type}}""",
            JSON,
            mapOf("xsrf-token" to token)
        )
    }

    private sealed interface Token {
        data class Ok(val value: String) : Token
        data class Failed(val result: FetchResult.Failed) : Token
    }

    private suspend fun token(forceLogin: Boolean): Token {
        val cached = session.session
        if (!forceLogin && cached != null && clock() < cached.second) return Token.Ok(cached.first)
        session.countCall()
        return when (val login = login(http, server, account.field(USER), account.field(SYSTEM_CODE))) {
            is Login.Ok -> {
                session.session = login.token to (clock() + SESSION_MS)
                Token.Ok(login.token)
            }
            is Login.Failed -> {
                session.session = null
                Token.Failed(login.result)
            }
        }
    }

    /** One device the plan reads, by role. */
    data class Group(val role: String, val type: Int, val id: String)

    /**
     * What to read for one inverter: the device that sees the grid (a power sensor or grid meter
     * if there is one, otherwise the inverter itself), the battery, and the inverter.
     */
    data class Plan(val station: String, val grid: Group?, val battery: Group?, val inverter: Group) {
        val key: String get() = "$station|${inverter.id}"

        fun encode(): String = listOfNotNull(
            "station=$station",
            "inv=${inverter.type}:${inverter.id}",
            grid?.let { "grid=${it.type}:${it.id}" },
            battery?.let { "bat=${it.type}:${it.id}" }
        ).joinToString(";")

        /** Grid every other poll: it decides the alarm. The battery and the inverter share the rest. */
        fun next(turn: Int): Group {
            val gridSource = grid ?: inverter
            val others = listOfNotNull(battery, inverter.takeIf { grid != null })
            if (others.isEmpty()) return gridSource
            return if (turn % 2 == 0) gridSource else others[(turn / 2) % others.size]
        }

        companion object {
            fun parse(raw: String): Plan? {
                val parts = raw.split(";").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                fun group(role: String, value: String?): Group? {
                    val (type, id) = value?.split(":", limit = 2)?.takeIf { it.size == 2 } ?: return null
                    return Group(role, type.toIntOrNull() ?: return null, id)
                }
                val inverter = group(ROLE_INVERTER, parts["inv"]) ?: return null
                return Plan(parts["station"].orEmpty(), group(ROLE_GRID, parts["grid"]), group(ROLE_BATTERY, parts["bat"]), inverter)
            }
        }
    }

    /** The last dataItemMap read for each role, kept in the process so polls can take turns. */
    class Memory {
        private val turns = HashMap<String, Int>()
        private val maps = HashMap<String, MutableMap<String, Pair<JsonObject, Long>>>()

        @Synchronized
        fun turn(key: String): Int = (turns[key] ?: 0).also { turns[key] = it + 1 }

        @Synchronized
        fun remember(key: String, role: String, map: JsonObject, at: Long) {
            maps.getOrPut(key) { HashMap() }[role] = map to at
        }

        /** What is still fresh enough to show: older than [MAX_AGE_MS] says nothing about now. */
        @Synchronized
        fun snapshot(key: String, now: Long): Map<String, JsonObject> =
            maps[key].orEmpty().filterValues { now - it.second <= MAX_AGE_MS }.mapValues { it.value.first }

        companion object {
            val shared = Memory()
            const val MAX_AGE_MS = 15 * 60_000L
        }
    }

    sealed interface Login {
        data class Ok(val token: String) : Login
        data class Failed(val result: FetchResult.Failed) : Login
    }

    sealed interface KpiResult {
        data class Ok(val items: Map<String, JsonObject>) : KpiResult
        data class Failed(val result: FetchResult) : KpiResult
    }

    companion object {
        const val SERVER = "server"
        const val USER = "user"
        const val SYSTEM_CODE = "system_code"
        const val DEFAULT_SERVER = "intl.fusionsolar.huawei.com"

        const val ROLE_GRID = "grid"
        const val ROLE_BATTERY = "battery"
        const val ROLE_INVERTER = "inverter"

        /** devTypeId values from the Northbound reference (and HA FusionSolar const.py). */
        const val TYPE_STRING_INVERTER = 1
        const val TYPE_GRID_METER = 17
        const val TYPE_RESIDENTIAL_INVERTER = 38
        const val TYPE_BATTERY = 39
        const val TYPE_POWER_SENSOR = 47

        private const val JSON = "application/json"
        private const val SESSION_MS = 25 * 60_000L

        /** failCodes: 305 signed out, 401 no right to this interface, 407 too frequent. */
        const val FAIL_RELOGIN = 305
        const val FAIL_NO_RIGHT = 401
        const val FAIL_TOO_FREQUENT = 407

        fun baseUrl(server: String): String {
            val host = server.trim().ifBlank { DEFAULT_SERVER }.removeSuffix("/").removeSuffix("/thirdData")
            return (if (host.startsWith("http")) host else "https://$host") + "/"
        }

        private fun needsLogin(reply: HttpReply): Boolean =
            reply.code == 401 || jsonObject(reply.body)?.int("failCode") == FAIL_RELOGIN

        fun parseRealKpi(reply: HttpReply): KpiResult {
            if (reply.code !in 200..299) return KpiResult.Failed(FetchResult.Failed("Huawei returned HTTP ${reply.code}"))
            val json = jsonObject(reply.body) ?: return KpiResult.Failed(FetchResult.Failed("Huawei sent a reply Roshni can't read"))
            when (json.int("failCode")) {
                null, 0 -> Unit
                FAIL_RELOGIN -> return KpiResult.Failed(FetchResult.Failed("Huawei keeps signing Roshni out. Is another app using this API account?", authProblem = true))
                FAIL_NO_RIGHT -> return KpiResult.Failed(FetchResult.Failed("This API account can't read device data. Ask the installer to tick it under Northbound management.", authProblem = true))
                FAIL_TOO_FREQUENT -> return KpiResult.Failed(FetchResult.Failed("Huawei allows one reading a minute; trying again shortly"))
                else -> return KpiResult.Failed(FetchResult.Failed("Huawei rejected the request (failCode ${json.int("failCode")}): ${json.str("message") ?: json.str("data") ?: ""}".trimEnd(' ', ':')))
            }
            val items = json.array("data")?.objects().orEmpty().mapNotNull { item ->
                val id = item.get("devId")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val map = item.obj("dataItemMap") ?: return@mapNotNull null
                id to map
            }.toMap()
            return KpiResult.Ok(items)
        }

        /**
         * Builds the metrics from whichever roles are fresh. Signs follow Roshni: grid power
         * positive when buying, battery power positive when discharging. Huawei's power sensor
         * reports export as positive and its battery reports charging as positive, so both flip.
         */
        fun compose(plan: Plan, maps: Map<String, JsonObject>): FetchResult {
            val grid = maps[ROLE_GRID]
            val battery = maps[ROLE_BATTERY]
            val inverter = maps[ROLE_INVERTER]
            val gridDevice = if (plan.grid != null) grid else inverter

            // run_state 0 is "disconnected": FusionSolar has lost the dongle, so nothing here is current.
            if (gridDevice != null && gridDevice.int("run_state") == 0) return FetchResult.LoggerOffline

            val b = MetricsBuilder()
            if (plan.grid != null && grid != null) {
                b.add("G_V_L1", grid.num("meter_u", "a_u"), "V", "Grid Voltage")
                    .add("G_V_L2", grid.num("b_u"), "V", "Grid Voltage L2")
                    .add("G_V_L3", grid.num("c_u"), "V", "Grid Voltage L3")
                    .add("PG_F1", grid.num("grid_frequency"), "Hz", "Grid Frequency")
                    .add("PG_Pt1", grid.num("active_power")?.let { -it }, "W", "Total Grid Power")
            } else if (plan.grid == null && inverter != null) {
                // No power sensor: the inverter's own AC side. On a plain grid-tied unit this goes
                // to zero in a cut; behind a backup box it may show the backup output instead.
                b.add("G_V_L1", inverter.num("a_u", "ab_u"), "V", "Grid Voltage")
                    .add("G_V_L2", inverter.num("b_u", "bc_u"), "V", "Grid Voltage L2")
                    .add("G_V_L3", inverter.num("c_u", "ca_u"), "V", "Grid Voltage L3")
                    .add("PG_F1", inverter.num("elec_freq"), "Hz", "Grid Frequency")
            }
            if (battery != null) {
                b.add("B_P1", battery.num("ch_discharge_power")?.let { -it }, "W", "Battery Power")
                    .add("B_left_cap1", battery.num("battery_soc"), "%", "SoC")
                    .add("Etdy_cg1", battery.num("charge_cap"), "kWh", "Daily Charging Energy")
                    .add("Etdy_dcg1", battery.num("discharge_cap"), "kWh", "Daily Discharging Energy")
            }
            if (inverter != null) {
                b.add("S_P_T", inverter.num("mppt_power")?.times(1000.0), "W", "Total Solar Power")
                    .add("AC_T", inverter.num("temperature"), "°C", "AC Temperature")
                    .add("Etdy_ge1", inverter.num("day_cap"), "kWh", "Daily Production")
            }
            // House load from the flows, once grid, battery and inverter output are all known.
            val gridW = grid?.num("active_power")?.let { -it }
            val inverterW = inverter?.num("active_power")?.times(1000.0)
            if (plan.grid != null && gridW != null && inverterW != null) {
                b.add("E_Puse_t1", (inverterW + gridW).coerceAtLeast(0.0), "W", "Total Consumption Power")
            }
            val metrics: List<SolarMetric> = b.build()
            if (metrics.isEmpty()) return FetchResult.Failed("Huawei returned no readings for this inverter yet")
            // getDevRealKpi carries no collection time; FusionSolar's own disconnect flag stands in for staleness.
            return FetchResult.Ok(metrics, collectedAt = 0L, inverterAlarm = false)
        }

        suspend fun login(http: CloudHttp, server: String, user: String, systemCode: String): Login {
            val body = com.google.gson.JsonObject().apply {
                addProperty("userName", user)
                addProperty("systemCode", systemCode)
            }.toString()
            val reply = http.post("${server}thirdData/login", body, JSON, mapOf("Accept" to JSON))
            if (reply.code !in 200..299) return Login.Failed(FetchResult.Failed("Huawei sign-in failed (HTTP ${reply.code})", authProblem = true))
            val json = jsonObject(reply.body)
            val token = reply.header("xsrf-token")
            if (token.isNullOrBlank() || json?.get("success")?.asBoolean == false) {
                val code = json?.int("failCode")
                return Login.Failed(
                    when (code) {
                        FAIL_TOO_FREQUENT -> FetchResult.Failed("Huawei refused: too many sign-ins. Trying again shortly.")
                        else -> FetchResult.Failed("Huawei sign-in rejected — check the API username, password and server${code?.let { " (failCode $it)" } ?: ""}", authProblem = true)
                    }
                )
            }
            return Login.Ok(token)
        }

        /** Signs in, lists plants and devices, and offers each inverter with its plant's battery and power sensor. */
        suspend fun discover(fields: Map<String, String>, http: CloudHttp = OkCloudHttp): Result<List<DiscoveredDevice>> = try {
            val server = baseUrl(fields[SERVER].orEmpty())
            when (val login = login(http, server, fields[USER].orEmpty(), fields[SYSTEM_CODE].orEmpty())) {
                is Login.Failed -> Result.failure(IllegalStateException(login.result.message))
                is Login.Ok -> discoverWith(http, server, login.token)
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Couldn't reach Huawei: ${e.localizedMessage ?: e.javaClass.simpleName}"))
        }

        private suspend fun discoverWith(http: CloudHttp, server: String, token: String): Result<List<DiscoveredDevice>> {
            val headers = mapOf("xsrf-token" to token)
            // The newer "stations" call first; older accounts only have getStationList.
            var stations = jsonObject(http.post("${server}thirdData/stations", """{"pageNo":1}""", JSON, headers).body)
                ?.takeIf { it.int("failCode") in setOf(null, 0) }
                ?.obj("data")?.array("list")?.objects().orEmpty()
                .mapNotNull { s -> s.str("plantCode")?.let { it to (s.str("plantName") ?: it) } }
            if (stations.isEmpty()) {
                val legacy = jsonObject(http.post("${server}thirdData/getStationList", "{}", JSON, headers).body)
                legacy?.int("failCode")?.takeIf { it != 0 }?.let { return Result.failure(IllegalStateException("Huawei listed no plants (failCode $it)")) }
                stations = legacy?.array("data")?.objects().orEmpty()
                    .mapNotNull { s -> s.str("stationCode")?.let { it to (s.str("stationName") ?: it) } }
            }
            if (stations.isEmpty()) return Result.success(emptyList())

            val codes = stations.joinToString(",") { it.first }
            val devReply = jsonObject(http.post("${server}thirdData/getDevList", com.google.gson.JsonObject().apply { addProperty("stationCodes", codes) }.toString(), JSON, headers).body)
                ?: return Result.failure(IllegalStateException("Huawei sent a device list Roshni can't read"))
            devReply.int("failCode")?.takeIf { it != 0 }?.let { return Result.failure(IllegalStateException("Huawei refused the device list (failCode $it)")) }
            return Result.success(plansFrom(stations.toMap(), devReply.array("data")?.objects().orEmpty()))
        }

        internal fun plansFrom(stationNames: Map<String, String>, devices: List<JsonObject>): List<DiscoveredDevice> {
            val byStation = devices.groupBy { it.str("stationCode").orEmpty() }
            return byStation.flatMap { (station, list) ->
                fun first(vararg types: Int, role: String) = list.firstOrNull { it.int("devTypeId") in types.toList() }
                    ?.let { d -> d.get("id")?.asString?.let { Group(role, d.int("devTypeId")!!, it) } }
                val grid = first(TYPE_POWER_SENSOR, TYPE_GRID_METER, role = ROLE_GRID)
                val battery = first(TYPE_BATTERY, role = ROLE_BATTERY)
                list.filter { it.int("devTypeId") in setOf(TYPE_RESIDENTIAL_INVERTER, TYPE_STRING_INVERTER) }.mapNotNull { d ->
                    val id = d.get("id")?.asString ?: return@mapNotNull null
                    val plan = Plan(station, grid, battery, Group(ROLE_INVERTER, d.int("devTypeId")!!, id))
                    DiscoveredDevice(d.str("esnCode") ?: d.str("devName") ?: id, stationNames[station] ?: station, null, plan.encode())
                }
            }
        }
    }
}
