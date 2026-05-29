package com.rite.pillcounting.core.settings.presentation.viewmodel

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.hl7.service.HL7Config
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.settings.domain.data.IApplicationSettingsRepository
import com.rite.pillcounting.core.settings.domain.data.IApplicationSettingsViewModel
import com.rite.pillcounting.core.settings.domain.model.ApplicationSettingsUiState
import com.rite.pillcounting.core.settings.domain.model.ColorSettings
import com.rite.pillcounting.core.settings.domain.model.Hl7ServiceConfig
import com.rite.pillcounting.core.settings.domain.model.SettingsDataDto
import com.rite.pillcounting.core.settings.domain.model.ThemeColors
import com.rite.pillcounting.core.settings.domain.model.enums.ScheduleCode
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import javax.inject.Inject

/**
 * ViewModel responsible for managing and exposing application settings to the UI layer.
 *
 * Responsibilities:
 * - Loads cached theme from [PreferenceHelper] immediately for fast startup.
 * - Fetches remote settings in the background and updates both UI + cache.
 * - Determines if app should show maintenance/update screen based on remote flags.
 * - Falls back to hardcoded defaults if neither cache nor remote settings are available.
 *
 * This ensures the user never waits on a blocking loading screen for theme data.
 */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    private val repository: IApplicationSettingsRepository,
    private val preferenceHelper: PreferenceHelper,
    private val txnDao: PillCountTxnDao,
    private val batchDao: BatchDao,
    private val hl7ServiceManager: Hl7ServiceManager,
    private val hl7EventHandler: Hl7EventHandler,
) : ViewModel(), IApplicationSettingsViewModel {

    private val logger = AppLogger.Companion.create<MainActivityViewModel>()

    // Holds the current state of "Ask to Add Notes"
    private val _isAskToAddNotes = MutableStateFlow(preferenceHelper.getShowNotesDialogSetting())
    val isAskToAddNotes: StateFlow<Boolean> = _isAskToAddNotes

    private val _selectedHistoryOption = MutableStateFlow(preferenceHelper.getHistoryRetention())
    val selectedHistoryOption: StateFlow<Int> = _selectedHistoryOption

    // Called when user toggles the switch
    fun toggleAskToAddNotes(newValue: Boolean) {
        _isAskToAddNotes.value = newValue
        preferenceHelper.saveShowNotesDialogSetting(newValue)
    }

    private val _uiState = MutableStateFlow(ApplicationSettingsUiState())
    override val uiState = _uiState.asStateFlow()

    private val _isSoundOn = MutableStateFlow(preferenceHelper.isSoundEnabled())
    val isSoundOn: StateFlow<Boolean> = _isSoundOn

    private val _isHapticOn = MutableStateFlow(preferenceHelper.isHapticEnabled())
    val isHapticOn: StateFlow<Boolean> = _isHapticOn

    private val _isRequireBackCountEnable = MutableStateFlow(preferenceHelper.isRequireBackCountEnabled())
    val isRequireBackCountEnable: StateFlow<Boolean> = _isRequireBackCountEnable

    private val _isRequireDoubleCountEnable = MutableStateFlow(preferenceHelper.isRequireDoubleCountEnabled())
    val isRequireDoubleCountEnable: StateFlow<Boolean> = _isRequireDoubleCountEnable

    private val _selectedSchedules =
        MutableStateFlow(loadSchedulesFromPrefs())

    val selectedSchedules: StateFlow<Set<ScheduleCode>> = _selectedSchedules

    private val _isSoundOverride = MutableStateFlow(preferenceHelper.isSoundOverride())
    val isSoundOverride: StateFlow<Boolean> = _isSoundOverride

    init {
        // Load cached/fallback theme instantly
        loadCachedOrFallbackTheme()

        // Start fetching remote theme in background
        fetchApplicationSettings()

        viewModelScope.launch(Dispatchers.IO) {
            deleteOldTransactions()
        }
    }

    /**
     * Loads cached theme colors if available, else applies fallback defaults.
     * This is called synchronously on init to avoid UI blocking.
     */
    private fun loadCachedOrFallbackTheme() {
        val cached = preferenceHelper.getThemeColors()
        if (cached != null) {
            logger.i("Loaded cached theme from preferences.")
            _uiState.update { it.copy(colorSettings = cached) }
        } else {
            logger.w("No cached theme found, applying fallback.")
            applyFallbackSettings()
        }
    }

    /**
     * Initiates a remote fetch for application settings in the background.
     * Updates the cache and UI if successful, otherwise retains cached/fallback values.
     */
    override fun fetchApplicationSettings() {
        viewModelScope.launch {
            try {
                logger.i("Fetching remote application settings...")
                val response = repository.getApplicationSettings()
                updateHl7Config(response)
                applyAndStoreSettings(response)

                evaluateHl7State()
            } catch (e: Exception) {
                logger.e("Failed to fetch settings. Keeping cached/fallback values.", e)
                _uiState.update { it.copy(errorMessage = e.message) }
            } finally {
                logger.d("Settings fetch process finished.")
            }
        }
    }

    /**
     * Applies new remote settings and updates cache + UI.
     */
    private fun applyAndStoreSettings(settings: ApiResponse<SettingsDataDto>) {
        logger.i("Successfully fetched remote settings.")

        val dto = settings.data
        val theme = dto?.settings?.colors

        _uiState.update {
            it.copy(
                colorSettings = theme ?: it.colorSettings, // keep existing if null
                appLogoUrl = dto?.settings?.appLogo ?: it.appLogoUrl,
                appSettings = dto,
                isMaintenanceMode = dto?.isMaintenanceMode ?: false,
                isUpdateRequired = isUpdateRequired(dto?.minVersion)
            )
        }

        theme?.let {
            preferenceHelper.saveThemeColors(it) // persist for next launch
            logger.i("Updated cached theme colors in preferences.")
        }

        // Persist the barcode template so RX scanning can parse the label format
        // on any subsequent launch without requiring a fresh settings fetch.
        dto?.hl7Config?.barcodeFormat?.takeIf { it.isNotBlank() }?.let { format ->
            preferenceHelper.saveBarcodeRegex(format)
            logger.i("Barcode format saved from settings: $format")
        }
    }

    /**
     * Applies hardcoded fallback color settings if cache + remote both fail.
     */
    private fun applyFallbackSettings() {
        logger.w("Applying hardcoded fallback settings.")

        val fallbackColors = ColorSettings(
            light = ThemeColors(
                primary = "#01BBD3",
                secondary = "#FD82B5",
                tertiary = "#333333",
                primaryBackground = "#EDEEEE",
                secondaryBackground = "#FFFFFF",
                textColor = "#666666",
                inputBackground = "#FFFFFF",
                statusChipBackgroundOnPrimary = "#FFFFFF",
                statusChipBackgroundOnSecondary = "#F5F4F4"
            ),
            dark = ThemeColors(
                primary = "#01BBD3",
                secondary = "#FD82B5",
                tertiary = "#FFFFFF",
                primaryBackground = "#333333",
                secondaryBackground = "#191919",
                textColor = "#EDEEEE",
                inputBackground = "#191919",
                statusChipBackgroundOnPrimary = "#191919",
                statusChipBackgroundOnSecondary = "#333333"
            )
        )

        _uiState.update {
            it.copy(
                colorSettings = fallbackColors,
                appLogoUrl = "default_logo_placeholder",
                isMaintenanceMode = false,
                isUpdateRequired = false
            )
        }
    }

    /**
     * Checks if update is required based on remote version vs current app version.
     */
    private fun isUpdateRequired(remoteVersion: String?): Boolean {
        if (remoteVersion.isNullOrBlank()) return false
        return try {
            val current = getCurrentAppVersion()
            compareVersions(remoteVersion, current) > 0
        } catch (_: Exception) {
            false
        }
    }

    /** Gets current app versionName from PackageManager. */
    private fun getCurrentAppVersion(): String {
        return try {
            val ctx = preferenceHelper.getContext()
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    /**
     * Compare two semantic version strings.
     * @return >0 if v1 > v2, <0 if v1 < v2, 0 if equal.
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".")
        val parts2 = v2.split(".")
        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val num1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val num2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    fun updateHistoryOption(optionDays: Int) {
        _selectedHistoryOption.value = optionDays
        preferenceHelper.saveHistoryRetention(optionDays)

        viewModelScope.launch(Dispatchers.IO) {
            deleteOldTransactions()
        }
    }

    private suspend fun deleteOldTransactions() {
        val optionDays = preferenceHelper.getHistoryRetention()

        try {
            // Compute cutoff in UTC to match DB timestamps

            //TODO(Move this to the dateUtils)
            val nowUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val cutoff = nowUtc.timeInMillis - optionDays * 24 * 60 * 60 * 1000L

            //TODO(Change to the logger)
            Log.d("DELETE_TXN", "Retention days: $optionDays, cutoff=${Date(cutoff)}")

            // Get all transactions older than cutoff regardless of isDeleted
            val oldTransactions = txnDao.getTransactionsBefore(cutoff)
            Log.d("DELETE_TXN", "Found ${oldTransactions.size} transactions to delete")

            oldTransactions.forEach { txn ->
                val filesToDelete = mutableListOf<String>()

                // Collect barcode image
                txn.barcodeImage?.let { filesToDelete.add(it) }

                // Collect details images
                val detailImages = txnDao.getTransactionDetailsImages(txn.txnId)
                filesToDelete.addAll(detailImages)

                // Delete transaction (assumes cascade deletes details)
                txnDao.deleteTransaction(txn.txnId)

                // Delete files from storage
                filesToDelete.forEach { path ->
                    val file = File(path)
                    if (file.exists()) {
                        if (file.delete()) {
                            Log.d("DELETE_TXN", "Deleted file: $path")
                        } else {
                            Log.w("DELETE_TXN", "Failed to delete file: $path")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("DELETE_TXN", "Error deleting old transactions", e)
        }
    }


    /**
     * Updates and caches the HL7 network service discovery (NSD) types from remote settings.
     */
    private fun updateHl7Config(setting: ApiResponse<SettingsDataDto>) {

        // Guard 1: Don't process HL7 config if HL7 is disabled for this device
        if (!preferenceHelper.isHl7Enabled()) {
            logger.i("HL7 disabled for this device — skipping HL7 config update")
            return
        }

        // Guard 2: Don't re-process if we already have a cached config
        // (avoids re-saving hostnames to prefs on every app launch)
        if (preferenceHelper.isHl7ConfigFetched()) {
            logger.i("HL7 config already cached — loading from prefs")
            _uiState.update {
                it.copy(
                    nsdBroadcastType = preferenceHelper.getHl7PillCounterHost(),
                    nsdDiscoveryType = preferenceHelper.getHl7PmsHost(),
                    isHl7Enabled = true
                )
            }
            return
        }

        // Guard 3: Server didn't return hl7_config (future server-side fix)
        val nsdDiscoveryType = Hl7ServiceConfig.PMS_HOST_NAME
        val nsdBroadcastType = Hl7ServiceConfig.PILL_COUNTER_HOST_NAME

        // Only store and expose if both values are non-empty
        if (nsdDiscoveryType.isBlank() || nsdBroadcastType.isBlank()) {
            logger.w("HL7 config received but host names are empty — skipping")
            return
        }

        // Persist so subsequent launches don't need to re-fetch
        preferenceHelper.saveHl7Config(
            pmsHost = nsdDiscoveryType,
            pillCounterHost = nsdBroadcastType
        )

        _uiState.update {
            it.copy(
                nsdBroadcastType = nsdBroadcastType,
                nsdDiscoveryType = nsdDiscoveryType,
                isHl7Enabled = true
            )
        }
        logger.i("NSD settings updated and saved to preferences:")
        logger.i("  • Broadcast Type: $nsdBroadcastType")
        logger.i("  • Discovery Type: $nsdDiscoveryType")
    }


    /**
     * Configures and starts the HL7 service and its event consumer.
     */
    private fun startHl7Service() {

        val broadCastServiceName = _uiState.value.nsdBroadcastType ?: return
        val discoverServiceName = _uiState.value.nsdDiscoveryType ?: return

        // Use terminal name from preferences, fallback to device model if not available
        val terminalName = preferenceHelper.getSelectedTerminalName() ?: "PillCounter-${Build.MODEL}"
        val config = HL7Config(
            serverPort = 2575,
            autoResponseDelayMs = 10_000L,
            nsdBroadcastServiceName = terminalName,
            nsdBroadcastType = broadCastServiceName,
            nsdDiscoveryType = discoverServiceName,
            imageServicePort = 8080,
            imageServiceSecurePort = 8443
        )
        hl7ServiceManager.initialize(config, hl7EventHandler)
    }


    /**
     * Checks conditions (e.g., user login or logout, HL7 enabled) and starts or stops the HL7 service accordingly.
     */
    fun evaluateHl7State() {
        val state = _uiState.value

        logger.i("evaluateHl7State called - isHl7Enabled=${state.isHl7Enabled}, isUserLoggedIn=${preferenceHelper.isUserLoggedIn()}")

        if (state.isHl7Enabled != true || !preferenceHelper.isUserLoggedIn()) {
            logger.i("HL7 disabled or user not logged in - stopping HL7 service")
            stopHl7Service()
        } else {
            logger.i("HL7 enabled and user logged in - waiting for terminal info from auth/me")
            // Don't start automatically - wait for terminal info
        }
    }

    /**
     * Starts HL7 service after terminal information is available from auth/me.
     * This should be called after the user detail API completes.
     */
    fun startHl7AfterTerminalLoaded() {
        val state = _uiState.value

        if (state.isHl7Enabled != true || !preferenceHelper.isUserLoggedIn()) {
            logger.w("Cannot start HL7 - either disabled or user not logged in")
            return
        }

        val terminalName = preferenceHelper.getSelectedTerminalName()
        if (terminalName.isNullOrEmpty()) {
            return
        }

        logger.i("Terminal name available: $terminalName - starting HL7 service")
        startHl7Service()
        logger.i("HL7 Started with terminal: $terminalName")
    }


    /**
     * Shuts down the HL7 service.
     */
    private fun stopHl7Service() {
        hl7ServiceManager.shutdown()
        logger.i("HL7 STOPPED")
    }


    /**
     * Re-evaluates the HL7 service state, typically called after a login or logout event.
     */
    fun onUserLoginOrLogOut() {
        evaluateHl7State()
    }

    // Called when user toggles the switch
    fun toggleSoundOnOff(newValue: Boolean) {
        preferenceHelper.setSoundEnabled(newValue)
        _isSoundOn.value = newValue
    }

    fun toggleHapticOnOff(newValue: Boolean) {
        preferenceHelper.setHapticEnabled(newValue)
        _isHapticOn.value = newValue
    }

    fun toggleRequireBackCountOnOff(newValue: Boolean) {
        preferenceHelper.setRequireBackCountEnabled(newValue)
        _isRequireBackCountEnable.value = newValue
    }

    fun toggleRequireDoubleCountOnOff(newValue: Boolean) {
        preferenceHelper.setRequireDoubleCountEnabled(newValue)
        _isRequireDoubleCountEnable.value = newValue
    }

    fun deleteAllTransaction() {
        viewModelScope.launch {
            txnDao.deleteAllTransactions()
            batchDao.deleteAll()
        }
    }

    fun toggleSchedule(code: ScheduleCode) {
        val updated = _selectedSchedules.value.toMutableSet().apply {
            if (contains(code)) remove(code) else add(code)
        }

        _selectedSchedules.value = updated

        preferenceHelper.setControlDrugTypes(
            updated.map { it.name }.toSet()
        )
    }

    private fun loadSchedulesFromPrefs(): Set<ScheduleCode> {
        if (!preferenceHelper.isControlDrugTypesInitialized()) {
            // Never explicitly set — first launch, seed with all schedules selected
            val defaults = ScheduleCode.entries.toSet()
            preferenceHelper.setControlDrugTypes(defaults.map { it.name }.toSet())
            return defaults
        }

        // User has explicitly saved a selection at least once; empty set is a valid choice
        return preferenceHelper.getControlDrugTypes().mapNotNull {
            runCatching { ScheduleCode.valueOf(it) }.getOrNull()
        }.toSet()
    }


    fun toggleSoundOverride(newValue: Boolean) {
        preferenceHelper.setSoundOverride(newValue)
        _isSoundOverride.value = newValue
    }

}