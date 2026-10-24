package io.github.hafeee44lab.solargrid.data.local

import java.io.IOException
import java.io.InputStream

/** The logger or the inverter answered, but with something we cannot use. */
open class LocalProtocolException(message: String) : IOException(message)

/** The inverter refused a Modbus request, for example a register it does not have. */
class ModbusException(message: String, val exceptionCode: Int? = null) : LocalProtocolException(message)

/**
 * Modbus RTU, as carried inside Solarman V5 frames: CRC-16/MODBUS and the read-registers requests
 * and responses. Deye and Sofar keep their live data in holding registers (function 0x03); Solis,
 * Kstar and Afore in input registers (function 0x04). Both are read-only requests.
 */
object ModbusRtu {

    const val READ_HOLDING = 0x03
    const val READ_INPUT = 0x04

    /** CRC-16/MODBUS: reflected polynomial 0xA001, initial value 0xFFFF. Sent low byte first. */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc
    }

    fun readHoldingRequest(slave: Int, start: Int, count: Int): ByteArray = readRequest(slave, READ_HOLDING, start, count)

    fun readRequest(slave: Int, function: Int, start: Int, count: Int): ByteArray {
        require(slave in 1..247) { "Modbus address $slave is out of range" }
        require(function == READ_HOLDING || function == READ_INPUT) { "Only read requests are allowed, not function $function" }
        require(start in 0..0xFFFF && count in 1..125) { "Can't read $count registers from $start" }
        val pdu = byteArrayOf(
            slave.toByte(),
            function.toByte(),
            (start ushr 8).toByte(), start.toByte(),
            (count ushr 8).toByte(), count.toByte()
        )
        return withCrc(pdu)
    }

    /**
     * The register values in a read response. Some Deye and logger combinations append the CRC
     * twice, leaving two extra zero bytes; pysolarmanv5 strips those and so do we.
     * See https://github.com/jmccrohan/pysolarmanv5/issues/62
     */
    fun parseReadResponse(frame: ByteArray, slave: Int, count: Int, function: Int = READ_HOLDING): IntArray {
        if (frame.size >= 5 && frame[1].toInt() and 0xFF == (function or 0x80)) {
            if (!crcMatches(frame, 5)) throw LocalProtocolException("Inverter sent a damaged error reply")
            val code = frame[2].toInt() and 0xFF
            throw ModbusException("Inverter refused the request (Modbus exception $code)", code)
        }

        val expected = 5 + 2 * count
        val body = when {
            frame.size == expected -> frame
            frame.size == expected + 2 && frame[expected].toInt() == 0 && frame[expected + 1].toInt() == 0 ->
                frame.copyOf(expected)
            else -> throw LocalProtocolException("Inverter reply is ${frame.size} bytes, expected $expected")
        }

        if (!crcMatches(body, expected)) throw LocalProtocolException("Inverter reply failed its CRC check")
        if (body[0].toInt() and 0xFF != slave) throw LocalProtocolException("Reply came from Modbus address ${body[0].toInt() and 0xFF}, not $slave")
        if (body[1].toInt() and 0xFF != function) throw LocalProtocolException("Reply is for Modbus function ${body[1].toInt() and 0xFF}")
        if (body[2].toInt() and 0xFF != 2 * count) throw LocalProtocolException("Reply carries ${body[2].toInt() and 0xFF} bytes, expected ${2 * count}")

        return IntArray(count) { i -> ((body[3 + 2 * i].toInt() and 0xFF) shl 8) or (body[4 + 2 * i].toInt() and 0xFF) }
    }

    fun withCrc(pdu: ByteArray): ByteArray {
        val crc = crc16(pdu)
        return pdu + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    private fun crcMatches(frame: ByteArray, length: Int): Boolean {
        val crc = crc16(frame, 0, length - 2)
        return frame[length - 2].toInt() and 0xFF == crc and 0xFF &&
            frame[length - 1].toInt() and 0xFF == crc ushr 8
    }
}

/**
 * Solarman V5, the protocol the Solarman Wi-Fi sticks (LSW-3 and friends) speak on TCP 8899. It
 * wraps a Modbus RTU frame in a header addressed to the stick's own serial number, so the inverter
 * can be read on the LAN without the cloud.
 *
 * Layout, all little-endian except the Modbus frame:
 *   header  A5 | length (2) | 10 45 | sequence (2) | logger serial (4)
 *   request 02 | sensor type 00 00 | three 4-byte times, all zero | Modbus RTU frame
 *   reply   02 | status | working time (4) | power-on time (4) | offset time (4) | Modbus RTU frame
 *   trailer checksum (sum of every byte after A5, low 8 bits) | 15
 * The length counts the payload only, so a whole frame is 13 + length bytes. The logger echoes the
 * first sequence byte back, which is how a reply is matched to its request.
 *
 * Source: pysolarmanv5 by Jonathan McCrohan, pysolarmanv5/pysolarmanv5.py (_v5_frame_encoder,
 * _v5_frame_decoder, _calculate_checksum) and docs/solarmanv5_protocol.rst,
 * https://github.com/jmccrohan/pysolarmanv5
 */
object SolarmanV5 {

    const val PORT = 8899

