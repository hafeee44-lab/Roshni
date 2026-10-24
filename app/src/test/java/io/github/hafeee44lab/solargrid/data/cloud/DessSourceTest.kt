package io.github.hafeee44lab.solargrid.data.cloud

import com.google.gson.JsonParser
import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DessSourceTest {

    private val realUtc = 1792827000000L // 2026-10-24 07:30:00 UTC, stamped by the server as 15:30 (UTC+8)
    private val hour = 60 * 60_000L

    private val device = DessSource.Device("W0012345678901", "2341", "1", "96342210100001")
    private val account = BrandAccount(
        CloudBrand.DESS,
        mapOf(DessSource.USER to "owner@example.com", DessSource.PASSWORD_SHA1 to DessSource.sha1("hunter2")),
        device.encode()
    )

    /** The recorded replies are queryDeviceLastData's flat title/val/unit list. */
    private fun recorded(file: String) = JsonParser.parseString(fixture("dess/$file")).asJsonObject.getAsJsonArray("dat").map {
        val o = it.asJsonObject
        Triple(o.get("title").asString, o.get("val")?.asString, o.get("unit")?.asString)
    }

    private fun parse(file: String, now: Long = realUtc + 4 * 60_000L, zones: DessSource.ServerZone = DessSource.ServerZone()) =
        DessSource.parseLastData(ok(fixture("dess/$file")), now, device.pn, zones)

    @Test
    fun `signatures match sha1 of salt, key and action string`() {
        assertEquals("f3bbbd66a63d4bf1747940578ec3d0103530e21d", DessSource.sha1("hunter2"))
        val login = DessSource.signedUrl(
            listOf("action" to "authSource", "usr" to "owner@example.com", "source" to "1", "company-key" to "bnrl_frRFjEz8Mkn"),
            realUtc, passwordSha1 = DessSource.sha1("hunter2")
        )
        assertEquals(
            "https://api.dessmonitor.com/public/?sign=baa5dad35fb875ca67f426e9dbfff3299f04c82e&salt=1792827000000" +
                "&action=authSource&usr=owner@example.com&source=1&company-key=bnrl_frRFjEz8Mkn",
            login
        )
        val data = DessSource.signedUrl(
            listOf("action" to "querySPDeviceLastData", "i18n" to "en_US", "source" to "1", "pn" to device.pn, "devcode" to "2341", "devaddr" to "1", "sn" to device.sn),
            realUtc, DessSource.Auth("tok0123456789abcdef", "9f4c2e1d5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d")
        )
        assertTrue(data.startsWith("https://api.dessmonitor.com/public/?sign=175b189d0e8ef3011258a65b42f15d3b01dd2ac7&salt=1792827000000&token=tok0123456789abcdef&action=querySPDeviceLastData"))
    }

    @Test
    fun `recorded PowMr on SBU with the mains present is not a cut`() {
        val reading = GridAnalyzer.read(DessSource.metrics(recorded("recorded_devcode_6416_query_device_last_data.json")))
        assertEquals(237.1, reading.gridVoltage!!, 0.01)
        assertEquals(49.9, reading.gridFrequency!!, 0.01)
        assertEquals(72.0, reading.soc!!, 0.01)
        // 1 A discharging at 53.4 V.
        assertEquals(53.4, reading.batteryPowerW!!, 0.01)
        assertEquals(787.0, reading.solarPowerW!!, 0.01)
        assertEquals(781.0, reading.loadPowerW!!, 0.01)
        assertFalse(GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun `recorded unit in Line Mode reads grid up`() {
        val reading = GridAnalyzer.read(DessSource.metrics(recorded("recorded_devcode_2477_query_device_last_data.json")))
        assertEquals(244.8, reading.gridVoltage!!, 0.01)
        assertEquals(100.0, reading.soc!!, 0.01)
        assertEquals(2534.0, reading.solarPowerW!!, 0.01)
        assertEquals(0.0, reading.batteryPowerW!!, 0.01)
        assertFalse(GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun `an Axpert with the mains cut reads grid down, discharging`() {
        val result = parse("sp_axpert_cut.json")
        assertTrue(CloudFakes.verdict(result)!!.gridDown)
        val reading = GridAnalyzer.read((result as FetchResult.Ok).metrics)
        assertEquals(900.0, reading.batteryPowerW!!, 0.01)
        assertEquals(64.0, reading.soc!!, 0.01)
    }

    @Test
    fun `collectors with one signed battery current read it the right way round`() {
        val result = parse("sp_signed_current_cut.json")
        assertTrue(CloudFakes.verdict(result)!!.gridDown)
        assertEquals(2040.0, GridAnalyzer.read((result as FetchResult.Ok).metrics).batteryPowerW!!, 0.01)
    }

    @Test
    fun `gts in the server's zone is brought back to real time`() {
        val result = parse("sp_axpert_mains.json") as FetchResult.Ok
        assertEquals(realUtc, result.collectedAt)
        val reading = GridAnalyzer.read(result.metrics)
        assertEquals(-648.0, reading.batteryPowerW!!, 0.01)
        assertFalse(GridAnalyzer.evaluate(reading)!!.gridDown)
    }

    @Test
    fun `a first reading that is already old doesn't make later old readings look fresh`() {
        val zones = DessSource.ServerZone()
        // First seen 3 hours late, then a fresh one: the fresh one fixes the offset.
        zones.correct("pn", realUtc + 8 * hour, realUtc + 3 * hour)
        assertEquals(realUtc, zones.correct("pn", realUtc + 8 * hour, realUtc + 2 * 60_000L))
        // A stale reading after that shows its true age.
        assertEquals(realUtc, zones.correct("pn", realUtc + 8 * hour, realUtc + 2 * hour))
    }

    @Test
    fun `fetch signs in once, stores the session, and re-signs on err 10`() = runBlocking {
        var now = realUtc
        val http = FakeHttp()
            .on("action=authSource", ok(fixture("dess/auth_ok.json")))
            .on("action=querySPDeviceLastData", ok(fixture("dess/err_token.json")), ok(fixture("dess/sp_axpert_mains.json")))
        val session = MemorySession()
        val result = DessSource(account, session, http, { now }, DessSource.ServerZone()).fetch()
        assertTrue(result is FetchResult.Ok)
        assertEquals(2, http.sent.count { it.url.contains("action=authSource") })
        assertEquals("tok0123456789abcdef\n9f4c2e1d5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d", session.session!!.first)
        assertTrue(session.session!!.second > now + 6 * 24 * hour)
        assertTrue(http.sent.last().url.contains("&token=tok0123456789abcdef&action=querySPDeviceLastData"))
    }

    @Test
    fun `a wrong password is an auth problem`() = runBlocking {
        val http = FakeHttp().on("action=authSource", ok(fixture("dess/auth_bad.json")))
        val result = DessSource(account, MemorySession(), http, { realUtc }).fetch() as FetchResult.Failed
        assertTrue(result.authProblem)
    }

    @Test
    fun `discovery lists the collector's inverter`() = runBlocking {
        val http = FakeHttp()
            .on("action=authSource", ok(fixture("dess/auth_ok.json")))
            .on("action=webQueryDeviceEs", ok(fixture("dess/devices.json")))
        val devices = DessSource.discover(account.fields, http, realUtc).getOrThrow()
        assertEquals("96342210100001", devices.single().sn)
        assertEquals("Inverex Nitrox", devices.single().station)
        assertEquals(true, devices.single().online)
        assertEquals(device, DessSource.Device.parse(devices.single().ref))
    }
}
