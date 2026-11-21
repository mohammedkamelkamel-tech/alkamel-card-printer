package com.alkamel.cardprinter.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

private val Context.alkamelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "alkamel_card_printer_prefs"
)

data class AppSettingsState(
    val showCutGuidesOnA4: Boolean = true,
    val showHeaderOnA4: Boolean = true,
    val appLockEnabled: Boolean = false,
    val biometricEnabled: Boolean = false
)

class AppPreferencesManager(private val context: Context) {

    private object Keys {
        val SHOW_CUT_GUIDES = booleanPreferencesKey("a4_show_cut_guides")
        val SHOW_A4_HEADER = booleanPreferencesKey("a4_show_header")
        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val LOCK_HASH = stringPreferencesKey("lock_hash")
        val LOCK_SALT = stringPreferencesKey("lock_salt")
        val BIOMETRIC = booleanPreferencesKey("biometric_enabled")
    }

    val settingsFlow: Flow<AppSettingsState> = context.alkamelDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val hash = p[Keys.LOCK_HASH].orEmpty()
            AppSettingsState(
                showCutGuidesOnA4 = p[Keys.SHOW_CUT_GUIDES] ?: true,
                showHeaderOnA4 = p[Keys.SHOW_A4_HEADER] ?: true,
                appLockEnabled = (p[Keys.LOCK_ENABLED] ?: false) && hash.isNotBlank(),
                biometricEnabled = p[Keys.BIOMETRIC] ?: false
            )
        }

    suspend fun saveA4Preferences(showCutGuides: Boolean, showHeader: Boolean) {
        context.alkamelDataStore.edit { p ->
            p[Keys.SHOW_CUT_GUIDES] = showCutGuides
            p[Keys.SHOW_A4_HEADER] = showHeader
        }
    }

    suspend fun enableAppLock(password: String, biometric: Boolean) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val hash = sha256("$salt:$password")
        context.alkamelDataStore.edit { p ->
            p[Keys.LOCK_SALT] = salt
            p[Keys.LOCK_HASH] = hash
            p[Keys.LOCK_ENABLED] = true
            p[Keys.BIOMETRIC] = biometric
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.alkamelDataStore.edit { it[Keys.BIOMETRIC] = enabled }
    }

    suspend fun disableAppLock() {
        context.alkamelDataStore.edit { p ->
            p[Keys.LOCK_ENABLED] = false
            p[Keys.BIOMETRIC] = false
            p.remove(Keys.LOCK_HASH)
            p.remove(Keys.LOCK_SALT)
        }
    }

    suspend fun verifyPassword(candidate: String): Boolean {
        val p = context.alkamelDataStore.data.first()
        val salt = p[Keys.LOCK_SALT].orEmpty()
        val saved = p[Keys.LOCK_HASH].orEmpty()
        if (salt.isBlank() || saved.isBlank()) return false
        val computed = sha256("$salt:$candidate")
        if (saved.length != computed.length) return false
        var diff = 0
        for (i in saved.indices) diff = diff or (saved[i].code xor computed[i].code)
        return diff == 0
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
