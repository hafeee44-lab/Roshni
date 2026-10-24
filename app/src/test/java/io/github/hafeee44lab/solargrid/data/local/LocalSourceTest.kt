package io.github.hafeee44lab.solargrid.data.local

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * A pretend Solarman stick: it decodes our V5 requests and answers from a register image, the way
 * pysolarmanv5's MockDatalogger (tests/setup_test.py) does, including its heartbeats and doubled CRCs.
 */
private class FakeLogger(
    private val serial: Long,
    private val registers: Map<Int, Int>,
    private val inputRegisters: Map<Int, Int> = emptyMap(),
    private val refuse: Set<Int> = emptySet(),
    private val heartbeatFirst: Boolean = false,
    private val doubleCrc: Boolean = false,
    private val silent: Boolean = false
) : LoggerLink {
    private val pending = ArrayDeque<ByteArray>()
    val requests = mutableListOf<Pair<Int, Int>>()
    val functions = mutableListOf<Int>()
    var closed = false

    override fun send(frame: ByteArray) {
        assertEquals(frame[frame.size - 2].toInt() and 0xFF, SolarmanV5.checksum(frame, 1, frame.size - 2))
        val sequence = frame[5].toInt() and 0xFF
        val modbus = frame.copyOfRange(26, frame.size - 2)
        val function = modbus[1].toInt()
        assertTrue("read requests only", function == 3 || function == 4)
        functions += function
        val image = if (function == 4) inputRegisters else registers
        val start = ((modbus[2].toInt() and 0xFF) shl 8) or (modbus[3].toInt() and 0xFF)
        val count = ((modbus[4].toInt() and 0xFF) shl 8) or (modbus[5].toInt() and 0xFF)
        requests += start to count
        if (silent) return

        if (heartbeatFirst) pending += hex("a5 01 00 10 47 8d 69 b5 b5 0a a2 00 64 15")
        val reply = if (start in refuse) {
            ModbusRtu.withCrc(byteArrayOf(1, (0x80 or function).toByte(), 2))
        } else {
            val body = ByteArray(3 + 2 * count)
            body[0] = 1
            body[1] = function.toByte()
            body[2] = (2 * count).toByte()
            for (i in 0 until count) {
                val v = image[start + i] ?: 0
                body[3 + 2 * i] = (v ushr 8).toByte()
                body[4 + 2 * i] = v.toByte()
            }
            ModbusRtu.withCrc(body).let { if (doubleCrc) it + byteArrayOf(0, 0) else it }
        }
        pending += v5Reply(sequence, reply)
    }

    override fun readFrame(): ByteArray = pending.removeFirstOrNull() ?: throw SocketTimeoutException("silent")

    override fun close() {
        closed = true
    }

    private fun v5Reply(sequence: Int, modbus: ByteArray): ByteArray {
        val length = 14 + modbus.size
        val f = ByteArray(13 + length)
        f[0] = 0xA5.toByte()
        f[1] = length.toByte()
        f[2] = (length ushr 8).toByte()
        f[3] = 0x10
        f[4] = 0x15
        f[5] = sequence.toByte()
        f[6] = 0x42
        for (i in 0 until 4) f[7 + i] = (serial ushr (8 * i)).toByte()
        f[11] = 0x02
        f[12] = 0x01
        modbus.copyInto(f, 25)
        f[f.size - 2] = SolarmanV5.checksum(f, 1, f.size - 2).toByte()
        f[f.size - 1] = 0x15
        return f
    }
}

class LocalSourceTest {

    private val logger = LocalLogger(host = "192.168.1.40", serial = 2_712_345_678L)

    private val outage = mapOf(
        0x0000 to 0x0005,
        0x0228 to 0b0001,
        0x024C to 79,
        0x024E to 1228,
        0x0256 to 0, 0x0261 to 0,
        0x028D to 1168,
        0x0066 to 110
    )

    @Before
    fun freshLayoutCache() = LocalSource.forgetLayouts()

    private fun source(vararg links: () -> LoggerLink): Pair<LocalSource, () -> Int> {
        var opened = 0
        val source = LocalSource(logger, TestProfiles.catalog, open = { links[minOf(opened++, links.lastIndex)]() }, retryDelayMs = 0L, now = { 1_000L })
        return source to { opened }
    }

