package io.github.hafeee44lab.solargrid.service

import android.content.SharedPreferences
import android.util.Log
import io.github.hafeee44lab.solargrid.data.RelaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Forwards alerts to the optional WhatsApp bridge (see tools/whatsapp-bridge). Messages that
 * cannot be delivered are queued and retried on the next poll, so an alert sent while the
 * Wi-Fi was down still arrives once it is back.
 */
object Relay {

    private const val PENDING_KEY = "relay_pending"
    private const val PENDING_LIMIT = 10
    private const val ATTEMPTS = 3
    private const val TIMEOUT_MS = 8000
    private const val TAG = "Relay"

    suspend fun send(settings: RelaySettings, prefs: SharedPreferences, text: String, key: String) {
        if (!settings.isUsable) return
        drain(settings, prefs)
        if (!post(settings, text, key)) queue(prefs, text, key)
    }

    suspend fun sendTest(settings: RelaySettings, message: String): Boolean =
        post(
            settings,
            message,
            "test-${System.currentTimeMillis()}"
        )

    suspend fun drain(settings: RelaySettings, prefs: SharedPreferences) {
        if (!settings.isUsable) return
        val pending = load(prefs)
        if (pending.isEmpty()) return
        store(prefs, pending.filterNot { (text, key) -> post(settings, text, key) })
    }

    private suspend fun post(settings: RelaySettings, text: String, key: String): Boolean = withContext(Dispatchers.IO) {
        repeat(ATTEMPTS) { attempt ->
            try {
                val payload = JSONObject().put("text", text).put("key", key).toString()
                val connection = (URL("${settings.url.trimEnd('/')}/send").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-bridge-key", settings.secret)
                }
                connection.outputStream.use { it.write(payload.toByteArray()) }
                val code = connection.responseCode
                connection.disconnect()

                if (code in 200..299) return@withContext true
                Log.w(TAG, "bridge replied $code for $key")
                if (code == 401 || code == 429) return@withContext false
            } catch (e: Exception) {
                Log.w(TAG, "attempt ${attempt + 1} failed for $key: ${e.message}")
            }
            if (attempt < ATTEMPTS - 1) delay(2000L * (attempt + 1))
        }
        false
    }

    private fun queue(prefs: SharedPreferences, text: String, key: String) {
        val pending = load(prefs).filterNot { it.second == key } + (text to key)
        store(prefs, pending.takeLast(PENDING_LIMIT))
    }

    private fun load(prefs: SharedPreferences): List<Pair<String, String>> {
        val raw = prefs.getString(PENDING_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val item = array.getJSONObject(it)
                item.getString("text") to item.getString("key")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun store(prefs: SharedPreferences, items: List<Pair<String, String>>) {
        val array = JSONArray()
        items.forEach { (text, key) -> array.put(JSONObject().put("text", text).put("key", key)) }
        prefs.edit().putString(PENDING_KEY, array.toString()).apply()
    }
}
