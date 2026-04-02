package com.rite.pillcounting.core.utils.preference

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.rite.pillcounting.core.settings.domain.model.ColorSettings
import com.rite.pillcounting.core.utils.logger.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Secure SharedPreferences wrapper for persistent application data.**
 *
 * `PreferenceHelper` provides an encrypted and centralized storage mechanism for:
 * - Authentication tokens (access / refresh).
 * - User session state (login flags, IDs).
 * - Transaction identifiers.
 * - Cached theme and UI preferences ([ColorSettings]).
 * - App-specific settings (e.g., dialogs, recent logins, history retention).
 *
 * It uses [EncryptedSharedPreferences] backed by [MasterKey] to ensure all data is stored securely
 * with hardware-backed AES-256 encryption where available.
 *
 * ### 🔒 Security
 * - Keys are encrypted using **AES-256 SIV**.
 * - Values are encrypted using **AES-256 GCM**.
 * - The master key is stored in the Android Keystore (hardware-backed if supported).
 *
 * ### 🧩 Integration
 * This class is a [Singleton] managed by **Hilt**, making it globally accessible:
 * ```kotlin
 * @Inject lateinit var preferenceHelper: PreferenceHelper
 * ```
 *
 * ### 🪵 Logging
 * - Uses [AppLogger] for structured logs.
 * - Sensitive data (like token values) is **never logged**.
 * - Logs include operation type, key names, and data length.
 *
 * @property context Application context (injected by Hilt).
 * @constructor Creates a secure instance of [PreferenceHelper] using encrypted preferences.
 */

private const val PREF_NAME = "pillcounting_secure_prefs"
// Auth Tokens
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"

// Session
private const val KEY_USER_LOGGED_IN = "user_logged_in"
private const val KEY_USER_ID = "user_id"
private const val KEY_LOCAL_ID = "local_id"

// Transactions
private const val KEY_TXN_ID = "txn_id"

// UI Theme
private const val KEY_THEME_COLORS = "theme_colors"

// Miscellaneous
private const val KEY_DO_NOT_ASK_AGAIN = "do_not_ask_again"
private const val KEY_SHOW_NOTES_DIALOG = "key_show_notes_dialog"
private const val KEY_RECENT_LOGINS = "recent_logins"
private const val KEY_HISTORY_RETENTION = "history_retention"

private const val KEY_SENT_TXN_ID = "last_txn_id"

//HL7
private const val KEY_NSD_BROADCAST_TYPE = "key_nsd_broadcast_type"
private const val KEY_NSD_DISCOVERY_TYPE = "key_nsd_discovery_type"
private const val KEY_HL7_ENABLED = "key_hl7_enabled"
private const val KEY_SOUND = "key_pill_count_sound_enabled"
private const val KEY_HAPTIC = "key_pill_count_haptic_enabled"
private const val KEY_REQUIRE_BACK_COUNT = "key_require_back_count"
private const val KEY_REQUIRE_DOUBLE_COUNT = "key_require_double_count"
private const val KEY_CONTROL_DRUG_TYPES="key_control_drug_types"
private const val KEY_SOUND_OVERRIDE="key_sound_override"
private const val KEY_BARCODE_REGEX="key_barcode_regex"


