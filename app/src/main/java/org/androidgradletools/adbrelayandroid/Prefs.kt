package org.androidgradletools.adbrelayandroid

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object Prefs {
    const val DEFAULT_ADBD_HOST = "127.0.0.1"
    const val DEFAULT_ADBD_PORT = 5555

    private const val NAME = "adb_proxy_prefs"
    private const val KEY_RELAY_URL = "relay_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_ADBD_HOST = "adbd_host"
    private const val KEY_ADBD_PORT = "adbd_port"

    fun encryptedPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(context: Context): Config {
        val p = encryptedPrefs(context)
        val host = p.getString(KEY_ADBD_HOST, DEFAULT_ADBD_HOST) ?: DEFAULT_ADBD_HOST
        val port = p.getInt(KEY_ADBD_PORT, DEFAULT_ADBD_PORT)
        return Config(
            relayUrl = p.getString(KEY_RELAY_URL, "") ?: "",
            token = p.getString(KEY_TOKEN, "") ?: "",
            adbdHost = host,
            adbdPort = port,
        )
    }

    fun save(context: Context, c: Config) {
        encryptedPrefs(context).edit()
            .putString(KEY_RELAY_URL, c.relayUrl.trim())
            .putString(KEY_TOKEN, c.token)
            .putString(KEY_ADBD_HOST, c.adbdHost.trim().ifEmpty { DEFAULT_ADBD_HOST })
            .putInt(KEY_ADBD_PORT, c.adbdPort.coerceIn(1, 65535))
            .apply()
    }

    data class Config(
        val relayUrl: String,
        val token: String,
        val adbdHost: String,
        val adbdPort: Int,
    ) {
        fun resolvedAdbdHost(): String = adbdHost.trim().ifBlank { DEFAULT_ADBD_HOST }

        fun resolvedAdbdPort(): Int = adbdPort.coerceIn(1, 65535)
    }
}
