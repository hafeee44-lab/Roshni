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

/**
 * A Solarman Wi-Fi logger on the home network. [profileId] names the inverter profile the user
 * picked; null means detect it from Deye's device-type register.
 */
data class LocalLogger(
    val host: String,
    val serial: Long,
    val port: Int = SolarmanV5.PORT,
    val modbusAddress: Int = 1,
    val profileId: String? = null
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

/** What reading Deye's device-type register found. */
sealed interface Detection {
    data class Found(val profile: InverterProfile) : Detection
    /** It answered, but with a code no bundled profile claims (or a non-Deye inverter). */
    data class Unknown(val deviceType: Int) : Detection
    data class Failed(val message: String) : Detection
}

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
    private val catalog: ProfileCatalog,
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

    /**
     * Reads Deye's device-type register once, for the model picker to pre-select a profile. On
     * other brands register 0 means something else, so only codes a profile claims count.
     */
    suspend fun detect(): Detection = withContext(Dispatchers.IO) {
        var failure: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            if (attempt > 0) delay(retryDelayMs)
            try {
                val type = open(logger).use { Session(it).read(ModbusRtu.READ_HOLDING, ProfileCatalog.DEVICE_TYPE_REGISTER, 1)[0] }
                return@withContext catalog.detect(type)?.let { Detection.Found(it) } ?: Detection.Unknown(type)
            } catch (e: ModbusException) {
                return@withContext Detection.Unknown(-1)
            } catch (e: IOException) {
                failure = e
            }
        }
        Detection.Failed(describe(failure))
    }

    private fun readOnce(): List<SolarMetric> = open(logger).use { link ->
        val session = Session(link)
        val profile = logger.profileId?.let {
            catalog.byId(it) ?: throw UnsupportedInverter("The inverter model saved for this logger is no longer available. Pick it again in Settings.")
        } ?: run {
            val key = "${logger.host}/${logger.serial}"
            profiles[key]?.let(catalog::byId) ?: detect(session).also { profiles[key] = it.id }
        }

        val registers = HashMap<Int, Int>()
        for (block in profile.blocks) {
            val values = try {
                session.read(block.function, block.start, block.count)
            } catch (e: ModbusException) {
                if (block.required) throw e else continue
            }
            values.forEachIndexed { i, v -> registers[InverterProfile.key(block.function, block.start + i)] = v }
        }
        profile.read(registers)
    }

    private fun detect(session: Session): InverterProfile {
        val type = session.read(ModbusRtu.READ_HOLDING, ProfileCatalog.DEVICE_TYPE_REGISTER, 1)[0]
        return catalog.detect(type)
            ?: throw UnsupportedInverter(
                "Couldn't recognise this inverter (device type 0x%04X). Pick its model in Settings → Data source.".format(type)
            )
    }

    private inner class Session(private val link: LoggerLink) {
        private var sequence = Random.nextInt(1, 255)

        fun read(function: Int, start: Int, count: Int): IntArray {
            sequence = (sequence + 1) and 0xFF
            val request = ModbusRtu.readRequest(logger.modbusAddress, function, start, count)
            link.send(SolarmanV5.encode(logger.serial, sequence, request))

            // Loggers slip in heartbeats and late answers to someone else's request; skip those.
            repeat(MAX_UNRELATED_FRAMES) {
                when (val decoded = SolarmanV5.decode(link.readFrame(), logger.serial, sequence)) {
                    is SolarmanV5.Decoded.Reply -> return ModbusRtu.parseReadResponse(decoded.modbus, logger.modbusAddress, count, function)
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

        /** Which profile each auto-detected logger leads to, so register 0 is read once per app run. */
        private val profiles = ConcurrentHashMap<String, String>()

        internal fun forgetLayouts() = profiles.clear()
    }
}