    private const val START = 0xA5
    private const val END = 0x15
    private const val CONTROL_SUFFIX = 0x10
    private const val CONTROL_REQUEST = 0x45
    private const val CONTROL_RESPONSE = 0x15
    private const val FRAME_TYPE_INVERTER = 0x02
    private const val HEADER = 11
    private const val REPLY_PREAMBLE = 25
    private const val MAX_PAYLOAD = 1024

    /** Serial numbers are four bytes on the wire. */
    const val MAX_SERIAL = 0xFFFF_FFFFL

    fun encode(loggerSerial: Long, sequence: Int, modbus: ByteArray): ByteArray {
        require(loggerSerial in 1..MAX_SERIAL) { "Logger serial $loggerSerial does not fit in four bytes" }
        val length = 15 + modbus.size
        val frame = ByteArray(13 + length)
        frame[0] = START.toByte()
        frame[1] = length.toByte()
        frame[2] = (length ushr 8).toByte()
        frame[3] = CONTROL_SUFFIX.toByte()
        frame[4] = CONTROL_REQUEST.toByte()
        frame[5] = sequence.toByte()
        frame[6] = 0
        writeSerial(frame, 7, loggerSerial)
        frame[11] = FRAME_TYPE_INVERTER.toByte()
        // Sensor type and the three time fields stay zero, as pysolarmanv5 sends them.
        modbus.copyInto(frame, 26)
        frame[frame.size - 2] = checksum(frame, 1, frame.size - 2).toByte()
        frame[frame.size - 1] = END.toByte()
        return frame
    }

    fun checksum(frame: ByteArray, from: Int, to: Int): Int {
        var sum = 0
        for (i in from until to) sum += frame[i].toInt() and 0xFF
        return sum and 0xFF
    }

    sealed interface Decoded {
        /** The reply to our request, holding the inverter's Modbus RTU frame. */
        class Reply(val modbus: ByteArray) : Decoded

        /** A well-formed frame that is not our reply: a heartbeat, or an answer to an older request. */
        data class Unrelated(val reason: String) : Decoded
    }

    /**
     * Checks a frame as pysolarmanv5's _v5_frame_decoder does: start and end bytes, checksum,
     * our sequence number echoed back, our logger serial, the response control code and the
     * inverter frame type.
     */
    fun decode(frame: ByteArray, loggerSerial: Long, sequence: Int): Decoded {
        if (frame.size < HEADER + 2 || frame[0].toInt() and 0xFF != START || frame.last().toInt() and 0xFF != END) {
            throw LocalProtocolException("Not a Solarman V5 frame")
        }
        if (frame[frame.size - 2].toInt() and 0xFF != checksum(frame, 1, frame.size - 2)) {
            throw LocalProtocolException("Logger reply failed its checksum")
        }

        val control = frame[4].toInt() and 0xFF
        if (frame[3].toInt() and 0xFF != CONTROL_SUFFIX || control != CONTROL_RESPONSE) {
            return Decoded.Unrelated("control code 0x%02X".format(control))
        }
        if (frame[5].toInt() and 0xFF != sequence and 0xFF) return Decoded.Unrelated("sequence ${frame[5].toInt() and 0xFF}")
        if (readSerial(frame, 7) != loggerSerial) throw LocalProtocolException("Reply came from logger ${readSerial(frame, 7)}, not $loggerSerial")
        if (frame.size < REPLY_PREAMBLE + 2 || frame[11].toInt() and 0xFF != FRAME_TYPE_INVERTER) {
            throw LocalProtocolException("Logger reply carries no inverter data")
        }

        val modbus = frame.copyOfRange(REPLY_PREAMBLE, frame.size - 2)
        if (modbus.size < 5) {
            throw LocalProtocolException("The logger answered but the inverter did not. Check the inverter's Modbus address is 1.")
        }
        return Decoded.Reply(modbus)
    }

    /**
     * Reads one frame off the stream, skipping anything before the next start byte. Loggers do
     * now and then send stray bytes that are not V5 at all.
     */
    fun readFrame(input: InputStream): ByteArray {
        var skipped = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Logger closed the connection")
            if (b == START) break
            if (++skipped > MAX_PAYLOAD) throw LocalProtocolException("Logger is sending something other than Solarman V5")
        }
        val lo = readByte(input)
        val hi = readByte(input)
        val length = lo or (hi shl 8)
        if (length > MAX_PAYLOAD) throw LocalProtocolException("Logger frame claims $length bytes")

        val frame = ByteArray(13 + length)
        frame[0] = START.toByte()
        frame[1] = lo.toByte()
        frame[2] = hi.toByte()
        var at = 3
        while (at < frame.size) {
            val n = input.read(frame, at, frame.size - at)
            if (n < 0) throw IOException("Logger closed the connection mid-frame")
            at += n
        }
        return frame
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw IOException("Logger closed the connection mid-frame")
        return b
    }

    private fun writeSerial(frame: ByteArray, at: Int, serial: Long) {
        for (i in 0 until 4) frame[at + i] = (serial ushr (8 * i)).toByte()
    }

    private fun readSerial(frame: ByteArray, at: Int): Long {
        var serial = 0L
        for (i in 0 until 4) serial = serial or ((frame[at + i].toLong() and 0xFF) shl (8 * i))
        return serial
    }
}