@Singleton
class PreferenceHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Secure [SharedPreferences] instance backed by AES encryption. */
    private val prefs: SharedPreferences

    /** JSON serializer for persisting complex objects like [ColorSettings]. */
    private val gson = Gson()

    /** Application logger (no sensitive value logging). */
    private val logger = AppLogger.create<PreferenceHelper>()

    init {
        // Initialize or retrieve a master key from Android Keystore
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // Create an encrypted preferences instance
        prefs = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        logger.i("EncryptedSharedPreferences initialized with AES-256 encryption.")
    }

    // ─────────────────────────── AUTH TOKENS ───────────────────────────

    /**
     * Saves both **access** and **refresh** tokens securely.
     *
     * @param accessToken The short-lived access token for authenticated API requests.
     * @param refreshToken The long-lived refresh token used to renew access.
     */
    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
        }
        logger.i("Saved tokens securely (lengths: ${accessToken.length}, ${refreshToken.length})")
    }

    /** @return The decrypted access token or `null` if not set. */
    fun getAccessToken(): String? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        logger.d("Access token retrieved (exists=${token != null}, length=${token?.length ?: 0})")
        return token
    }

    /** @return The decrypted refresh token or `null` if not set. */
    fun getRefreshToken(): String? {
        val token = prefs.getString(KEY_REFRESH_TOKEN, null)
        logger.d("Refresh token retrieved (exists=${token != null}, length=${token?.length ?: 0})")
        return token
    }

    /** Clears both tokens from secure storage. */
    fun clearTokens() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
        }
        logger.w("Cleared authentication tokens from secure storage.")
    }

    // ─────────────────────────── USER SESSION ───────────────────────────

    /**
     * Updates the user's login state.
     *
     * @param loggedIn `true` if logged in, otherwise `false`.
     */
    fun setUserLoggedIn(loggedIn: Boolean) {
        prefs.edit { putBoolean(KEY_USER_LOGGED_IN, loggedIn) }
        logger.i("Set user login state: $loggedIn")
    }

    /**
     * Checks if the user is currently logged in.
     * @return `true` if logged in, otherwise `false`.
     */
    fun isUserLoggedIn(): Boolean {
        val state = prefs.getBoolean(KEY_USER_LOGGED_IN, false)
        logger.d("Checked user login state: $state")
        return state
    }

    // ─────────────────────────── USER IDENTIFIERS ───────────────────────────

    /**
     * Saves the backend-issued user ID.
     * @param userId The unique user identifier from the API.
     */
    fun saveUserId(userId: String) {
        prefs.edit { putString(KEY_USER_ID, userId) }
        logger.i("Saved userId securely (length=${userId.length})")
    }

    /** @return The decrypted user ID or `null` if not set. */
    fun getUserId(): String? {
        val id = prefs.getString(KEY_USER_ID, null)
        logger.d("UserId retrieved (exists=${id != null}, length=${id?.length ?: 0})")
        return id
    }

    /** Saves a local Room database primary key. */
    fun saveLocalId(localId: Long) {
        prefs.edit { putLong(KEY_LOCAL_ID, localId) }
        logger.i("Saved localId: $localId")
    }

    /** @return The stored Room DB ID, or `0` if not found. */
    fun getLocalId(): Long {
        val id = prefs.getLong(KEY_LOCAL_ID, 0)
        logger.d("Retrieved localId: $id")
        return id
    }

    // ─────────────────────────── TRANSACTIONS ───────────────────────────

    /** Saves the current transaction ID securely. */
    fun saveTxnId(txnId: Long) {
        prefs.edit { putLong(KEY_TXN_ID, txnId) }
        logger.i("Saved transaction ID: $txnId")
    }

    /** @return The stored transaction ID, or `0` if none exists. */
    fun getTxnId(): Long {
        val id = prefs.getLong(KEY_TXN_ID, 0)
        logger.d("Retrieved transaction ID: $id")
        return id
    }

    // ─────────────────────────── THEME CACHING ───────────────────────────

    /**
     * Persists the [ColorSettings] theme configuration as encrypted JSON.
     *
     * @param theme The theme configuration object.
     */
    fun saveThemeColors(theme: ColorSettings) {
        val json = gson.toJson(theme)
        prefs.edit { putString(KEY_THEME_COLORS, json) }
        logger.i("Saved theme colors (json length=${json.length})")
    }

    /**
     * Retrieves the cached theme configuration.
     * @return [ColorSettings] if cached, or `null` if not found.
     */
    fun getThemeColors(): ColorSettings? {
        val json = prefs.getString(KEY_THEME_COLORS, null)
        return if (json != null) {
            logger.d("Retrieved theme colors (json length=${json.length})")
            gson.fromJson(json, ColorSettings::class.java)
        } else {
            logger.w("No cached theme colors found.")
            null
        }
    }

    // ─────────────────────────── USER SETTINGS ───────────────────────────

    /** Stores the “Do Not Ask Again” dialog preference. */
    fun saveDoNotAskAgain(doNotAsk: Boolean) {
        prefs.edit { putBoolean(KEY_DO_NOT_ASK_AGAIN, doNotAsk) }
        logger.i("Saved DoNotAskAgain flag: $doNotAsk")
    }

    /** @return Whether the “Do Not Ask Again” option is enabled. */
    fun isDoNotAskAgain(): Boolean {
        val value = prefs.getBoolean(KEY_DO_NOT_ASK_AGAIN, false)
        logger.d("Retrieved DoNotAskAgain: $value")
        return value
    }

    // Save the flag that profile check has been completed
    fun setProfileChecked(isChecked: Boolean) {
        prefs.edit { putBoolean("isProfileChecked", isChecked) }
    }

    // Check if the profile check has been done before
    fun isProfileChecked(): Boolean {
        return prefs.getBoolean("isProfileChecked", false)
    }

    /** Provides the application [Context] (used for PackageManager or resource access). */
    fun getContext(): Context = context

    // ─────────────────────────── UI DIALOG FLAGS ───────────────────────────

    /** Saves the flag indicating whether to show notes dialog again. */
    fun saveShowNotesDialogSetting(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_NOTES_DIALOG, show) }
        logger.i("Saved showNotesDialog flag: $show")
    }

    /** @return `true` if notes dialog should be shown, default `true`. */
    fun getShowNotesDialogSetting(): Boolean {
        val value = prefs.getBoolean(KEY_SHOW_NOTES_DIALOG, true)
        logger.d("Retrieved showNotesDialog flag: $value")
        return value
    }

    // ─────────────────────────── RECENT LOGINS ───────────────────────────

    /**
     * Adds a login email to the recent logins list.
     * Keeps only the latest 5 unique entries (most recent first).
     *
     * @param email The login email to record.
     */
    @SuppressLint("NewApi")
    fun addRecentLogin(email: String) {
        val current = getRecentLogins().toMutableList()
        current.remove(email)
        current.add(0, email)
        while (current.size > 5) current.removeLast()
        prefs.edit { putStringSet(KEY_RECENT_LOGINS, current.toSet()) }
        logger.i("Added recent login: $email (total=${current.size})")
    }

    /** @return A list of recent login emails (most recent first). */
    fun getRecentLogins(): List<String> {
        val set = prefs.getStringSet(KEY_RECENT_LOGINS, emptySet()) ?: emptySet()
        return set.toList()
    }

    /**
     * Removes a specific email from the recent logins.
     * @param email The email address to remove.
     */
    fun removeRecentLogin(email: String) {
        val updated = getRecentLogins().filterNot { it == email }
        prefs.edit { putStringSet(KEY_RECENT_LOGINS, updated.toSet()) }
        logger.i("Removed recent login: $email (remaining=${updated.size})")
    }

    // ─────────────────────────── HISTORY RETENTION ───────────────────────────

    /** Saves the user’s preferred history retention period (in days). */
    fun saveHistoryRetention(days: Int) {
        prefs.edit { putInt(KEY_HISTORY_RETENTION, days) }
        logger.i("Saved history retention: $days days")
    }

    /**
     * Retrieves the number of days to retain local history.
     * @return The retention period, defaulting to 7 days.
     */
    fun getHistoryRetention(): Int {
        val days = prefs.getInt(KEY_HISTORY_RETENTION, 7)
        logger.d("Retrieved history retention: $days days")
        return days
    }


    /********* Required HL7 flow  preferences ****************/


    /**
     * Saves the transaction ID of the last HL7 message sent to the PMS.
     * This is used to track outbound messages and avoid duplicate sends
     * in case of retries, reconnects, or app restarts.
     */
    fun saveSentMessageTxnId(txnId: Long) {
        prefs.edit {
            putLong(KEY_SENT_TXN_ID, txnId)
        }
    }

    /**
     * Retrieves the transaction ID of the last HL7 message sent to the PMS.
     * Returns -1 if no message has been sent yet.
     */
    fun getSentMessageTxnId(): Long {
        return prefs.getLong(KEY_SENT_TXN_ID, -1L)
    }



    /**
     * Saves the NSD broadcast service type.
     * This defines how PillCounter advertises itself on the local network.
     */
    fun saveNsdBroadcastType(type: String) {
        prefs.edit { putString(KEY_NSD_BROADCAST_TYPE, type) }
        logger.i("Saved NSD broadcast type: $type")
    }

    /**
     * Retrieves the NSD broadcast service type.
     */
    fun getNsdBroadcastType(): String {
        val type = prefs.getString(KEY_NSD_BROADCAST_TYPE, "")
            ?: ""
        logger.d("Retrieved NSD broadcast type: $type")
        return type
    }



    /**
     * Saves the NSD discovery service type.
     * This defines which PMS services PillCounter searches for.
     */
    fun saveNsdDiscoveryType(type: String) {
        prefs.edit { putString(KEY_NSD_DISCOVERY_TYPE, type) }
        logger.i("Saved NSD discovery type: $type")
    }

    /**
     * Retrieves the NSD discovery service type.
     */
    fun getNsdDiscoveryType(): String {
        val type = prefs.getString(KEY_NSD_DISCOVERY_TYPE, "")
            ?: ""
        logger.d("Retrieved NSD discovery type: $type")
        return type
    }


    /**
     * Enables or disables HL7 functionality.
     * When disabled, no HL7 server/client operations should start.
     */
    fun setHl7Enabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_HL7_ENABLED, enabled) }
        logger.i("HL7 enabled set to: $enabled")
    }

    /**
     * Checks whether HL7 functionality is enabled.
     * @return true if enabled, false otherwise (default: true)
     */
    fun isHl7Enabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_HL7_ENABLED, true)
        logger.d("HL7 enabled: $enabled")
        return enabled
    }

    fun setSoundEnabled(enabled: Boolean) {
        val enabled = prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
        logger.i("setSoundEnabled : $enabled")
    }

    fun isSoundEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_SOUND, true)
        logger.d("isSoundEnabled : $enabled")
        return enabled
    }

    fun setHapticEnabled(enabled: Boolean) {
        val enabled = prefs.edit().putBoolean(KEY_HAPTIC, enabled).apply()
        logger.i("setHapticEnabled : $enabled")
    }

    fun isHapticEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_HAPTIC, true)
        logger.d("isHapticEnabled : $enabled")
        return enabled
    }

    fun setRequireBackCountEnabled(enabled: Boolean) {
        val enabled = prefs.edit().putBoolean(KEY_REQUIRE_BACK_COUNT, enabled).apply()
        logger.i("setRequireBackCountEnabled : $enabled")
    }

    fun isRequireBackCountEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_REQUIRE_BACK_COUNT, true)
        logger.d("isRequireBackCountEnabled : $enabled")
        return enabled
    }

    fun setRequireDoubleCountEnabled(enabled: Boolean) {
        val enabled = prefs.edit().putBoolean(KEY_REQUIRE_DOUBLE_COUNT, enabled).apply()
        logger.i("setRequireDoubleCountEnabled : $enabled")
    }

    fun isRequireDoubleCountEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_REQUIRE_DOUBLE_COUNT, true)
        logger.d("isRequireDoubleCountEnabled : $enabled")
        return enabled
    }

    fun setControlDrugTypes(controlDrugTypes: Set<String>) {
        prefs.edit { putStringSet(KEY_CONTROL_DRUG_TYPES, controlDrugTypes) }
        logger.i("setControlDrugTypes : ${controlDrugTypes.joinToString(",")}")
    }

    fun getControlDrugTypes(): Set<String> {
        val types = prefs.getStringSet(KEY_CONTROL_DRUG_TYPES, emptySet()) ?: emptySet()
        logger.d("getControlDrugTypes : ${types.joinToString(",")}")
        return types
    }

    fun setSoundOverride(enabled: Boolean){
        val enabled = prefs.edit().putBoolean(KEY_SOUND_OVERRIDE, enabled).apply()
        logger.i("setSoundOverride : $enabled")
    }

    fun isSoundOverride():Boolean{
        val enabled = prefs.getBoolean(KEY_SOUND_OVERRIDE, true)
        logger.i("isSoundOverride : $enabled")
        return enabled
    }

    fun saveBarcodeRegex(barcodeRegex: String) {
        prefs.edit { putString(KEY_BARCODE_REGEX, barcodeRegex) }
        logger.i("SaveBarcodeRegex securely (length=${barcodeRegex.length})")
    }

    fun getBarcodeRegex(): String? {
        val id = prefs.getString(KEY_BARCODE_REGEX, null)
        logger.d("getBarcodeRegex retrieved (exists=${id != null}, length=${id?.length ?: 0})")
        return id
    }

}
