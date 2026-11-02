package com.bettermifitness.sync.data.preferences

/**
 * Platform-secure storage for sensitive auth tokens.
 *
 * Android: backed by EncryptedSharedPreferences (AES-256-GCM, key in Android Keystore).
 * iOS: backed by Keychain (kSecClassGenericPassword).
 *
 * Non-sensitive preferences stay in plain DataStore; only the three session
 * secrets go through here.
 */
expect class SecureTokenStorage() {
    /** Returns the stored value, or null if absent. */
    fun get(key: String): String?

    /** Stores a value (overwrites). */
    fun put(key: String, value: String)

    /** Removes a value. */
    fun remove(key: String)

    /** Clears all values in this store. */
    fun clear()
}
