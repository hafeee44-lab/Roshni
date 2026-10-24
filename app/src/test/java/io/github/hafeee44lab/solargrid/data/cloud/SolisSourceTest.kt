package io.github.hafeee44lab.solargrid.data.cloud

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolisSourceTest {

    private val fields = mapOf(SolisSource.KEY_ID to "1300386381676999999", SolisSource.KEY_SECRET to "test-secret")

    @Test
    fun `signature matches the reference HMAC-SHA1 scheme`() {
        // Worked out with Python's hmac/hashlib exactly as solis-sensor's _prepare_header does.
        val body = """{"id":"1308675217944611083","sn":"1031000000000001"}"""
        val md5 = SolisSource.contentMd5(body)
        assertEquals("sW0IXuJASMfNHGI2fueKBg==", md5)
        assertEquals("hIZwQw21R2PNwiDrBY6c7tpkfo8=", SolisSource.sign("test-secret", md5, "Sat, 24 Oct 2026 07:30:00 GMT", "/v1/api/inverterDetail"))
        assertEquals("Sat, 24 Oct 2026 07:30:00 GMT", SolisSource.httpDate(1792827000000L))
    }

    @Test
    fun `fetch posts a signed inverterDetail request`() = runBlocking {
        val http = FakeHttp().on("inverterDetail", ok(fixture("solis/detail_grid_on.json")))
        val account = BrandAccount(CloudBrand.SOLIS, fields, "1308675217944611083|1031000000000001")
        val result = SolisSource(account, MemorySession(), http) { 1792827000000L }.fetch()
        assertTrue(result is FetchResult.Ok)
        val sent = http.sent.single()
        assertEquals("https://www.soliscloud.com:13333/v1/api/inverterDetail", sent.url)
        assertEquals("""{"id":"1308675217944611083","sn":"1031000000000001"}""", sent.body)
        assertEquals("API 1300386381676999999:hIZwQw21R2PNwiDrBY6c7tpkfo8=", sent.headers["Authorization"])
        assertEquals("Sat, 24 Oct 2026 07:30:00 GMT", sent.headers["Date"])
    }

    @Test
    fun `detail maps with kW converted and Roshni's signs`() {
        val result = SolisSource.parseDetail(ok(fixture("solis/detail_grid_on.json"))) as FetchResult.Ok
        val reading = GridAnalyzer.read(result.metrics)
        assertEquals(listOf(231.2), reading.gridPhaseVolts)
        assertEquals(480.0, reading.gridPowerW!!, 0.01)
        assertEquals(1200.0, reading.batteryPowerW!!, 0.01)
        assertEquals(66.0, reading.soc!!, 0.01)
        assertEquals(1680.0, reading.loadPowerW!!, 0.01)
        assertEquals(620.0, reading.solarPowerW!!, 0.01)
        assertEquals(3.2, reading.today.boughtKwh!!, 0.01)
        assertEquals(11.5, reading.today.consumedKwh!!, 0.01)
        assertEquals(1792827000000L, result.collectedAt)
        assertFalse(result.inverterAlarm)
        assertFalse(CloudFakes.verdict(result)!!.gridDown)
    }

    @Test
    fun `a cut reads grid down and the alarm state is passed on`() {
        val result = SolisSource.parseDetail(ok(fixture("solis/detail_grid_off.json"))) as FetchResult.Ok
        assertTrue(CloudFakes.verdict(result)!!.gridDown)
        assertTrue(result.inverterAlarm)
        assertEquals(2140.0, GridAnalyzer.read(result.metrics).batteryPowerW!!, 0.01)
    }

    @Test
    fun `offline state, refused keys, a wrong clock and rejections`() {
        assertEquals(FetchResult.LoggerOffline, SolisSource.parseDetail(ok(fixture("solis/detail_offline.json"))))
        assertTrue((SolisSource.parseDetail(HttpReply(403, "")) as FetchResult.Failed).authProblem)
        val clock = SolisSource.parseDetail(HttpReply(408, "")) as FetchResult.Failed
        assertTrue(clock.message.contains("clock"))
        assertFalse((SolisSource.parseDetail(ok(fixture("solis/rejected.json"))) as FetchResult.Failed).authProblem)
    }

    @Test
    fun `discovery lists inverters with their id and serial`() = runBlocking {
        val http = FakeHttp().on("inverterList", ok(fixture("solis/list.json")))
        val devices = SolisSource.discover(fields + (SolisSource.API_URL to "https://v3.soliscloud.com:13333/"), http, 0L).getOrThrow()
        assertEquals("1031000000000001", devices.single().sn)
        assertEquals("Ghar", devices.single().station)
        assertEquals("1308675217944611083|1031000000000001", devices.single().ref)
        assertEquals("https://v3.soliscloud.com:13333/v1/api/inverterList", http.sent.single().url)
    }
}
