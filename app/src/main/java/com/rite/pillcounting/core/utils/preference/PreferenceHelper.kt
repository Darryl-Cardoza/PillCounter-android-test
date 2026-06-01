package com.rite.pillcounting.core.utils.preference

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.rite.pillcounting.core.settings.domain.model.ColorSettings
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.dashboard.domain.model.Terminal
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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

// HL7
private const val KEY_NSD_BROADCAST_TYPE = "key_nsd_broadcast_type"
private const val KEY_NSD_DISCOVERY_TYPE = "key_nsd_discovery_type"
private const val KEY_HL7_ENABLED = "key_hl7_enabled"
private const val KEY_SOUND = "key_pill_count_sound_enabled"
private const val KEY_HAPTIC = "key_pill_count_haptic_enabled"
private const val KEY_REQUIRE_BACK_COUNT = "key_require_back_count"
private const val KEY_REQUIRE_DOUBLE_COUNT = "key_require_double_count"
private const val KEY_CONTROL_DRUG_TYPES="key_control_drug_types"
private const val KEY_CONTROL_DRUG_TYPES_INITIALIZED = "key_control_drug_types_initialized"
private const val KEY_SOUND_OVERRIDE="key_sound_override"
private const val KEY_BARCODE_REGEX="key_barcode_regex"
private const val KEY_BUCKET_LIST="key_bucket_list"
private const val KEY_TERMINALS="key_terminals"
private const val KEY_SELECTED_TERMINAL_ID="key_selected_terminal_id"
private const val KEY_SELECTED_TERMINAL_NAME="key_selected_terminal_name"
private const val KEY_HAZARDOUS_DRUG = "key_hazardous_drug"
private const val KEY_HAZARDOUS_TRAY_COLORS = "key_hazardous_tray_colors"
private const val KEY_NON_HAZARDOUS_TRAY_COLORS = "key_non_hazardous_tray_colors"
private const val KEY_HL7_PMS_HOST = "key_hl7_pms_host"
private const val KEY_HL7_PILLCOUNTER_HOST = "key_hl7_pillcounter_host"
private const val KEY_HL7_CONFIG_FETCHED = "key_hl7_config_fetched"

// FCM
private const val KEY_LAST_SENT_FCM_TOKEN = "last_sent_fcm_token"

