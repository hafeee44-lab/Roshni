package io.github.hafeee44lab.solargrid.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

internal fun hex(text: String): ByteArray =
    text.split(' ', '\n').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

/**
 * Byte sequences captured from real loggers and posted in pysolarmanv5's issue tracker, with
 * pysolarmanv5's own debug output ("SENT:" / "RECD:"):
 *  - #108 https://github.com/jmccrohan/pysolarmanv5/issues/108 — a Deye single-phase hybrid,
 *    reading holding register 0x00BA (PV1 power) and getting 977 W back;
 *  - #51  https://github.com/jmccrohan/pysolarmanv5/issues/51 — a stale reply to an older request;
 *  - #10, #12, #101 — more request/response pairs, used for the checksum;
 *  - the heartbeat frame pysolarmanv5's test server (tests/setup_test.py) injects.
 */
class SolarmanV5Test {

    private val serial108 = 0xD63C2478L // 78 24 3c d6 on the wire
    private val sent108 = hex("a5 17 00 10 45 5e 00 78 24 3c d6 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 03 00 ba 00 01 a5 ef cd 15")
    private val recd108 = hex("a5 15 00 10 15 5e 8f 78 24 3c d6 02 01 69 5f 01 00 a9 28 00 00 39 f8 7e 6a 01 03 02 03 d1 78 e8 c5 15")
    private val inverterSilent108 = hex("a5 10 00 10 15 01 c5 78 24 3c d6 02 01 91 60 01 00 d1 29 00 00 39 f8 7e 6a 05 00 b6 15")
    private val heartbeat = hex("a5 01 00 10 47 8d 69 b5 b5 0a a2 00 64 15")

    @Test
    fun buildsTheSameRequestPysolarmanv5Sent() {
        val modbus = ModbusRtu.readHoldingRequest(slave = 1, start = 0x00BA, count = 1)
        assertArrayEquals(sent108, SolarmanV5.encode(serial108, 0x5E, modbus))
    }

    @Test
    fun decodesTheRealReplyIntoRegisterValues() {
        val decoded = SolarmanV5.decode(recd108, serial108, 0x5E) as SolarmanV5.Decoded.Reply
        assertArrayEquals(hex("01 03 02 03 d1 78 e8"), decoded.modbus)
        assertArrayEquals(intArrayOf(977), ModbusRtu.parseReadResponse(decoded.modbus, slave = 1, count = 1))
    }

    @Test
    fun everyCapturedFrameHasTheChecksumWeCompute() {
        val frames = listOf(
            sent108, recd108, inverterSilent108, heartbeat,
            hex("a5 17 00 10 45 85 00 d8 5e 18 ad 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 03 06 04 00 01 c5 43 05 15"),
            hex("a5 15 00 10 15 01 9b d8 5e 18 ad 02 01 13 f7 0a 00 66 08 00 00 56 fe a0 65 01 03 02 01 fa 39 97 80 15"),
            hex("a5 17 00 10 45 00 00 71 19 8e f3 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 04 81 7d 00 02 c9 ef 36 15"),
            hex("a5 17 00 10 15 00 20 71 19 8e f3 02 01 21 16 1e 00 a7 19 00 00 d4 39 96 62 01 04 04 00 00 00 0a 7b 83 95 15"),
            hex("a5 10 00 10 15 00 40 dd bc f4 6a 02 01 ae 38 11 00 57 0e 00 00 7b 5b a7 62 05 00 af 15"),
            hex("a5 17 00 10 15 6e a2 af a7 4e 8e 02 01 da 09 7e 01 df 65 00 00 40 20 94 67 01 03 04 00 00 09 c5 3c 30 c4 15")
        )
        for (frame in frames) {
            assertEquals(frame[frame.size - 2].toInt() and 0xFF, SolarmanV5.checksum(frame, 1, frame.size - 2))
            val length = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
            assertEquals("a frame is 13 bytes plus its payload length", 13 + length, frame.size)
        }
    }

    @Test
    fun aHeartbeatIsNotOurReply() {
        assertTrue(SolarmanV5.decode(heartbeat, serial108, 0x5E) is SolarmanV5.Decoded.Unrelated)
    }

    @Test
    fun aReplyToAnOlderRequestIsNotOurs() {
        // #51: request sequence 0x85, but the logger answered with 0xa8 from an earlier request.
        val stale = hex(
            "a5 43 00 10 15 a8 8f d8 5e 18 ad 02 01 10 f7 0a 00 63 08 00 00 56 fe a0 65 01 03 30 00 02 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 00 24 00 00 00 18 00 00 e4 c1 8b 15"
        )
        assertTrue(SolarmanV5.decode(stale, 0xAD185ED8L, 0x85) is SolarmanV5.Decoded.Unrelated)
    }

