package com.rite.pillcounting.feature.settings.presentation.viewmodel

import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.ViewModel
import com.rite.pillcounting.R
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.health.logic.SessionHealthController
import com.rite.pillcounting.core.hl7.service.HL7Config
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.feature.settings.domain.data.IApplicationSettingsRepository
import com.rite.pillcounting.feature.settings.domain.data.IApplicationSettingsViewModel
import com.rite.pillcounting.feature.settings.domain.model.ApplicationSettingsUiState
import com.rite.pillcounting.feature.settings.domain.model.ColorSettings
import com.rite.pillcounting.feature.settings.domain.model.Hl7ServiceConfig
import com.rite.pillcounting.feature.settings.domain.model.SettingsDataDto
import com.rite.pillcounting.feature.settings.domain.model.ThemeColors
import com.rite.pillcounting.core.models.ScheduleCode
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
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
    private val stockTxnDao: StockTxnDao,
    private val bottleInfoDao: BottleInfoDao,
    private val hl7ServiceManager: Hl7ServiceManager,
    private val hl7EventHandler: Hl7EventHandler,
    private val sessionHealthController: SessionHealthController,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel(), IApplicationSettingsViewModel {

    private val logger = AppLogger.Companion.create<MainActivityViewModel>()

    // The full config identity HL7 last started with, so a later settings/auth-me fetch
    // that returns different values (e.g. HL7 was preloaded from stale cached prefs on a
    // cold launch, then the network fetch lands with updated terminal/service name, PMS
    // IP, or connection flags) can detect the mismatch and restart HL7 with the fresh
    // config. Null means HL7 has not been started yet in this process — doubles as the
    // guard against evaluateHl7State() and startHl7AfterTerminalLoaded() both racing to
    // call through to Hl7ServiceManager.initialize for the same process.
    private var hl7StartedWithIdentity: Hl7Identity? = null

    private data class Hl7Identity(
        val broadCastServiceName: String,
        val discoverServiceName: String,
        val terminalName: String,
        val pmsIp: String?,
        val pmsPort: Int,
        val useStaticPmsConnection: Boolean,
        val bypassTls: Boolean,
        val hl7Version: String,
    )

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

    /** HL7 toggle from the portal (cached in prefs). */
    fun isHl7Enabled(): Boolean = preferenceHelper.isHl7Enabled()

    /** Live "use static PMS connection" flag, so the Settings screen reacts to changes made elsewhere (e.g. profile sync) without needing to be reopened. */
    val isUseStaticPmsConnection: StateFlow<Boolean> = callbackFlow {
        trySend(preferenceHelper.isUseStaticPmsConnection())

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == preferenceHelper.useStaticPmsConnectionKey) {
                trySend(preferenceHelper.isUseStaticPmsConnection())
            }
        }

        preferenceHelper.registerOnChangeListener(listener)
        awaitClose { preferenceHelper.unregisterOnChangeListener(listener) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        preferenceHelper.isUseStaticPmsConnection()
    )

    /**
     * Live PMS ip/port, so [ConnectionInfoScreen][com.rite.pillcounting.feature.settings.presentation.ConnectionInfoScreen]
     * reflects changes made elsewhere (e.g. profile sync) without needing to be re-entered.
     */
    val pmsConnection: StateFlow<Pair<String, String>> = callbackFlow {
        fun currentValue() = preferenceHelper.getPmsIP().orEmpty() to
            preferenceHelper.getPmsPort().takeIf { it != 0 }?.toString().orEmpty()

        trySend(currentValue())

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == preferenceHelper.pmsIpKey || key == preferenceHelper.pmsPortKey) {
                trySend(currentValue())
            }
        }

        preferenceHelper.registerOnChangeListener(listener)
        awaitClose { preferenceHelper.unregisterOnChangeListener(listener) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        preferenceHelper.getPmsIP().orEmpty() to preferenceHelper.getPmsPort().takeIf { it != 0 }?.toString().orEmpty()
    )

    private val _pmsTestConnectionState = MutableStateFlow<PmsTestConnectionState>(PmsTestConnectionState.Idle)
    val pmsTestConnectionState: StateFlow<PmsTestConnectionState> = _pmsTestConnectionState

    /** Resets test-connection result — call when [ConnectionInfoScreen][com.rite.pillcounting.feature.settings.presentation.ConnectionInfoScreen] is (re-)entered. */
    fun resetPmsTestConnectionState() {
        _pmsTestConnectionState.value = PmsTestConnectionState.Idle
    }

    /**
     * Reports whether the app can reach the configured PMS static IP/port.
     *
     * If the live MLLP client (owned by [Hl7ServiceManager]/[HL7Service]) is already
     * connected, reports success immediately instead of opening a second, independent
     * TCP socket to the same host:port — most PMS/MLLP listeners only accept one active
     * connection per client and will drop the live connection when a second competing
     * socket connects, which looked like the connection flapping during a test.
     * Only opens a standalone probe socket when the live client isn't already connected.
     */
    fun testPmsConnection() {
        val host = preferenceHelper.getPmsIP()
        val port = preferenceHelper.getPmsPort()

        if (host.isNullOrBlank() || port <= 0) {
            _pmsTestConnectionState.value =
                PmsTestConnectionState.Failed(preferenceHelper.getContext().getString(R.string.pms_not_configured))
            return
        }

        if (hl7ServiceManager.isPmsConnected()) {
            _pmsTestConnectionState.value = PmsTestConnectionState.Success
            return
        }

        _pmsTestConnectionState.value = PmsTestConnectionState.Testing

        viewModelScope.launch(ioDispatcher) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), PMS_TEST_CONNECTION_TIMEOUT_MS)
                }
                _pmsTestConnectionState.value = PmsTestConnectionState.Success
            } catch (e: Exception) {
                logger.w("testPmsConnection() — failed to reach $host:$port: ${e.message}")
                _pmsTestConnectionState.value =
                    PmsTestConnectionState.Failed(preferenceHelper.getContext().getString(R.string.pms_unable_to_reach))
            }
        }
    }

    companion object {
        private const val PMS_TEST_CONNECTION_TIMEOUT_MS = 5_000
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

    private val _isHazardousDrug = MutableStateFlow(preferenceHelper.isHazardousDrugEnabled())
    val isHazardousDrug: StateFlow<Boolean> = _isHazardousDrug

    private val _faceLockTimeoutMinutes = MutableStateFlow(preferenceHelper.getFaceLockTimeoutMinutes())
    val faceLockTimeoutMinutes: StateFlow<Int> = _faceLockTimeoutMinutes

    init {
        // Load cached/fallback theme instantly
        loadCachedOrFallbackTheme()

        // Start HL7 immediately from whatever's already cached in prefs (previous
        // successful fetch), instead of waiting on the network call below — a slow/failed
        // settings fetch on a cold app process (fresh install/build) must not block HL7
        // startup for the whole session. If the fetch below later returns different
        // NSD/terminal values, evaluateHl7State()'s config-changed check restarts HL7.
        preloadCachedHl7StateAndStart()

        // Start fetching remote theme/settings in background; reconciles HL7 config
        // once it lands.
        fetchApplicationSettings()

        viewModelScope.launch(ioDispatcher) {
            deleteOldTransactions()
        }
    }

    /** Populates [_uiState]'s HL7 fields from cached prefs (if any) and starts HL7 right away. */
    private fun preloadCachedHl7StateAndStart() {
        if (!preferenceHelper.isHl7ConfigFetched()) return
        _uiState.update {
            it.copy(
                nsdBroadcastType = preferenceHelper.getHl7PillCounterHost(),
                nsdDiscoveryType = preferenceHelper.getHl7PmsHost(),
                isHl7Enabled = preferenceHelper.isHl7Enabled()
            )
        }
        evaluateHl7State()
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
                // Forward the server-supplied offline threshold to the health controller so the
                // OFFLINE timer reflects the current backend policy. Null means the backend did
                // not include the field on this response — controller keeps its persisted value.
                response.data?.offlineSessionThresholdSeconds?.let { seconds ->
                    sessionHealthController.updateThreshold(seconds)
                }

                evaluateHl7State()
                // No explicit /health call here — MainActivity.onResume + DashboardViewModel
                // preflight both trigger checkHealth() which coalesces via the controller's
                // in-flight/throttle guard. Firing again here would just get swallowed by the
                // 5s throttle but still adds noise.
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

        viewModelScope.launch(ioDispatcher) {
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

            logger.d("Retention days: $optionDays, cutoff=${Date(cutoff)}")

            // Get all transactions older than cutoff regardless of isDeleted
            val oldTransactions = txnDao.getTransactionsBefore(cutoff)
            logger.d("Found ${oldTransactions.size} transactions to delete")

            oldTransactions.forEach { txn ->
                val filesToDelete = mutableListOf<String>()

                // Collect barcode images
                BottleInfoJson.decode(txn.bottleInfoListJson).mapNotNull { it.barcodeImagePath }.forEach { filesToDelete.add(it) }

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
                            logger.d("Deleted file: $path")
                        } else {
                            logger.w("Failed to delete file: $path")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            logger.e("Error deleting old transactions", e)
        }
    }


    /**
     * Applies the HL7 NSD service types from the settings response.
     *
     * The server value wins on every fetch — prefs are only the cold-start cache. The cached-
     * config guard that used to sit here returned early forever after the first successful
     * fetch, so a backend change could never reach the device.
     */
    private fun updateHl7Config(setting: ApiResponse<SettingsDataDto>) {
        if (!preferenceHelper.isHl7Enabled()) {
            logger.i("HL7 disabled for this device — skipping HL7 config update")
            return
        }

        val hl7Config = setting.data?.hl7Config
        val nsdDiscoveryType = Hl7ServiceConfig.resolvePmsHostName(hl7Config?.pmsHostName)
        val nsdBroadcastType =
            Hl7ServiceConfig.resolvePillCounterHostName(hl7Config?.pillCounterHostName)

        logger.block(
            "HL7-NSD · Service types from settings API",
            "Discovery (pms_host_name)" to nsdDiscoveryType,
            "Broadcast (pillcounter_host_name)" to nsdBroadcastType,
            "Server sent discovery" to (hl7Config?.pmsHostName ?: "— using default"),
            "Server sent broadcast" to (hl7Config?.pillCounterHostName ?: "— using default"),
            "Cached discovery" to preferenceHelper.getHl7PmsHost().ifBlank { "none" },
            "Cached broadcast" to preferenceHelper.getHl7PillCounterHost().ifBlank { "none" },
        )

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
    }


    /**
     * Configures and starts the HL7 service and its event consumer.
     *
     * Guarded by [hl7StartedWithIdentity] so the two independent triggers — [evaluateHl7State]
     * (fires at ViewModel init, covers an app relaunch with terminal info already cached)
     * and [startHl7AfterTerminalLoaded] (fires once auth/me returns, covers a fresh login) —
     * can never both call through to [Hl7ServiceManager.initialize] for the same process.
     */
    private fun startHl7Service() {
        // Defence in depth. Both callers already gate on login, but nothing in here did — so a
        // future third call site could have started HL7 for a logged-out user.
        if (!preferenceHelper.isUserLoggedIn()) {
            logger.w("startHl7Service() — refusing to start HL7: no user is logged in")
            return
        }

        val broadCastServiceName = _uiState.value.nsdBroadcastType ?: return
        val discoverServiceName = _uiState.value.nsdDiscoveryType ?: return
        // Use terminal name from preferences, fallback to device model if not available
        val terminalName = preferenceHelper.getSelectedTerminalName() ?: "PillCounter-${Build.MODEL}"
        val identity = Hl7Identity(
            broadCastServiceName = broadCastServiceName,
            discoverServiceName = discoverServiceName,
            terminalName = terminalName,
            pmsIp = preferenceHelper.getPmsIP(),
            pmsPort = preferenceHelper.getPmsPort(),
            useStaticPmsConnection = preferenceHelper.isUseStaticPmsConnection(),
            bypassTls = preferenceHelper.isBypassTlsEnabled(),
            hl7Version = preferenceHelper.getHl7Version(),
        )

        val previousIdentity = hl7StartedWithIdentity
        if (previousIdentity != null) {
            if (identity == previousIdentity) {
                logger.i("startHl7Service() — already running with this identity, skipping duplicate init")
                return
            }
            // Only the NSD service types moved: re-point the running service instead of tearing
            // it down. stopService() is async and initialize() fires straight after, so the
            // restart path races itself; the live path has no such window.
            if (isOnlyNsdTypeChange(previousIdentity, identity)) {
                logger.block(
                    "HL7-NSD · Only the service types changed — re-pointing the live service",
                    "Broadcast was" to previousIdentity.broadCastServiceName,
                    "Broadcast now" to identity.broadCastServiceName,
                    "Discovery was" to previousIdentity.discoverServiceName,
                    "Discovery now" to identity.discoverServiceName,
                )
                hl7StartedWithIdentity = identity
                hl7ServiceManager.updateServiceTypes(broadCastServiceName, discoverServiceName)
                return
            }
            logger.i("startHl7Service() — identity changed since last start (preloaded cache vs fresh fetch, or updated PMS/connection settings), restarting HL7")
            hl7ServiceManager.shutdown()
        }
        hl7StartedWithIdentity = identity

        val config = HL7Config.fromPreferences(
            preferenceHelper = preferenceHelper,
            nsdBroadcastType = broadCastServiceName,
            nsdDiscoveryType = discoverServiceName,
        )
        hl7ServiceManager.initialize(config, hl7EventHandler)
    }

    /** True when [new] differs from [old] only in the two NSD service types. */
    private fun isOnlyNsdTypeChange(old: Hl7Identity, new: Hl7Identity): Boolean =
        old.copy(
            broadCastServiceName = new.broadCastServiceName,
            discoverServiceName = new.discoverServiceName,
        ) == new


    /**
     * Checks conditions (e.g., user login or logout, HL7 enabled) and starts or stops the HL7 service accordingly.
     */
    fun evaluateHl7State() {
        val state = _uiState.value

        logger.i("evaluateHl7State called - isHl7Enabled=${state.isHl7Enabled}, isUserLoggedIn=${preferenceHelper.isUserLoggedIn()}")

        if (state.isHl7Enabled != true || !preferenceHelper.isUserLoggedIn()) {
            logger.i("HL7 disabled or user not logged in - stopping HL7 service")
            stopHl7Service()
        } else if (!preferenceHelper.getSelectedTerminalName().isNullOrEmpty()) {
            // Terminal name is already cached from a previous session (app relaunch while
            // still logged in) — start immediately here instead of waiting on
            // startHl7AfterTerminalLoaded(), which previously only fired from a
            // DashboardScreen Compose LaunchedEffect and so silently never ran at all if
            // Dashboard wasn't the screen composed at the moment terminal info loaded.
            logger.i("HL7 enabled, user logged in, terminal already cached — starting HL7 now")
            startHl7Service()
        } else {
            logger.i("HL7 enabled and user logged in - waiting for terminal info from auth/me")
            // Don't start automatically - wait for terminal info (fresh login / first launch)
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
        hl7StartedWithIdentity = null
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
            bottleInfoDao.deleteAll()
            stockTxnDao.deleteAll()
            batchDao.deleteAll()
        }
    }

    /** Replaces the controlled-drug schedules that require a double count. */
    fun updateSchedules(codes: Set<ScheduleCode>) {
        _selectedSchedules.value = codes
        preferenceHelper.setControlDrugTypes(codes.map { it.name }.toSet())
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

    fun toggleHazardousDrug(newValue: Boolean) {
        preferenceHelper.setHazardousDrugEnabled(newValue)
        _isHazardousDrug.value = newValue
    }

    fun clearTrayColorLists() {
        preferenceHelper.clearAllTrayColorLists()
        logger.i("Tray color classification lists cleared from Settings")
    }

    /** Updates the idle-lock timeout shown/used everywhere ([faceLockTimeoutMinutes]). */
    fun updateFaceLockTimeoutMinutes(minutes: Int) {
        preferenceHelper.saveFaceLockTimeoutMinutes(minutes)
        _faceLockTimeoutMinutes.value = minutes
    }

}

/** Result of a "Test Connection" attempt against the configured PMS static IP/port. */
sealed interface PmsTestConnectionState {
    data object Idle : PmsTestConnectionState
    data object Testing : PmsTestConnectionState
    data object Success : PmsTestConnectionState
    data class Failed(val reason: String) : PmsTestConnectionState
}
