package io.github.hafeee44lab.solargrid.data.local

import android.content.Context
import io.github.hafeee44lab.solargrid.data.SecureBox

/** The local logger's address and serial, sealed with the same Keystore key as the cloud account. */
class LoggerStore(context: Context) {

    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): LocalLogger? {
        val host = SecureBox.open(prefs.getString(HOST, null)) ?: return null
        val serial = SecureBox.open(prefs.getString(SERIAL, null))?.toLongOrNull() ?: return null
        return LocalLogger(host = host, serial = serial).takeIf { it.isComplete }
    }

    fun save(logger: LocalLogger) {
        prefs.edit()
            .putString(HOST, SecureBox.seal(logger.host.trim()))
            .putString(SERIAL, SecureBox.seal(logger.serial.toString()))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS = "local_logger"
        private const val HOST = "host"
        private const val SERIAL = "serial"
    }
}
