package io.github.hafeee44lab.solargrid.data.local

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads a real logger, read-only (Modbus function 0x03 only), and prints what Roshni would see.
 * Skipped unless both are set:
 *   ROSHNI_LOGGER_IP=192.168.x.y ROSHNI_LOGGER_SERIAL=27xxxxxxxx ./gradlew :app:testDebugUnitTest --tests '*LiveLoggerProbe*'
 */
class LiveLoggerProbe {

    @Test
    fun readTheRealLogger() = runBlocking {
        val ip = System.getenv("ROSHNI_LOGGER_IP")
        val serial = System.getenv("ROSHNI_LOGGER_SERIAL")?.let { LocalLogger.parseSerial(it) }
        assumeTrue("set ROSHNI_LOGGER_IP and ROSHNI_LOGGER_SERIAL to run", ip != null && serial != null)

        when (val result = LocalSource(LocalLogger(ip!!, serial!!)).fetch()) {
            is FetchResult.Ok -> {
                val reading = GridAnalyzer.read(result.metrics)
                println("LIVE metrics:\n" + reading.dump)
                println("LIVE verdict: " + GridAnalyzer.evaluate(reading))
            }
            else -> println("LIVE failed: $result")
        }
    }

    /** The same read-only requests LocalSource makes, printed raw, for checking the register map. */
    @Test
    fun dumpRawRegisters() {
        val ip = System.getenv("ROSHNI_LOGGER_IP")
        val serial = System.getenv("ROSHNI_LOGGER_SERIAL")?.let { LocalLogger.parseSerial(it) }
        assumeTrue("set ROSHNI_LOGGER_IP and ROSHNI_LOGGER_SERIAL to run", ip != null && serial != null)

        SocketLink(ip!!, SolarmanV5.PORT).use { link ->
            var sequence = 0x20
            val blocks = listOf(RegisterBlock(DeyeLayout.DEVICE_TYPE_REGISTER, 1)) + DeyeLayout.THREE_PHASE_LV.blocks
            for (block in blocks) {
                sequence++
                link.send(SolarmanV5.encode(serial!!, sequence, ModbusRtu.readHoldingRequest(1, block.start, block.count)))
                var values: IntArray? = null
                repeat(8) {
                    if (values == null) {
                        val decoded = SolarmanV5.decode(link.readFrame(), serial, sequence)
                        if (decoded is SolarmanV5.Decoded.Reply) values = ModbusRtu.parseReadResponse(decoded.modbus, 1, block.count)
                    }
                }
                val line = values!!.withIndex().joinToString(" ") { (i, v) -> "%d=%d".format(block.start + i, if (v >= 0x8000) v - 0x10000 else v) }
                println("RAW ${block.start}..${block.end}: $line")
            }
        }
    }
}
