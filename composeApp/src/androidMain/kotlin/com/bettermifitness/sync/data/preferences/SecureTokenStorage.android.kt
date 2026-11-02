package com.bettermifitness.sync.data.preferences

import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bettermifitness.sync.di.androidAppContext

/**
 * Android implementation using EncryptedSharedPreferences.
 * Master key lives in Android Keystore (AES-256-GCM); values never touch disk in plaintext.
 */
actual class SecureTokenStorage actual constructor() {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(androidAppContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            androidAppContext(),
            "secure_mi_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual fun get(key: String): String? = prefs.getString(key, null)

    actual fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    actual fun clear() {
        prefs.edit().clear().apply()
    }
}