    @Test
    fun aReplyFromAnotherLoggerIsRejected() {
        expectProtocolError { SolarmanV5.decode(recd108, serial108 + 1, 0x5E) }
    }

    @Test
    fun aDamagedFrameIsRejected() {
        val damaged = recd108.copyOf().also { it[28] = 0x00 }
        expectProtocolError { SolarmanV5.decode(damaged, serial108, 0x5E) }
    }

    @Test
    fun aLoggerThatGotNoAnswerFromTheInverterIsReported() {
        // #108: a two-byte payload where the Modbus frame should be.
        expectProtocolError { SolarmanV5.decode(inverterSilent108, serial108, 0x01) }
    }

    @Test
    fun framesAreReadOffAStreamPastStrayBytes() {
        // pysolarmanv5's test server sends gibberish and an "AT+" string between frames.
        val stray = hex("41 54 2b 59 5a 0d 0a aa 03 0a 00 be 9c")
        val stream = ByteArrayInputStream(stray + heartbeat + recd108)
        assertArrayEquals(heartbeat, SolarmanV5.readFrame(stream))
        assertArrayEquals(recd108, SolarmanV5.readFrame(stream))
    }

    @Test
    fun aTruncatedFrameIsAnError() {
        try {
            SolarmanV5.readFrame(ByteArrayInputStream(recd108.copyOf(20)))
            fail("a cut-off frame must not be returned")
        } catch (e: java.io.IOException) {
            // expected
        }
    }

    @Test
    fun serialsUpToFourBytesAreAccepted() {
        val frame = SolarmanV5.encode(4_294_967_295L, 1, ModbusRtu.readHoldingRequest(1, 0, 1))
        assertArrayEquals(hex("ff ff ff ff"), frame.copyOfRange(7, 11))
    }

    private fun expectProtocolError(block: () -> Unit) {
        try {
            block()
            fail("expected a protocol error")
        } catch (e: LocalProtocolException) {
            // expected
        }
    }
}

class ModbusRtuTest {

    @Test
    fun crcMatchesTheTextbookExample() {
        assertArrayEquals(hex("01 03 00 00 00 0a c5 cd"), ModbusRtu.readHoldingRequest(1, 0, 10))
    }

    @Test
    fun crcMatchesCapturedRequests() {
        assertArrayEquals(hex("01 03 00 ba 00 01 a5 ef"), ModbusRtu.readHoldingRequest(1, 0x00BA, 1))
        assertArrayEquals(hex("01 03 06 04 00 01 c5 43"), ModbusRtu.readHoldingRequest(1, 0x0604, 1))
        assertArrayEquals(hex("01 03 11 25 00 02 d0 fc"), ModbusRtu.readHoldingRequest(1, 0x1125, 2))
    }

    @Test
    fun capturedResponsesParse() {
        assertArrayEquals(intArrayOf(0x01FA), ModbusRtu.parseReadResponse(hex("01 03 02 01 fa 39 97"), 1, 1))
        assertArrayEquals(intArrayOf(0, 0x09C5), ModbusRtu.parseReadResponse(hex("01 03 04 00 00 09 c5 3c 30"), 1, 2))
    }

    @Test
    fun aDoubledCrcIsTolerated() {
        // Some Deye/logger pairs append the CRC twice; see pysolarmanv5 issue #62.
        assertArrayEquals(intArrayOf(977), ModbusRtu.parseReadResponse(hex("01 03 02 03 d1 78 e8 00 00"), 1, 1))
    }

    @Test
    fun aBadCrcIsRejected() {
        try {
            ModbusRtu.parseReadResponse(hex("01 03 02 03 d2 78 e8"), 1, 1)
            fail("bad CRC must be rejected")
        } catch (e: LocalProtocolException) {
            // expected
        }
    }

    @Test
    fun anExceptionReplyCarriesItsCode() {
        try {
            ModbusRtu.parseReadResponse(ModbusRtu.withCrc(hex("01 83 02")), 1, 1)
            fail("exception reply must throw")
        } catch (e: ModbusException) {
            assertEquals(2, e.exceptionCode)
        }
    }

    @Test
    fun registerValuesAreUnsignedBigEndian() {
        assertArrayEquals(intArrayOf(0xFCE0), ModbusRtu.parseReadResponse(ModbusRtu.withCrc(hex("01 03 02 fc e0")), 1, 1))
    }
}
