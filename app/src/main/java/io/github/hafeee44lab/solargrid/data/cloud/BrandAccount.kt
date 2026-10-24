package io.github.hafeee44lab.solargrid.data.cloud

import android.content.Context
import io.github.hafeee44lab.solargrid.data.SecureBox
import org.json.JSONObject

/**
 * Sign-in details for a non-Solarman cloud. Each brand asks for different things, so they are
 * kept as named [fields]; [device] is the brand source's own reference to one inverter.
 */
data class BrandAccount(
    val brand: CloudBrand,
    val fields: Map<String, String>,
    val device: String,
    /** What the user knows the inverter by, usually its serial. */
    val deviceName: String = "",
    /** The plant or station it belongs to. */
    val label: String = ""
) {
    fun field(key: String): String = fields[key].orEmpty()

    val isComplete: Boolean
        get() = brand != CloudBrand.SOLARMAN && device.isNotBlank()
}

/** One saved brand account at a time, sealed whole with the Keystore key that guards the Solarman secret. */
class BrandAccountStore(context: Context) {

    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): BrandAccount? {
        val raw = SecureBox.open(prefs.getString(SEALED, null)) ?: return null
        return try {
            val json = JSONObject(raw)
            val fields = json.getJSONObject("fields")
            BrandAccount(
                brand = CloudBrand.from(json.getString("brand")),
                fields = fields.keys().asSequence().associateWith { fields.getString(it) },
                device = json.getString("device"),
                deviceName = json.optString("deviceName"),
                label = json.optString("label")
            ).takeIf { it.isComplete }
        } catch (e: Exception) {
            null
        }
    }

    fun save(account: BrandAccount) {
        val json = JSONObject()
            .put("brand", account.brand.name)
            .put("fields", JSONObject(account.fields.mapValues { it.value.trim() }))
            .put("device", account.device)
            .put("deviceName", account.deviceName)
            .put("label", account.label)
        prefs.edit().putString(SEALED, SecureBox.seal(json.toString())).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS = "brand_account"
        private const val SEALED = "sealed"
    }
}
