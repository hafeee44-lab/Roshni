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
}