    @Test
    fun onePollReadsEveryBlockOverOneConnection() = runBlocking {
        val fake = FakeLogger(logger.serial, outage)
        val (source, opened) = source({ fake })

        val result = source.fetch() as FetchResult.Ok
        assertEquals(1, opened())
        assertTrue("connection is closed after the poll", fake.closed)
        assertEquals(1_000L, result.collectedAt)
        // The exact requests the real SG05 answered on 24 Oct.
        assertEquals(listOf(0x0000 to 1, 0x0202 to 39, 0x024A to 90, 0x02B2 to 1, 0x0066 to 1), fake.requests)

        val verdict = GridAnalyzer.evaluate(GridAnalyzer.read(result.metrics))!!
        assertTrue(verdict.gridDown)
        assertEquals("grid relay open", verdict.basis)
    }

    @Test
    fun theDeviceTypeIsOnlyAskedForOnce() = runBlocking {
        val fakes = listOf(FakeLogger(logger.serial, outage), FakeLogger(logger.serial, outage))
        var i = 0
        val source = LocalSource(logger, TestProfiles.catalog, open = { fakes[i++] }, retryDelayMs = 0L)
        source.fetch()
        source.fetch()
        assertEquals(TestProfiles.profile("deye-3p-lv").blocks.size, fakes[1].requests.size)
    }

    @Test
    fun heartbeatsAndDoubledCrcsAreHandled() = runBlocking {
        val (source, _) = source({ FakeLogger(logger.serial, outage, heartbeatFirst = true, doubleCrc = true) })
        val reading = GridAnalyzer.read((source.fetch() as FetchResult.Ok).metrics)
        assertEquals(79.0, reading.soc!!, 0.001)
    }

    @Test
    fun aRefusedOptionalBlockIsSkipped() = runBlocking {
        val (source, _) = source({ FakeLogger(logger.serial, outage, refuse = setOf(0x0066)) })
        val reading = GridAnalyzer.read((source.fetch() as FetchResult.Ok).metrics)
        assertEquals(null, reading.batteryCapacityAh)
        assertEquals(1228.0, reading.batteryPowerW!!, 0.001)
    }

    @Test
    fun aFailedConnectionIsRetriedOnce() = runBlocking {
        val (source, opened) = source({ throw LoggerUnreachable("refused") }, { FakeLogger(logger.serial, outage) })
        assertTrue(source.fetch() is FetchResult.Ok)
        assertEquals(2, opened())
    }

    @Test
    fun anUnreachableLoggerSaysWhereItLooked() = runBlocking {
        val (source, opened) = source({ throw LoggerUnreachable("timeout") })
        val failed = source.fetch() as FetchResult.Failed
        assertEquals(LocalSource.ATTEMPTS, opened())
        assertTrue(failed.message, failed.message.contains("192.168.1.40"))
    }

    @Test
    fun aWrongSerialShowsUpAsSilence() = runBlocking {
        // A stick ignores frames addressed to another serial, so the read times out.
        val (source, _) = source({ FakeLogger(logger.serial, outage, silent = true) })
        val failed = source.fetch() as FetchResult.Failed
        assertTrue(failed.message, failed.message.contains("serial"))
    }

    @Test
    fun anUnsupportedInverterIsNotRetried() = runBlocking {
        val (source, opened) = source({ FakeLogger(logger.serial, mapOf(0x0000 to 0x0004)) })
        val failed = source.fetch() as FetchResult.Failed
        assertEquals(1, opened())
        assertTrue(failed.message, failed.message.contains("0x0004"))
    }

    @Test
    fun aPickedProfileSkipsDetection() = runBlocking {
        val fake = FakeLogger(logger.serial, outage)
        val picked = LocalSource(logger.copy(profileId = "deye-3p-lv"), TestProfiles.catalog, open = { fake }, retryDelayMs = 0L)
        assertTrue(picked.fetch() is FetchResult.Ok)
        assertTrue("register 0 not read", 0x0000 to 1 !in fake.requests)
    }

