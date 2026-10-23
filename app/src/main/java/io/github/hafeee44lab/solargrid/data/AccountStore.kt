package io.github.hafeee44lab.solargrid.data

import android.content.Context
import io.github.hafeee44lab.solargrid.BuildConfig
import java.security.MessageDigest

/**
 * Solarman OpenAPI credentials. The password itself is never stored — the API only ever
 * wants its SHA-256, so that is all we keep, and it is sealed with a Keystore key.
 */
data class Account(
    val appId: String,
    val appSecret: String,
    val email: String,
    val passwordSha256: String,
    val deviceSn: String,
    val label: String = ""
) {
    val isComplete: Boolean
        get() = appId.isNotBlank() && appSecret.isNotBlank() && email.isNotBlank() &&
            passwordSha256.isNotBlank() && deviceSn.isNotBlank()

    companion object {
        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    fun load(): Account? {
        seedFromBuildIfEmpty()

        val account = Account(
            appId = prefs.getString(APP_ID, "") ?: "",
            appSecret = SecureBox.open(prefs.getString(APP_SECRET, null)) ?: "",
            email = prefs.getString(EMAIL, "") ?: "",
            passwordSha256 = SecureBox.open(prefs.getString(PASSWORD, null)) ?: "",
            deviceSn = prefs.getString(DEVICE_SN, "") ?: "",
            label = prefs.getString(LABEL, "") ?: ""
        )
        return account.takeIf { it.isComplete }
    }

    fun save(account: Account) {
        prefs.edit()
            .putString(APP_ID, account.appId.trim())
            .putString(APP_SECRET, SecureBox.seal(account.appSecret.trim()))
            .putString(EMAIL, account.email.trim())
            .putString(PASSWORD, SecureBox.seal(account.passwordSha256))
            .putString(DEVICE_SN, account.deviceSn.trim())
            .putString(LABEL, account.label)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().putBoolean(SEED_CONSUMED, true).apply()
    }

    /**
     * A developer's own build can carry credentials from local.properties. They are copied in
     * once; signing out afterwards really signs out instead of silently re-seeding.
     */
    private fun seedFromBuildIfEmpty() {
        if (prefs.getBoolean(SEED_CONSUMED, false)) return
        if (prefs.contains(APP_ID)) return

        val seeded = Account(
            appId = BuildConfig.SEED_APP_ID,
            appSecret = BuildConfig.SEED_APP_SECRET,
            email = BuildConfig.SEED_EMAIL,
            passwordSha256 = BuildConfig.SEED_PASSWORD_SHA256,
            deviceSn = BuildConfig.SEED_DEVICE_SN
        )

        prefs.edit().putBoolean(SEED_CONSUMED, true).apply()
        if (seeded.isComplete) save(seeded)
    }

    private companion object {
        const val APP_ID = "app_id"
        const val APP_SECRET = "app_secret"
        const val EMAIL = "email"
        const val PASSWORD = "password_sha256"
        const val DEVICE_SN = "device_sn"
        const val LABEL = "label"
        const val SEED_CONSUMED = "seed_consumed"
    }
}