@Singleton
class PreferenceHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SecurePreferences = SecurePreferences(context)
    private val gson = Gson()
    private val logger = AppLogger.create<PreferenceHelper>()

    init {
        logger.i("SecurePreferences initialized with AES-256-GCM encryption.")
    }

    // ─────────────────────────── AUTH TOKENS ───────────────────────────

    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.putString(KEY_ACCESS_TOKEN, accessToken)
        prefs.putString(KEY_REFRESH_TOKEN, refreshToken)
        logger.i("Saved tokens securely (lengths: ${accessToken.length}, ${refreshToken.length})")
    }

    fun getAccessToken(): String? {
        val token = prefs.getString(KEY_ACCESS_TOKEN)
        logger.d("Access token retrieved (exists=${token != null}, length=${token?.length ?: 0})")
        return token
    }

    fun getRefreshToken(): String? {
        val token = prefs.getString(KEY_REFRESH_TOKEN)
        logger.d("Refresh token retrieved (exists=${token != null}, length=${token?.length ?: 0})")
        return token
    }

    fun clearTokens() {
        prefs.remove(KEY_ACCESS_TOKEN)
        prefs.remove(KEY_REFRESH_TOKEN)
        logger.w("Cleared authentication tokens from secure storage.")
    }

    // ─────────────────────────── USER SESSION ───────────────────────────

    fun setUserLoggedIn(loggedIn: Boolean) {
        prefs.putBoolean(KEY_USER_LOGGED_IN, loggedIn)
        logger.i("Set user login state: $loggedIn")
    }

    fun isUserLoggedIn(): Boolean {
        val state = prefs.getBoolean(KEY_USER_LOGGED_IN, false)
        logger.d("Checked user login state: $state")
        return state
    }

    // ─────────────────────────── USER IDENTIFIERS ───────────────────────────

    fun saveUserId(userId: String) {
        prefs.putString(KEY_USER_ID, userId)
        logger.i("Saved userId securely (length=${userId.length})")
    }

    fun getUserId(): String? {
        val id = prefs.getString(KEY_USER_ID)
        logger.d("UserId retrieved (exists=${id != null}, length=${id?.length ?: 0})")
        return id
    }

    fun saveLocalId(localId: Long) {
        prefs.putLong(KEY_LOCAL_ID, localId)
        logger.i("Saved localId")
    }

    fun getLocalId(): Long {
        val id = prefs.getLong(KEY_LOCAL_ID, 0)
        logger.d("Retrieved localId")
        return id
    }

    // ─────────────────────────── TRANSACTIONS ───────────────────────────

    fun saveTxnId(txnId: Long) {
        prefs.putLong(KEY_TXN_ID, txnId)
        logger.i("Saved transaction ID")
    }

    fun getTxnId(): Long {
        val id = prefs.getLong(KEY_TXN_ID, 0)
        logger.d("Retrieved transaction ID")
        return id
    }

    // ─────────────────────────── THEME CACHING ───────────────────────────

    fun saveThemeColors(theme: ColorSettings) {
        val json = gson.toJson(theme)
        prefs.putString(KEY_THEME_COLORS, json)
        logger.i("Saved theme colors (json length=${json.length})")
    }

    fun getThemeColors(): ColorSettings? {
        val json = prefs.getString(KEY_THEME_COLORS) ?: run {
            logger.w("No cached theme colors found.")
            return null
        }
        logger.d("Retrieved theme colors (json length=${json.length})")
        return gson.fromJson(json, ColorSettings::class.java)
    }

    // ─────────────────────────── USER SETTINGS ───────────────────────────

    fun saveDoNotAskAgain(doNotAsk: Boolean) {
        prefs.putBoolean(KEY_DO_NOT_ASK_AGAIN, doNotAsk)
        logger.i("Saved DoNotAskAgain flag: $doNotAsk")
    }

    fun isDoNotAskAgain(): Boolean {
        val value = prefs.getBoolean(KEY_DO_NOT_ASK_AGAIN, false)
        logger.d("Retrieved DoNotAskAgain: $value")
        return value
    }

    fun setProfileChecked(isChecked: Boolean) {
        prefs.putBoolean("isProfileChecked", isChecked)
    }

    fun isProfileChecked(): Boolean =
        prefs.getBoolean("isProfileChecked", false)

    fun getContext(): Context = context

    // ─────────────────────────── UI DIALOG FLAGS ───────────────────────────

    fun saveShowNotesDialogSetting(show: Boolean) {
        prefs.putBoolean(KEY_SHOW_NOTES_DIALOG, show)
        logger.i("Saved showNotesDialog flag: $show")
    }

    fun getShowNotesDialogSetting(): Boolean {
        val value = prefs.getBoolean(KEY_SHOW_NOTES_DIALOG, true)
        logger.d("Retrieved showNotesDialog flag: $value")
        return value
    }

    // ─────────────────────────── RECENT LOGINS ───────────────────────────
    // Stored as JSON string — SecurePreferences does not support StringSet

    @SuppressLint("NewApi")
    fun addRecentLogin(email: String) {
        val current = getRecentLogins().toMutableList()
        current.remove(email)
        current.add(0, email)
        while (current.size > 5) current.removeLast()
        prefs.putString(KEY_RECENT_LOGINS, gson.toJson(current))
        logger.i("Added recent login (total=${current.size})")
    }

    fun getRecentLogins(): List<String> {
        val json = prefs.getString(KEY_RECENT_LOGINS) ?: return emptyList()
        return gson.fromJson(json, Array<String>::class.java).toList()
    }

    fun removeRecentLogin(email: String) {
        val updated = getRecentLogins().filterNot { it == email }
        prefs.putString(KEY_RECENT_LOGINS, gson.toJson(updated))
        logger.i("Removed recent login (remaining=${updated.size})")
    }

    // ─────────────────────────── HISTORY RETENTION ───────────────────────────

    fun saveHistoryRetention(days: Int) {
        prefs.putInt(KEY_HISTORY_RETENTION, days)
        logger.i("Saved history retention: $days days")
    }

    fun getHistoryRetention(): Int {
        val days = prefs.getInt(KEY_HISTORY_RETENTION, 7)
        logger.d("Retrieved history retention: $days days")
        return days
    }

    // ─────────────────────────── HL7 MESSAGE TRACKING ───────────────────────────

    fun saveSentMessageTxnId(txnId: Long) {
        prefs.putLong(KEY_SENT_TXN_ID, txnId)
    }

    fun getSentMessageTxnId(): Long =
        prefs.getLong(KEY_SENT_TXN_ID, -1L)

    // ─────────────────────────── NSD / HL7 SETTINGS ───────────────────────────

    fun saveNsdBroadcastType(type: String) {
        prefs.putString(KEY_NSD_BROADCAST_TYPE, type)
        logger.i("Saved NSD broadcast type")
    }

    fun getNsdBroadcastType(): String =
        prefs.getString(KEY_NSD_BROADCAST_TYPE) ?: ""

    fun saveNsdDiscoveryType(type: String) {
        prefs.putString(KEY_NSD_DISCOVERY_TYPE, type)
        logger.i("Saved NSD discovery type")
    }

    fun getNsdDiscoveryType(): String =
        prefs.getString(KEY_NSD_DISCOVERY_TYPE) ?: ""

    fun setHl7Enabled(enabled: Boolean) {
        prefs.putBoolean(KEY_HL7_ENABLED, enabled)
        logger.i("HL7 enabled set to: $enabled")
    }

    fun isHl7Enabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_HL7_ENABLED, true)
        logger.d("HL7 enabled: $enabled")
        return enabled
    }

    fun setSoundEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_SOUND, enabled)
        logger.i("setSoundEnabled: $enabled")
    }

    fun isSoundEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_SOUND, true)
        logger.d("isSoundEnabled: $enabled")
        return enabled
    }

    fun setHapticEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_HAPTIC, enabled)
        logger.i("setHapticEnabled: $enabled")
    }

    fun isHapticEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_HAPTIC, true)
        logger.d("isHapticEnabled: $enabled")
        return enabled
    }

    fun setRequireBackCountEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_REQUIRE_BACK_COUNT, enabled)
        logger.i("setRequireBackCountEnabled: $enabled")
    }

    fun isRequireBackCountEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_REQUIRE_BACK_COUNT, true)
        logger.d("isRequireBackCountEnabled: $enabled")
        return enabled
    }

    fun setRequireDoubleCountEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_REQUIRE_DOUBLE_COUNT, enabled)
        logger.i("setRequireDoubleCountEnabled: $enabled")
    }

    fun isRequireDoubleCountEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_REQUIRE_DOUBLE_COUNT, true)
        logger.d("isRequireDoubleCountEnabled: $enabled")
        return enabled
    }

    // Stored as JSON string — SecurePreferences does not support StringSet
    fun setControlDrugTypes(controlDrugTypes: Set<String>) {
        prefs.putString(KEY_CONTROL_DRUG_TYPES, gson.toJson(controlDrugTypes))
        prefs.putBoolean(KEY_CONTROL_DRUG_TYPES_INITIALIZED, true)
        logger.i("setControlDrugTypes : ${controlDrugTypes.joinToString(",")}")
    }

    fun getControlDrugTypes(): Set<String> {
        val json = prefs.getString(KEY_CONTROL_DRUG_TYPES) ?: return emptySet()
        return gson.fromJson(json, Array<String>::class.java).toSet()
    }

    /** Returns true only after [setControlDrugTypes] has been called at least once. */
    fun isControlDrugTypesInitialized(): Boolean {
        return prefs.getBoolean(KEY_CONTROL_DRUG_TYPES_INITIALIZED, false)
    }

    fun setSoundOverride(enabled: Boolean) {
        prefs.putBoolean(KEY_SOUND_OVERRIDE, enabled)
        logger.i("setSoundOverride : $enabled")
    }

    fun isSoundOverride(): Boolean {
        val enabled = prefs.getBoolean(KEY_SOUND_OVERRIDE, true)
        logger.d("isSoundOverride: $enabled")
        return enabled
    }

    fun saveBarcodeRegex(barcodeRegex: String) {
        prefs.putString(KEY_BARCODE_REGEX, barcodeRegex)
        logger.i("SaveBarcodeRegex securely (length=${barcodeRegex.length})")
    }

    fun getBarcodeRegex(): String? {
        val id = prefs.getString(KEY_BARCODE_REGEX)
        logger.d("getBarcodeRegex retrieved (exists=${id != null})")
        return id
    }

    fun setKeyBucketList(bucketList: List<String>) {
        val json = gson.toJson(bucketList)
        prefs.putString(KEY_BUCKET_LIST, json)
        logger.i("Saved bucket list (size=${bucketList.size})")
    }

    fun getBucketList(): List<String> {
        val json = prefs.getString(KEY_BUCKET_LIST) ?: return emptyList()
        return gson.fromJson(json, Array<String>::class.java).toList()
    }

    // ─────────────────────────── HL7 CONFIG ───────────────────────────

    fun saveHl7Config(pmsHost: String, pillCounterHost: String) {
        prefs.putString(KEY_HL7_PMS_HOST, pmsHost)
        prefs.putString(KEY_HL7_PILLCOUNTER_HOST, pillCounterHost)
        prefs.putBoolean(KEY_HL7_CONFIG_FETCHED, true)
        logger.i("Saved HL7 config to prefs")
    }

    fun getHl7PmsHost(): String =
        prefs.getString(KEY_HL7_PMS_HOST) ?: ""

    fun getHl7PillCounterHost(): String =
        prefs.getString(KEY_HL7_PILLCOUNTER_HOST) ?: ""
    // ─────────────────────────── TERMINALS ───────────────────────────

    /**
     * Saves the list of terminals as JSON.
     * @param terminals List of Terminal objects to persist.
     */
    fun saveTerminals(terminals: List<Terminal>) {
        val json = gson.toJson(terminals)
        prefs.putString(KEY_TERMINALS, json)
        logger.i("Saved terminals list (size=${terminals.size})")
    }

    /**
     * Retrieves the saved list of terminals.
     * @return List of Terminal objects, or empty list if none found.
     */
    fun getTerminals(): List<Terminal> {
        val json = prefs.getString(KEY_TERMINALS, null)
        return if (json != null) {
            val array = gson.fromJson(json, Array<Terminal>::class.java)
            array.toList()
        } else {
            logger.d("No terminals found in preferences")
            emptyList()
        }
    }

    /**
     * Saves the selected terminal ID.
     * @param terminalId The ID of the selected terminal.
     */
    fun saveSelectedTerminalId(terminalId: String) {
        prefs.putString(KEY_SELECTED_TERMINAL_ID, terminalId)
        logger.i("Saved selected terminal ID: $terminalId")
    }

    /**
     * Retrieves the selected terminal ID.
     * @return The selected terminal ID, or null if not set.
     */
    fun getSelectedTerminalId(): String? {
        val id = prefs.getString(KEY_SELECTED_TERMINAL_ID, null)
        logger.d("Retrieved selected terminal ID: $id")
        return id
    }

    /**
     * Saves the selected terminal name.
     * @param terminalName The name of the selected terminal.
     */
    fun saveSelectedTerminalName(terminalName: String) {
        prefs.putString(KEY_SELECTED_TERMINAL_NAME, terminalName)
        logger.i("Saved selected terminal name: $terminalName")
    }

    /**
     * Retrieves the selected terminal name.
     * @return The selected terminal name, or null if not set.
     */
    fun getSelectedTerminalName(): String? {
        val name = prefs.getString(KEY_SELECTED_TERMINAL_NAME, null)
        logger.d("Retrieved selected terminal name: $name")
        return name
    }

    // ─────────────────────────── HAZARDOUS DRUG ───────────────────────────

    fun setHazardousDrugEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_HAZARDOUS_DRUG, enabled)
        logger.i("Hazardous drug enabled set to: $enabled")
    }

    fun isHazardousDrugEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_HAZARDOUS_DRUG, true)
        logger.d("isHazardousDrugEnabled: $enabled")
        return enabled
    }

    // ─────────────────────────── TRAY COLOR CLASSIFICATION ───────────────────────────

    fun getHazardousTrayColors(): Set<String> {
        val json = prefs.getString(KEY_HAZARDOUS_TRAY_COLORS) ?: return emptySet()
        return gson.fromJson(json, Array<String>::class.java).toSet()
    }

    fun addHazardousTrayColor(colorName: String) {
        val updated = getHazardousTrayColors().toMutableSet().apply { add(colorName) }
        prefs.putString(KEY_HAZARDOUS_TRAY_COLORS, gson.toJson(updated))
        logger.i("Added hazardous tray color: $colorName")
    }

    fun getNonHazardousTrayColors(): Set<String> {
        val json = prefs.getString(KEY_NON_HAZARDOUS_TRAY_COLORS) ?: return emptySet()
        return gson.fromJson(json, Array<String>::class.java).toSet()
    }

    fun addNonHazardousTrayColor(colorName: String) {
        val updated = getNonHazardousTrayColors().toMutableSet().apply { add(colorName) }
        prefs.putString(KEY_NON_HAZARDOUS_TRAY_COLORS, gson.toJson(updated))
        logger.i("Added non-hazardous tray color: $colorName")
    }

    fun clearAllTrayColorLists() {
        prefs.putString(KEY_HAZARDOUS_TRAY_COLORS, gson.toJson(emptySet<String>()))
        prefs.putString(KEY_NON_HAZARDOUS_TRAY_COLORS, gson.toJson(emptySet<String>()))
        logger.i("Cleared all tray color classification lists")
    }

    fun isHl7ConfigFetched(): Boolean =
        prefs.getBoolean(KEY_HL7_CONFIG_FETCHED, false)

    fun clearHl7Config() {
        prefs.remove(KEY_HL7_PMS_HOST)
        prefs.remove(KEY_HL7_PILLCOUNTER_HOST)
        prefs.putBoolean(KEY_HL7_CONFIG_FETCHED, false)
        logger.w("Cleared HL7 config from prefs")
    }

    // ─────────────────────────── FCM TOKEN ───────────────────────────

    fun saveLastSentFcmToken(token: String) {
        prefs.putString(KEY_LAST_SENT_FCM_TOKEN, token)
    }

    fun getLastSentFcmToken(): String? =
        prefs.getString(KEY_LAST_SENT_FCM_TOKEN)

    fun clearLastSentFcmToken() {
        prefs.remove(KEY_LAST_SENT_FCM_TOKEN)
    }

    // ─────────────────────────── GENERIC ───────────────────────────

    fun getInt(key: String, default: Int = 0): Int =
        prefs.getInt(key, default)

    fun putInt(key: String, value: Int) =
        prefs.putInt(key, value)
}