    @Test
    fun inputRegisterProfilesUseFunctionFour() = runBlocking {
        val solis = mapOf(33116 to 1, 33139 to 64, 33135 to 1, 33149 to 0, 33150 to 900, 33130 to 0, 33131 to 0)
        val fake = FakeLogger(logger.serial, emptyMap(), inputRegisters = solis)
        val source = LocalSource(logger.copy(profileId = "solis-rhi-1p"), TestProfiles.catalog, open = { fake }, retryDelayMs = 0L)
        val reading = GridAnalyzer.read((source.fetch() as FetchResult.Ok).metrics)
        assertEquals(setOf(4), fake.functions.toSet())
        assertEquals(true, GridAnalyzer.evaluate(reading)!!.gridDown)
        assertEquals(900.0, reading.batteryPowerW!!, 0.001)
        assertEquals(64.0, reading.soc!!, 0.001)
    }

    @Test
    fun aSavedProfileThatNoLongerExistsSaysSo() = runBlocking {
        val source = LocalSource(logger.copy(profileId = "gone"), TestProfiles.catalog, open = { FakeLogger(logger.serial, outage) }, retryDelayMs = 0L)
        val failed = source.fetch() as FetchResult.Failed
        assertTrue(failed.message, failed.message.contains("Pick it again"))
    }

    @Test
    fun detectionPreselectsDeyeModels() = runBlocking {
        fun detect(registers: Map<Int, Int>, refuse: Set<Int> = emptySet()) =
            runBlocking { LocalSource(logger, TestProfiles.catalog, open = { FakeLogger(logger.serial, registers, refuse = refuse) }, retryDelayMs = 0L).detect() }

        assertEquals("deye-3p-lv", (detect(mapOf(0 to 0x0005)) as Detection.Found).profile.id)
        assertEquals("deye-3p-hv", (detect(mapOf(0 to 0x0006)) as Detection.Found).profile.id)
        assertEquals("deye-1p", (detect(mapOf(0 to 0x0003)) as Detection.Found).profile.id)
        assertEquals(Detection.Unknown(0x1234), detect(mapOf(0 to 0x1234)))
        assertEquals(Detection.Unknown(-1), detect(emptyMap(), refuse = setOf(0)))
        val failed = LocalSource(logger, TestProfiles.catalog, open = { throw LoggerUnreachable("no") }, retryDelayMs = 0L).detect()
        assertTrue(failed is Detection.Failed)
    }
}

class LocalLoggerTest {

    @Test
    fun serials() {
        assertEquals(2_712_345_678L, LocalLogger.parseSerial(" 2712345678 "))
        assertEquals(null, LocalLogger.parseSerial("27123456789"))
        assertEquals(null, LocalLogger.parseSerial("4294967296"))
        assertEquals(null, LocalLogger.parseSerial("SS1ES122M3"))
        assertEquals(null, LocalLogger.parseSerial(""))
    }

    @Test
    fun hosts() {
        assertTrue(LocalLogger.isPlausibleHost("192.168.1.40"))
        assertTrue(LocalLogger.isPlausibleHost("logger.lan"))
        assertEquals(false, LocalLogger.isPlausibleHost("192.168.1"))
        assertEquals(false, LocalLogger.isPlausibleHost("192.168.1.300"))
        assertEquals(false, LocalLogger.isPlausibleHost("192.168. 1.4"))
    }
}

class LoggerDiscoveryTest {

    @Test
    fun aStickReplyFillsInAddressAndSerial() {
        assertEquals(
            FoundLogger(ip = "192.168.1.40", mac = "98D8631A2B3C", serial = 2_712_345_678L),
            LoggerDiscovery.parseReply("192.168.1.40,98D8631A2B3C,2712345678")
        )
    }

    @Test
    fun otherTrafficIsIgnored() {
        assertEquals(null, LoggerDiscovery.parseReply("WIFIKIT-214028-READ"))
        assertEquals(null, LoggerDiscovery.parseReply("192.168.1.40,98D8631A2B3C,HF-LPB100"))
        assertEquals(null, LoggerDiscovery.parseReply("hello,there,123"))
    }

    @Test
    fun subnetBroadcastAddress() {
        assertEquals(listOf(192, 168, 1, 255), LoggerDiscovery.subnetBroadcast(byteArrayOf(192.toByte(), 168.toByte(), 1, 23), 24)!!.map { it.toInt() and 0xFF })
        assertEquals(listOf(10, 0, 3, 255), LoggerDiscovery.subnetBroadcast(byteArrayOf(10, 0, 2, 9), 23)!!.map { it.toInt() and 0xFF })
    }
}
