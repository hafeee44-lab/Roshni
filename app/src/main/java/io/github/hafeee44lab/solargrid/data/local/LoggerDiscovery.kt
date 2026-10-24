package io.github.hafeee44lab.solargrid.data.local

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/** A logger that answered the discovery broadcast. */
data class FoundLogger(val ip: String, val mac: String, val serial: Long)

/**
 * Finds Solarman loggers on the Wi-Fi the way ha-solarman does (discovery.py, const.py): a UDP
 * broadcast of "WIFIKIT-214028-READ" (and the older "HF-A11ASSISTHREAD") to port 48899. Each stick
 * answers "ip,mac,serial", which fills in both fields of the setup form.
 * https://github.com/davidrapan/ha-solarman/blob/main/custom_components/solarman/discovery.py
 */
object LoggerDiscovery {

    const val PORT = 48899
    private val MESSAGES = listOf("WIFIKIT-214028-READ", "HF-A11ASSISTHREAD")
    private const val LISTEN_MS = 2_500L
    private const val RECEIVE_SLICE_MS = 500

    suspend fun scan(context: Context): List<FoundLogger> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<Long, FoundLogger>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = RECEIVE_SLICE_MS
            for (target in broadcastAddresses(context)) {
                for (message in MESSAGES) {
                    val bytes = message.toByteArray(Charsets.US_ASCII)
                    try {
                        socket.send(DatagramPacket(bytes, bytes.size, target, PORT))
                    } catch (e: Exception) {
                        // Some networks refuse the all-ones broadcast; the subnet one may still work.
                    }
                }
            }

            val buffer = ByteArray(256)
            val until = System.currentTimeMillis() + LISTEN_MS
            while (System.currentTimeMillis() < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                parseReply(String(packet.data, 0, packet.length, Charsets.US_ASCII))?.let { found[it.serial] = it }
            }
        }
        found.values.toList()
    }

    /** "192.168.1.40,98D8631A2B3C,2712345678". Anything else, including our own broadcast echoed back, is ignored. */
    fun parseReply(text: String): FoundLogger? {
        val parts = text.trim().split(',')
        if (parts.size != 3) return null
        val ip = parts[0].trim()
        if (!LocalLogger.isPlausibleHost(ip) || ip.split('.').size != 4) return null
        val serial = LocalLogger.parseSerial(parts[2]) ?: return null
        return FoundLogger(ip = ip, mac = parts[1].trim(), serial = serial)
    }

    private fun broadcastAddresses(context: Context): List<InetAddress> {
        val targets = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val links = cm?.getLinkProperties(cm.activeNetwork)
            links?.linkAddresses?.forEach { link ->
                val address = link.address as? Inet4Address ?: return@forEach
                if (address.isLoopbackAddress) return@forEach
                subnetBroadcast(address.address, link.prefixLength)?.let { targets += InetAddress.getByAddress(it) }
            }
        } catch (e: Exception) {
            // Without link details the all-ones broadcast is all we can try.
        }
        return targets.distinct()
    }

    /** 192.168.1.23/24 → 192.168.1.255. */
    fun subnetBroadcast(address: ByteArray, prefixLength: Int): ByteArray? {
        if (address.size != 4 || prefixLength !in 8..30) return null
        val ip = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = -1 shl (32 - prefixLength)
        val broadcast = ip or mask.inv()
        return ByteArray(4) { i -> (broadcast ushr (24 - 8 * i)).toByte() }
    }
}
