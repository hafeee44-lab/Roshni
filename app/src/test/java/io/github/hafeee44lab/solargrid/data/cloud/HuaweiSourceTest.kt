package io.github.hafeee44lab.solargrid.data.cloud

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiSourceTest {

    private val plan = HuaweiSource.Plan(
        station = "NE=33607111",
        grid = HuaweiSource.Group(HuaweiSource.ROLE_GRID, HuaweiSource.TYPE_POWER_SENSOR, "1000000033607914"),
        battery = HuaweiSource.Group(HuaweiSource.ROLE_BATTERY, HuaweiSource.TYPE_BATTERY, "1000000033607999"),
        inverter = HuaweiSource.Group(HuaweiSource.ROLE_INVERTER, HuaweiSource.TYPE_RESIDENTIAL_INVERTER, "1000000033607000")
    )

    private fun account(p: HuaweiSource.Plan = plan) = BrandAccount(
        CloudBrand.HUAWEI,
        mapOf(HuaweiSource.USER to "roshni_api", HuaweiSource.SYSTEM_CODE to "s3cret", HuaweiSource.SERVER to "eu5.fusionsolar.huawei.com"),
        p.encode()
    )

    private fun loginReply() = ok(fixture("huawei/login_ok.json"), "xsrf-token" to "tok-1")

    private fun map(file: String) = (HuaweiSource.parseRealKpi(ok(fixture("huawei/$file"))) as HuaweiSource.KpiResult.Ok).items.values.first()

    @Test
    fun `a full set maps meter, battery and inverter with Roshni's signs`() {
        val result = HuaweiSource.compose(
            plan,
            mapOf(
                HuaweiSource.ROLE_GRID to map("kpi_meter_on.json"),
                HuaweiSource.ROLE_BATTERY to map("kpi_battery.json"),
                HuaweiSource.ROLE_INVERTER to map("kpi_inverter.json")
            )
        ) as FetchResult.Ok
        val reading = GridAnalyzer.read(result.metrics)
        assertEquals(231.4, reading.gridVoltage!!, 0.01)
        assertEquals(50.01, reading.gridFrequency!!, 0.001)
        // The power sensor's -650 W is buying from the grid: +650 in Roshni.
        assertEquals(650.0, reading.gridPowerW!!, 0.01)
        // The battery's -1800 W is discharging: +1800 in Roshni.
        assertEquals(1800.0, reading.batteryPowerW!!, 0.01)
        assertEquals(72.0, reading.soc!!, 0.01)
        assertEquals(350.0, reading.solarPowerW!!, 0.01)
        assertEquals(2800.0, reading.loadPowerW!!, 0.01)
        assertEquals(11.2, reading.today.producedKwh!!, 0.01)
        assertEquals(3.4, reading.today.dischargedKwh!!, 0.01)
        assertFalse(CloudFakes.verdict(result)!!.gridDown)
    }

    @Test
    fun `a dead power sensor reads grid down`() {
        val result = HuaweiSource.compose(plan, mapOf(HuaweiSource.ROLE_GRID to map("kpi_meter_off.json"), HuaweiSource.ROLE_BATTERY to map("kpi_battery.json")))
        assertTrue(CloudFakes.verdict(result)!!.gridDown)
    }

    @Test
    fun `a disconnected grid device means the dongle is offline`() {
        val noMeter = plan.copy(grid = null)
        assertEquals(FetchResult.LoggerOffline, HuaweiSource.compose(noMeter, mapOf(HuaweiSource.ROLE_INVERTER to map("kpi_inverter_disconnected.json"))))
    }

    @Test
    fun `without a power sensor the inverter's AC side stands in`() {
        val result = HuaweiSource.compose(plan.copy(grid = null), mapOf(HuaweiSource.ROLE_INVERTER to map("kpi_inverter.json"))) as FetchResult.Ok
        val reading = GridAnalyzer.read(result.metrics)
        assertEquals(230.9, reading.gridVoltage!!, 0.01)
        assertNull(reading.gridPowerW)
    }

    @Test
    fun `polls take turns, grid every other one`() {
        assertEquals(listOf("grid", "battery", "grid", "inverter", "grid", "battery"), (0..5).map { plan.next(it).role })
        val noMeter = plan.copy(grid = null)
        assertEquals(listOf("inverter", "battery", "inverter", "battery"), (0..3).map { noMeter.next(it).role })
        assertEquals(listOf("inverter", "inverter"), (0..1).map { noMeter.copy(battery = null).next(it).role })
        assertEquals(plan, HuaweiSource.Plan.parse(plan.encode()))
    }

    @Test
    fun `each poll makes one data call and fills in the rest from memory`() = runBlocking {
        val http = FakeHttp()
            .on("thirdData/login", loginReply())
            .on("getDevRealKpi", ok(fixture("huawei/kpi_meter_on.json")), ok(fixture("huawei/kpi_battery.json")))
        val memory = HuaweiSource.Memory()
        var now = 0L
        val session = MemorySession()
        val source = HuaweiSource(account(), session, http, memory) { now }

        val first = source.fetch() as FetchResult.Ok
        assertNull(GridAnalyzer.read(first.metrics).soc)
        now += 2 * 60_000L
        val second = source.fetch() as FetchResult.Ok
        val reading = GridAnalyzer.read(second.metrics)
        assertEquals(72.0, reading.soc!!, 0.01)
        assertEquals(231.4, reading.gridVoltage!!, 0.01)

        val calls = http.sent.filter { it.url.endsWith("getDevRealKpi") }
        assertEquals(2, calls.size)
        assertEquals("""{"devIds":"1000000033607914","devTypeId":47}""", calls[0].body)
        assertEquals("""{"devIds":"1000000033607999","devTypeId":39}""", calls[1].body)
        assertTrue(calls.all { it.headers["xsrf-token"] == "tok-1" })
        assertEquals(1, http.sent.count { it.url.endsWith("thirdData/login") })
        assertEquals("https://eu5.fusionsolar.huawei.com/thirdData/login", http.sent[0].url)
        assertEquals("""{"userName":"roshni_api","systemCode":"s3cret"}""", http.sent[0].body)
    }

    @Test
    fun `old readings from memory are dropped rather than shown as current`() = runBlocking {
        val http = FakeHttp()
            .on("thirdData/login", loginReply())
            .on("getDevRealKpi", ok(fixture("huawei/kpi_battery.json")), ok(fixture("huawei/kpi_meter_off.json")))
        val memory = HuaweiSource.Memory()
        memory.turn(plan.key) // start on the battery's turn
        var now = 0L
        val source = HuaweiSource(account(), MemorySession(), http, memory) { now }
        source.fetch()
        now += HuaweiSource.Memory.MAX_AGE_MS + 1
        val reading = GridAnalyzer.read((source.fetch() as FetchResult.Ok).metrics)
        assertNull(reading.soc)
        assertEquals(0.0, reading.gridVoltage!!, 0.01)
    }

    @Test
    fun `an expired session signs in again once`() = runBlocking {
        val http = FakeHttp()
            .on("thirdData/login", ok(fixture("huawei/login_ok.json"), "xsrf-token" to "tok-2"))
            .on("getDevRealKpi", ok(fixture("huawei/fail_305.json")), ok(fixture("huawei/kpi_meter_on.json")))
        val session = MemorySession().apply { session = "tok-old" to Long.MAX_VALUE }
        val result = HuaweiSource(account(), session, http, HuaweiSource.Memory()).fetch()
        assertTrue(result is FetchResult.Ok)
        assertEquals("tok-2", session.session!!.first)
        assertEquals("tok-2", http.sent.last().headers["xsrf-token"])
    }

    @Test
    fun `too frequent is transient, bad credentials are an auth problem`() = runBlocking {
        val limited = HuaweiSource(
            account(), MemorySession(),
            FakeHttp().on("thirdData/login", loginReply()).on("getDevRealKpi", ok(fixture("huawei/fail_407.json"))),
            HuaweiSource.Memory()
        ).fetch() as FetchResult.Failed
        assertFalse(limited.authProblem)
        assertFalse(limited.quotaExhausted)

        val bad = HuaweiSource(account(), MemorySession(), FakeHttp().on("thirdData/login", ok(fixture("huawei/login_bad.json"))), HuaweiSource.Memory())
            .fetch() as FetchResult.Failed
        assertTrue(bad.authProblem)
    }

    @Test
    fun `discovery offers each inverter with its plant's battery and power sensor`() = runBlocking {
        val http = FakeHttp()
            .on("thirdData/login", loginReply())
            .on("thirdData/stations", ok(fixture("huawei/stations.json")))
            .on("thirdData/getDevList", ok(fixture("huawei/devlist.json")))
        val devices = HuaweiSource.discover(mapOf(HuaweiSource.USER to "u", HuaweiSource.SYSTEM_CODE to "p"), http).getOrThrow()
        assertEquals(1, devices.size)
        assertEquals("HV2150000001", devices[0].sn)
        assertEquals("Ghar", devices[0].station)
        assertEquals(plan, HuaweiSource.Plan.parse(devices[0].ref))
        assertEquals("""{"stationCodes":"NE=33607111"}""", http.sent.last().body)
    }
}
