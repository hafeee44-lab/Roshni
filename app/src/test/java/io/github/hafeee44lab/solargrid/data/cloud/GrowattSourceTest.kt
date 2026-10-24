package io.github.hafeee44lab.solargrid.data.cloud

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class GrowattSourceTest {

    private val karachi = TimeZone.getTimeZone("Asia/Karachi")

    private fun v1(file: String, type: String) =
        GrowattSource.parseV1(ok(fixture("growatt/$file")), type, karachi)

    private fun account(mode: String, device: GrowattSource.Device) = BrandAccount(
        brand = CloudBrand.GROWATT,
        fields = mapOf(
            GrowattSource.MODE to mode,
            GrowattSource.SERVER to GrowattSource.DEFAULT_SERVER,
            GrowattSource.TOKEN to "tok-123",
            GrowattSource.USER to "owner",
            GrowattSource.PASSWORD_HASH to GrowattSource.hashPassword("secret")
        ),
        device = device.encode()
    )

    @Test
    fun `password hash matches growattServer, zeros at even positions become c`() {
        assertEquals("c98f6bcd4621d373cade4e832627b4f6", GrowattSource.hashPassword("test"))
        assertEquals("e99a18c428cb38d5f260853678922ec3", GrowattSource.hashPassword("abc123"))
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", GrowattSource.hashPassword("password"))
    }

    @Test
    fun `V1 SPH maps grid, battery, solar and daily energy to Roshni's keys`() {
        val result = v1("v1_sph_grid_on.json", GrowattSource.TYPE_SPH) as FetchResult.Ok
        val reading = GridAnalyzer.read(result.metrics)
        assertEquals(230.0, reading.gridVoltage!!, 0.01)
        assertEquals(50.0, reading.gridFrequency!!, 0.01)
        assertEquals(200.0, reading.gridPowerW!!, 0.01)
        // Charging at 1.2 kW is negative in Roshni's convention (positive = battery supplying).
        assertEquals(-1200.0, reading.batteryPowerW!!, 0.01)
        assertEquals(75.0, reading.soc!!, 0.01)
        assertEquals(1500.0, reading.solarPowerW!!, 0.01)
        // No load field on SPH: worked out from the flows, 1500 - 1200 + 200.
        assertEquals(500.0, reading.loadPowerW!!, 0.01)
        assertEquals(8.0, reading.today.producedKwh!!, 0.01)
        assertEquals(1.5, reading.today.boughtKwh!!, 0.01)
        assertEquals(3.0, reading.today.dischargedKwh!!, 0.01)
        assertFalse(CloudFakes.verdict(result)!!.gridDown)
    }

    @Test
    fun `V1 timestamp is read in the plant's local time`() {
        val result = v1("v1_sph_grid_on.json", GrowattSource.TYPE_SPH) as FetchResult.Ok
        // 2026-10-24 12:30:00 PKT = 07:30:00 UTC
        assertEquals(1792827000000L, result.collectedAt)
    }

    @Test
    fun `V1 SPH in a cut reads grid down`() {
        val result = v1("v1_sph_grid_off.json", GrowattSource.TYPE_SPH)
        val verdict = CloudFakes.verdict(result)!!
        assertTrue(verdict.gridDown)
        assertEquals(1450.0, GridAnalyzer.read((result as FetchResult.Ok).metrics).batteryPowerW!!, 0.01)
    }

    @Test
    fun `V1 MIN maps tlx fields, export is negative grid power`() {
        val reading = GridAnalyzer.read((v1("v1_min_grid_on.json", GrowattSource.TYPE_MIN) as FetchResult.Ok).metrics)
        assertEquals(231.5, reading.gridVoltage!!, 0.01)
        assertEquals(-350.0, reading.gridPowerW!!, 0.01)
        assertEquals(-2100.0, reading.batteryPowerW!!, 0.01)
        assertEquals(88.0, reading.soc!!, 0.01)
        assertEquals(900.0, reading.loadPowerW!!, 0.01)
        assertEquals(41.2, reading.inverterTempC!!, 0.01)
    }

    @Test
    fun `V1 rate limit is a transient failure, no privilege is an auth problem`() {
        val limited = v1("v1_rate_limited.json", GrowattSource.TYPE_SPH) as FetchResult.Failed
        assertFalse(limited.authProblem)
        assertFalse(limited.quotaExhausted)
        val denied = v1("v1_no_privilege.json", GrowattSource.TYPE_SPH) as FetchResult.Failed
        assertTrue(denied.authProblem)
    }

    @Test
    fun `V1 with no data means the inverter has nothing to report`() {
        val result = GrowattSource.parseV1(ok("""{"error_code":0,"error_msg":"","data":null}"""), GrowattSource.TYPE_SPH, karachi)
        assertEquals(FetchResult.LoggerOffline, result)
    }

    @Test
    fun `ShinePhone mix status is in kilowatts`() {
        val result = GrowattSource.parseClassic(ok(fixture("growatt/classic_mix_status.json")), GrowattSource.TYPE_MIX) as FetchResult.Ok
        val reading = GridAnalyzer.read(result.metrics)
        assertEquals(420.0, reading.batteryPowerW!!, 0.01)
        assertEquals(950.0, reading.loadPowerW!!, 0.01)
        assertEquals(530.0, reading.solarPowerW!!, 0.01)
        assertEquals(64.0, reading.soc!!, 0.01)
        assertEquals(229.4, reading.gridVoltage!!, 0.01)
        assertFalse(CloudFakes.verdict(result)!!.gridDown)
    }

    @Test
    fun `SPF storage in a cut reads grid down, discharge worked out from the flows`() {
        val result = GrowattSource.parseClassic(ok(fixture("growatt/classic_storage_grid_off.json")), GrowattSource.TYPE_STORAGE) as FetchResult.Ok
        val reading = GridAnalyzer.read(result.metrics)
        assertTrue(CloudFakes.verdict(result)!!.gridDown)
        assertEquals(47.0, reading.soc!!, 0.01)
        // 820 W out, 120 W from the panels, nothing from the mains: the battery gives 700 W.
        assertEquals(700.0, reading.batteryPowerW!!, 0.01)
        assertEquals(820.0, reading.loadPowerW!!, 0.01)
    }

    @Test
    fun `token fetch sends the token header to the right endpoint, once`() = runBlocking {
        val http = FakeHttp().on("mix_last_data", ok(fixture("growatt/v1_sph_grid_on.json")))
        val session = MemorySession()
        val source = GrowattSource(account(GrowattSource.MODE_TOKEN, GrowattSource.Device(true, GrowattSource.TYPE_SPH, "SPH0000001")), session, http, zone = karachi)

        assertTrue(source.fetch() is FetchResult.Ok)
        assertEquals(1, http.sent.size)
        assertEquals("https://openapi.growatt.com/v1/device/mix/mix_last_data", http.sent[0].url)
        assertEquals("tok-123", http.sent[0].headers["token"])
        assertEquals("mix_sn=SPH0000001", http.sent[0].body)
        assertEquals(1, session.calls)
    }

    @Test
    fun `password fetch signs in once and reuses the cookie`() = runBlocking {
        var now = 1_000L
        val http = FakeHttp()
            .on("newTwoLoginAPI.do", ok(fixture("growatt/classic_login_ok.json"), "Set-Cookie" to "JSESSIONID=abc; Path=/", "Set-Cookie" to "SERVERID=s1; Path=/"))
            .on("newStorageAPI.do", ok(fixture("growatt/classic_storage_grid_off.json")))
        val session = MemorySession()
        val source = GrowattSource(
            account(GrowattSource.MODE_PASSWORD, GrowattSource.Device(false, GrowattSource.TYPE_STORAGE, "SPF0000001", "1234567")),
            session, http, clock = { now }
        )

        assertTrue(source.fetch() is FetchResult.Ok)
        now += 5 * 60_000L
        assertTrue(source.fetch() is FetchResult.Ok)

        assertEquals(1, http.sent.count { it.url.contains("newTwoLoginAPI.do") })
        val login = http.sent.first { it.url.contains("newTwoLoginAPI.do") }
        assertEquals("userName=owner&password=${GrowattSource.hashPassword("secret")}", login.body)
        assertTrue(http.sent.filter { it.url.contains("newStorageAPI") }.all { it.headers["Cookie"] == "JSESSIONID=abc; SERVERID=s1" })
    }

    @Test
    fun `an expired ShinePhone session signs in again exactly once`() = runBlocking {
        val http = FakeHttp()
            .on("newTwoLoginAPI.do", ok(fixture("growatt/classic_login_ok.json"), "Set-Cookie" to "JSESSIONID=new"))
            .on("newMixApi.do", HttpReply(302, ""), ok(fixture("growatt/classic_mix_status.json")))
        val session = MemorySession().apply { session = "JSESSIONID=old" to Long.MAX_VALUE }
        val source = GrowattSource(
            account(GrowattSource.MODE_PASSWORD, GrowattSource.Device(false, GrowattSource.TYPE_MIX, "MIX0000001", "1234567")),
            session, http
        )

        assertTrue(source.fetch() is FetchResult.Ok)
        assertEquals(1, http.sent.count { it.url.contains("newTwoLoginAPI.do") })
        assertEquals("JSESSIONID=new", session.session!!.first)
        assertTrue(http.sent.last().url.contains("mixId=MIX0000001&plantId=1234567"))
    }

    @Test
    fun `a ShinePhone lockout pauses checks, a wrong password is an auth problem`() = runBlocking {
        fun source(reply: String) = GrowattSource(
            account(GrowattSource.MODE_PASSWORD, GrowattSource.Device(false, GrowattSource.TYPE_MIX, "MIX0000001", "1")),
            MemorySession(), FakeHttp().on("newTwoLoginAPI.do", ok(fixture("growatt/$reply")))
        )
        val locked = source("classic_login_locked.json").fetch() as FetchResult.Failed
        assertTrue(locked.quotaExhausted)
        val bad = source("classic_login_bad.json").fetch() as FetchResult.Failed
        assertTrue(bad.authProblem)
        assertFalse(bad.quotaExhausted)
    }

    @Test
    fun `token discovery lists SPH and MIN, skips what V1 can't read`() = runBlocking {
        val http = FakeHttp().on("plant/list", ok(fixture("growatt/v1_plants.json"))).on("device/list", ok(fixture("growatt/v1_devices.json")))
        val devices = GrowattSource.discover(mapOf(GrowattSource.MODE to GrowattSource.MODE_TOKEN, GrowattSource.TOKEN to "t"), http).getOrThrow()
        assertEquals(listOf("SPH0000001"), devices.map { it.sn })
        assertEquals("Rooftop", devices[0].station)
        assertEquals(true, devices[0].online)
        assertEquals(GrowattSource.Device(true, GrowattSource.TYPE_SPH, "SPH0000001"), GrowattSource.Device.parse(devices[0].ref))
        assertTrue(http.sent[1].url.endsWith("v1/device/list?plant_id=424242"))
    }

    @Test
    fun `token discovery with only an SPF says to use the password instead`() = runBlocking {
        val http = FakeHttp().on("plant/list", ok(fixture("growatt/v1_plants.json"))).on("device/list", ok(fixture("growatt/v1_devices_storage_only.json")))
        val result = GrowattSource.discover(mapOf(GrowattSource.MODE to GrowattSource.MODE_TOKEN, GrowattSource.TOKEN to "t"), http)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("ShinePhone password"))
    }

    @Test
    fun `password discovery finds the SPF and remembers its plant`() = runBlocking {
        val http = FakeHttp()
            .on("newTwoLoginAPI.do", ok(fixture("growatt/classic_login_ok.json"), "Set-Cookie" to "JSESSIONID=abc"))
            .on("getAllDeviceList", ok(fixture("growatt/classic_devices.json")))
        val devices = GrowattSource.discover(
            mapOf(GrowattSource.MODE to GrowattSource.MODE_PASSWORD, GrowattSource.USER to "u", GrowattSource.PASSWORD_HASH to "h"), http
        ).getOrThrow()
        assertEquals(listOf("SPF0000001"), devices.map { it.sn })
        assertEquals(GrowattSource.Device(false, GrowattSource.TYPE_STORAGE, "SPF0000001", "1234567"), GrowattSource.Device.parse(devices[0].ref))
    }

    @Test
    fun `regional servers keep their host`() {
        assertEquals("https://openapi-us.growatt.com/", GrowattSource.baseUrl("openapi-us.growatt.com"))
        assertEquals("https://openapi.growatt.com/", GrowattSource.baseUrl(" "))
        assertEquals("http://server.smten.com/", GrowattSource.baseUrl("http://server.smten.com/"))
        assertNotNull(GrowattSource.Device.parse("classic|tlx|SN1|42"))
    }
}
