package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mifitness.miclient.auth.MiCredentials
import com.mifitness.miclient.auth.MiRegion
import com.mifitness.miclient.auth.PassportAuthUtils
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Persisted Mi Account credentials and session identity (SRP: auth storage only).
 *
 * Sensitive session secrets (serviceToken, ssecurity, passToken) are stored in
 * platform-secure storage (Android Keystore / iOS Keychain) via [secureStorage].
 * Non-sensitive metadata (user ID, region, device ID) stays in plain DataStore.
 */
class CredentialsStore(
    private val dataStore: DataStore<Preferences>,
    private val secureStorage: SecureTokenStorage,
) : CredentialsPort {
    // Sensitive: read from platform-secure storage (one-shot; callers use .first())
    override val token: Flow<String?> = flow { emit(secureStorage.get(SECURE_TOKEN_KEY)) }

    val miUserId: Flow<String?> = dataStore.data.map { it[MI_USER_ID_KEY] }

    /** Effective region used by the API client (after discovery or STS provisional). */
    val region: Flow<String?> = dataStore.data.map { prefs ->
        MiRegion.normalizeCode(prefs[REGION_KEY] ?: prefs[LOGIN_REGION_KEY])
    }

    val loginRegion: Flow<String?> = dataStore.data.map { it[LOGIN_REGION_KEY] }

    val regionDiscoverySource: Flow<String?> =
        dataStore.data.map { it[REGION_DISCOVERY_SOURCE_KEY] }

    val regionLatestEpoch: Flow<String?> =
        dataStore.data.map { it[REGION_LATEST_EPOCH_KEY] }

    override suspend fun saveCredentials(credentials: MiCredentials) {
        // Sensitive tokens -> platform-secure storage (Keystore/Keychain)
        secureStorage.put(SECURE_TOKEN_KEY, credentials.serviceToken)
        secureStorage.put(SECURE_SSECURITY_KEY, credentials.ssecurity)
        secureStorage.put(SECURE_PASS_TOKEN_KEY, credentials.passToken)
        // Non-sensitive metadata -> plain DataStore
        dataStore.edit { preferences ->
            // Every persist is either a fresh login or a completed passport refresh, i.e. the
            // moment Xiaomi last rotated our passToken. Stamped so the sync policy can roll
            // the session forward before the server retires the token we now hold.
            preferences[SESSION_REFRESHED_AT_KEY] = Clock.System.now().epochSeconds.toString()
            preferences[MI_USER_ID_KEY] = credentials.userId
            // Device identity is stable across logins; never blank it from legacy creds.
            if (credentials.deviceId.isNotBlank()) {
                preferences[DEVICE_ID_KEY] = credentials.deviceId
            }
            if (credentials.cUserId.isNotBlank()) {
                preferences[C_USER_ID_KEY] = credentials.cUserId
            } else {
                preferences.remove(C_USER_ID_KEY)
            }

            val loginFromSts = MiRegion.normalizeCode(credentials.region)
            preferences[LOGIN_REGION_KEY] = loginFromSts
            // Provisional until discovery; keep existing discovered region if still signed-in refresh
            // with same tokens would re-run discovery on login only.
            if (preferences[REGION_KEY].isNullOrBlank()) {
                preferences[REGION_KEY] = loginFromSts
            }
            // Drop legacy manual mode key if present
            preferences.remove(REGION_MODE_KEY)
            // Remove legacy plaintext tokens if migrating from older version
            preferences.remove(TOKEN_KEY)
            preferences.remove(SSECURITY_KEY)
            preferences.remove(PASS_TOKEN_KEY)
        }
    }

    override suspend fun loadCredentials(): MiCredentials? {
        val serviceToken = secureStorage.get(SECURE_TOKEN_KEY) ?: return null
        val ssecurity = secureStorage.get(SECURE_SSECURITY_KEY) ?: return null
        // passToken may be blank on legacy installs; still load for in-memory API until re-login.
        val passToken = secureStorage.get(SECURE_PASS_TOKEN_KEY) ?: ""
        val prefs = dataStore.data.first()
        val userId = prefs[MI_USER_ID_KEY] ?: return null
        val deviceId = prefs[DEVICE_ID_KEY] ?: ""
        val cUserId = prefs[C_USER_ID_KEY] ?: ""
        val effective = MiRegion.normalizeCode(
            prefs[REGION_KEY] ?: prefs[LOGIN_REGION_KEY],
        )
        return MiCredentials(
            userId = userId,
            ssecurity = ssecurity,
            serviceToken = serviceToken,
            passToken = passToken,
            deviceId = deviceId,
            region = effective,
            cUserId = cUserId,
        )
    }

    override suspend fun lastSessionRefreshEpochSeconds(): Long? =
        dataStore.data.first()[SESSION_REFRESHED_AT_KEY]?.toLongOrNull()

    /**
     * Stable device identity Xiaomi uses to decide whether email+password needs OTP.
     * Generated once and reused for every login, OTP, browser URL, and refresh,
     * mirroring the official app's cached hashed device id. Never blank after return.
     */
    override suspend fun ensureDeviceId(): String {
        val prefs = dataStore.data.first()
        prefs[DEVICE_ID_KEY]?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = PassportAuthUtils.generateDeviceId()
        dataStore.edit { it[DEVICE_ID_KEY] = generated }
        return generated
    }

    /** Current stored device identity, or empty when never generated. */
    override suspend fun loadDeviceId(): String = dataStore.data.first()[DEVICE_ID_KEY] ?: ""

    /**
     * Restores a previously trusted device identity without minting a new one.
     * Used by logout, which wipes tokens but must keep presenting the same
     * install to Xiaomi on the next sign-in.
     */
    suspend fun restoreDeviceId(deviceId: String) {
        if (deviceId.isBlank()) return
        dataStore.edit { it[DEVICE_ID_KEY] = deviceId }
    }

    /**
     * Persist auto-discovery winner and metadata for Settings.
     */
    suspend fun setDiscoveredRegion(result: MiRegion.DiscoveryResult) {
        dataStore.edit { preferences ->
            preferences[REGION_KEY] = MiRegion.normalizeCode(result.region)
            preferences[REGION_DISCOVERY_SOURCE_KEY] = result.source.name
            if (result.latestEpochSec != null) {
                preferences[REGION_LATEST_EPOCH_KEY] = result.latestEpochSec.toString()
            } else {
                preferences.remove(REGION_LATEST_EPOCH_KEY)
            }
            preferences.remove(REGION_MODE_KEY)
        }
    }

    /** Clears session credentials but keeps the stable device identity. */
    suspend fun clearCredentials() {
        secureStorage.remove(SECURE_TOKEN_KEY)
        secureStorage.remove(SECURE_SSECURITY_KEY)
        secureStorage.remove(SECURE_PASS_TOKEN_KEY)
        dataStore.edit { preferences ->
            preferences.remove(MI_USER_ID_KEY)
            preferences.remove(REGION_KEY)
            preferences.remove(REGION_MODE_KEY)
            preferences.remove(LOGIN_REGION_KEY)
            preferences.remove(REGION_DISCOVERY_SOURCE_KEY)
            preferences.remove(REGION_LATEST_EPOCH_KEY)
            preferences.remove(C_USER_ID_KEY)
            preferences.remove(SESSION_REFRESHED_AT_KEY)
            // Legacy plaintext keys (in case of partial migration)
            preferences.remove(TOKEN_KEY)
            preferences.remove(SSECURITY_KEY)
            preferences.remove(PASS_TOKEN_KEY)
        }
    }

    companion object {
        // Legacy DataStore keys (kept for migration cleanup; values now in SecureTokenStorage)
        private val TOKEN_KEY = stringPreferencesKey("bearer_token")
        private val SSECURITY_KEY = stringPreferencesKey("ssecurity")
        private val PASS_TOKEN_KEY = stringPreferencesKey("pass_token")
        // Secure storage keys (Android Keystore / iOS Keychain)
        private const val SECURE_TOKEN_KEY = "secure_bearer_token"
        private const val SECURE_SSECURITY_KEY = "secure_ssecurity"
        private const val SECURE_PASS_TOKEN_KEY = "secure_pass_token"
        private val MI_USER_ID_KEY = stringPreferencesKey("mi_user_id")
        private val REGION_KEY = stringPreferencesKey("region")
        private val REGION_MODE_KEY = stringPreferencesKey("region_mode") // legacy
        private val LOGIN_REGION_KEY = stringPreferencesKey("login_region")
        private val REGION_DISCOVERY_SOURCE_KEY = stringPreferencesKey("region_discovery_source")
        private val REGION_LATEST_EPOCH_KEY = stringPreferencesKey("region_latest_epoch")
        private val SSECURITY_KEY = stringPreferencesKey("ssecurity")
        private val PASS_TOKEN_KEY = stringPreferencesKey("pass_token")
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        private val C_USER_ID_KEY = stringPreferencesKey("c_user_id")
        private val SESSION_REFRESHED_AT_KEY = stringPreferencesKey("session_refreshed_at")
    }
}
