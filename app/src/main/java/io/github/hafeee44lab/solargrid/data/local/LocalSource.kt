package io.github.hafeee44lab.solargrid.data.local

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.ReadingSource
import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A Solarman Wi-Fi logger on the home network. */
data class LocalLogger(
    val host: String,
    val serial: Long,
    val port: Int = SolarmanV5.PORT,
    val modbusAddress: Int = 1
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && serial in 1..SolarmanV5.MAX_SERIAL

    companion object {
        /** The stick's serial is printed on its label; it is usually ten digits. */
        fun parseSerial(text: String): Long? =
            text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 10 }
                ?.toLongOrNull()?.takeIf { it in 1..SolarmanV5.MAX_SERIAL }

        fun isPlausibleHost(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return false
            val parts = t.split('.')
            val looksNumeric = parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) }
            return if (looksNumeric) parts.size == 4 && parts.all { it.length <= 3 && it.toInt() in 0..255 } else t.length <= 253
        }
    }
}

/** One TCP conversation with the logger. Separate from [LocalSource] so tests can fake a logger. */
interface LoggerLink : Closeable {
    fun send(frame: ByteArray)
    fun readFrame(): ByteArray
}

/** Connecting failed outright: wrong address, or this phone is not on that network. */
class LoggerUnreachable(message: String) : IOException(message)

/** The inverter behind the logger is not one we have a register map for. */
class UnsupportedInverter(message: String) : IOException(message)

class SocketLink(
    host: String,
    port: Int,
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = READ_TIMEOUT_MS
) : LoggerLink {

    private val socket = Socket().also { s ->
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            s.tcpNoDelay = true
        } catch (e: IOException) {
            s.close()
            throw LoggerUnreachable(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }
    private val input: InputStream = BufferedInputStream(socket.getInputStream())
    private val output: OutputStream = socket.getOutputStream()

    override fun send(frame: ByteArray) {
        output.write(frame)
        output.flush()
    }

    override fun readFrame(): ByteArray = SolarmanV5.readFrame(input)

    override fun close() = socket.close()

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 6_000
    }
}

/**
 * Reads the inverter straight from its Solarman logger over the LAN: no App ID, no secret and no
 * cloud allowance. Each poll opens one connection, reads every register block, and closes it;
 * a failed poll is retried once on a fresh connection.
 */
class LocalSource(
    private val logger: LocalLogger,
    private val open: (LocalLogger) -> LoggerLink = { SocketLink(it.host, it.port) },
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val now: () -> Long = System::currentTimeMillis
) : ReadingSource {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        var failure: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            if (attempt > 0) delay(retryDelayMs)
            try {
                return@withContext FetchResult.Ok(metrics = readOnce(), collectedAt = now(), inverterAlarm = false)
            } catch (e: UnsupportedInverter) {
                return@withContext FetchResult.Failed(e.message ?: "Unsupported inverter")
            } catch (e: IOException) {
                failure = e
            }
        }
        FetchResult.Failed(describe(failure))
    }

    private fun readOnce(): List<SolarMetric> = open(logger).use { link ->
        val session = Session(link)
        val key = "${logger.host}/${logger.serial}"
        val layout = layouts[key] ?: detect(session).also { layouts[key] = it }

        val registers = HashMap<Int, Int>()
        for (block in layout.blocks) {
            val values = try {
                session.read(block.start, block.count)
            } catch (e: ModbusException) {
                if (block.required) throw e else continue
            }
            values.forEachIndexed { i, v -> registers[block.start + i] = v }
        }
        DeyeRegisters.metrics(layout, registers)
    }

    private fun detect(session: Session): DeyeLayout {
        val type = session.read(DeyeLayout.DEVICE_TYPE_REGISTER, 1)[0]
        return DeyeLayout.fromDeviceType(type)
            ?: throw UnsupportedInverter(
                "This inverter (Deye device type 0x%04X) can't be read on the local network yet. Use Solarman cloud instead.".format(type)
            )
    }

    private inner class Session(private val link: LoggerLink) {
        private var sequence = Random.nextInt(1, 255)

        fun read(start: Int, count: Int): IntArray {
            sequence = (sequence + 1) and 0xFF
            val request = ModbusRtu.readHoldingRequest(logger.modbusAddress, start, count)
            link.send(SolarmanV5.encode(logger.serial, sequence, request))

            // Loggers slip in heartbeats and late answers to someone else's request; skip those.
            repeat(MAX_UNRELATED_FRAMES) {
                when (val decoded = SolarmanV5.decode(link.readFrame(), logger.serial, sequence)) {
                    is SolarmanV5.Decoded.Reply -> return ModbusRtu.parseReadResponse(decoded.modbus, logger.modbusAddress, count)
                    is SolarmanV5.Decoded.Unrelated -> Unit
                }
            }
            throw LocalProtocolException("The logger kept sending frames that were not our answer")
        }
    }

    private fun describe(e: IOException?): String = when (e) {
        is LoggerUnreachable -> "Couldn't reach the logger at ${logger.host}. Is this phone on the same Wi-Fi?"
        is SocketTimeoutException -> "The logger at ${logger.host} didn't answer. Check the serial number: it is the one on the Wi-Fi stick, not the inverter's."
        is LocalProtocolException -> e.message ?: "The logger sent something we could not read"
        null -> "The logger did not answer"
        else -> "Local network error: ${e.localizedMessage ?: e.javaClass.simpleName}"
    }

    companion object {
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_500L
        private const val MAX_UNRELATED_FRAMES = 8

        /** Which register layout each logger leads to, so register 0 is read once per app run. */
        private val layouts = ConcurrentHashMap<String, DeyeLayout>()

        internal fun forgetLayouts() = layouts.clear()
    }
}
