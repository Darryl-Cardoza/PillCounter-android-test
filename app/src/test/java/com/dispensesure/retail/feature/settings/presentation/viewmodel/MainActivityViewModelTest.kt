package com.dispensesure.retail.feature.settings.presentation.viewmodel

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.room.withTransaction
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.health.logic.SessionHealthController
import com.dispensesure.retail.core.models.ApiResponse
import com.dispensesure.retail.core.models.ScheduleCode
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.DataPurged
import com.dispensesure.retail.core.audit.model.LocalDataCleared
import com.dispensesure.retail.core.audit.model.SecuritySetting
import com.dispensesure.retail.core.audit.model.SettingChanged
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.core.Hl7EventHandler
import com.dispensesure.retail.feature.hl7.core.Hl7ServiceManager
import com.dispensesure.retail.feature.settings.domain.data.IApplicationSettingsRepository
import com.dispensesure.retail.feature.settings.domain.model.ApplicationSettingsHL7Config
import com.dispensesure.retail.feature.settings.domain.model.ApplicationSettingsResponse
import com.dispensesure.retail.feature.settings.domain.model.ColorSettings
import com.dispensesure.retail.feature.settings.domain.model.SettingsDataDto
import com.dispensesure.retail.feature.settings.domain.model.ThemeColors
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityViewModelTest {

    private val regionProvider: RegionProvider = mockk { every { current } returns Region.US }

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: IApplicationSettingsRepository
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var stockBatchDao: StockBatchDao
    private lateinit var stockTxnDao: StockTxnDao
    private lateinit var stockBottleInfoDao: StockBottleInfoDao
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val appDatabase = mockk<AppDatabase>()

    // Runs the withTransaction block for real and records which DAO calls happened inside it.
    private var inTransaction = false
    private val callsOutsideTransaction = mutableListOf<String>()
    private fun stubTransaction() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { appDatabase.withTransaction(capture(block)) } coAnswers {
            inTransaction = true
            try { block.captured.invoke() } finally { inTransaction = false }
        }
    }
    private fun track(name: String) { if (!inTransaction) callsOutsideTransaction += name }
    private lateinit var hl7ServiceManager: Hl7ServiceManager
    private lateinit var hl7EventHandler: Hl7EventHandler
    private lateinit var sessionHealthController: SessionHealthController

    private fun themeColors(primary: String = "#000000") = ThemeColors(
        primary = primary,
        secondary = "#111111",
        tertiary = "#222222",
        primaryBackground = "#333333",
        secondaryBackground = "#444444",
        textColor = "#555555",
        inputBackground = "#666666",
        statusChipBackgroundOnPrimary = "#777777",
        statusChipBackgroundOnSecondary = "#888888",
    )

    private fun colorSettings() = ColorSettings(
        light = themeColors("#aaaaaa"),
        dark = themeColors("#bbbbbb"),
    )

    private fun settingsResponse(
        colors: ColorSettings = colorSettings(),
        appLogo: String = "logo_url",
        offlineSessionThresholdSeconds: Long? = 86400L,
    ) = ApplicationSettingsResponse(
        colors = colors,
        appLogo = appLogo,
        placeholderLogo = "placeholder",
        offlineSessionThresholdSeconds = offlineSessionThresholdSeconds,
    )

    private fun dto(
        minVersion: String? = null,
        isMaintenanceMode: Boolean = false,
        settings: ApplicationSettingsResponse = settingsResponse(),
        hl7Config: ApplicationSettingsHL7Config? = null,
    ) = SettingsDataDto(
        minVersion = minVersion,
        isMaintenanceMode = isMaintenanceMode,
        settings = settings,
        hl7Config = hl7Config,
    )

    private fun apiResponse(data: SettingsDataDto?) = ApiResponse(
        status = 200,
        isSuccess = true,
        message = "ok",
        token = null,
        data = data,
    )

    @Before
    fun setup() {
        stubTransaction()
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.i(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)

        repository = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        dispenseTxnDao = mockk(relaxed = true)
        stockBatchDao = mockk(relaxed = true)
        stockTxnDao = mockk(relaxed = true)
        stockBottleInfoDao = mockk(relaxed = true)
        hl7ServiceManager = mockk(relaxed = true)
        hl7EventHandler = mockk(relaxed = true)
        sessionHealthController = mockk(relaxed = true)

        // Defaults so init doesn't crash.
        every { preferenceHelper.getShowNotesDialogSetting() } returns false
        every { preferenceHelper.getHistoryRetention() } returns 30
        every { preferenceHelper.isSoundEnabled() } returns false
        every { preferenceHelper.isHapticEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isSoundOverride() } returns false
        every { preferenceHelper.isHazardousDrugEnabled() } returns false
        every { preferenceHelper.getThemeColors() } returns null
        every { preferenceHelper.isControlDrugTypesInitialized(Region.US) } returns false
        every { preferenceHelper.getControlDrugTypes(Region.US) } returns emptySet()
        every { preferenceHelper.isHl7Enabled() } returns false
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        every { preferenceHelper.isUserLoggedIn() } returns false
        every { preferenceHelper.getSelectedTerminalName() } returns null

        val defaultContext = mockk<Context>(relaxed = true)
        every { defaultContext.getString(com.dispensesure.retail.R.string.pms_not_configured) } returns "PMS IP/port not configured"
        every { defaultContext.getString(com.dispensesure.retail.R.string.pms_unable_to_reach) } returns "Unable to reach PMS server"
        every { preferenceHelper.getContext() } returns defaultContext

        coEvery { repository.getApplicationSettings() } returns apiResponse(dto())
        coEvery { dispenseTxnDao.getTransactionsBefore(any()) } returns emptyList()
        coEvery { dispenseTxnDao.getTransactionDetailsImages(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() = MainActivityViewModel(
        repository,
        preferenceHelper,
        dispenseTxnDao,
        stockBatchDao,
        stockTxnDao,
        stockBottleInfoDao,
        hl7ServiceManager,
        hl7EventHandler,
        mockk(relaxed = true),
        sessionHealthController,
        testDispatcher,
        regionProvider,
        auditLogger,
        appDatabase,
    )

    @Test
    fun `a security setting change is logged once with old and new`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleRequireBackCountOnOff(true)
        vm.toggleRequireBackCountOnOff(true)

        verify(exactly = 1) {
            auditLogger.log(SettingChanged(SecuritySetting.REQUIRE_BACK_COUNT, "false", "true"))
        }
    }

    @Test
    fun `re-selecting the same face lock timeout logs nothing`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateFaceLockTimeoutMinutes(vm.faceLockTimeoutMinutes.value)

        verify(exactly = 0) { auditLogger.log(match { it is SettingChanged }) }
    }

    // ─────────────────────────── init / theme loading ───────────────────────────

    @Test
    fun `init applies fallback when no cached theme`() = runTest(testDispatcher) {
        // No cached theme + failed remote fetch → fallback settings must persist.
        coEvery { repository.getApplicationSettings() } throws RuntimeException("offline")
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.colorSettings)
        assertEquals("default_logo_placeholder", state.appLogoUrl)
        assertFalse(state.isMaintenanceMode)
        assertFalse(state.isUpdateRequired)
    }

    @Test
    fun `init uses cached theme when present`() = runTest(testDispatcher) {
        val cached = colorSettings()
        every { preferenceHelper.getThemeColors() } returns cached

        val vm = createViewModel()
        // before fetch overrides, cached should already be applied; let everything settle
        advanceUntilIdle()

        // fetch dto has its own colors which overwrite, but cached path was exercised in init.
        verify(atLeast = 1) { preferenceHelper.getThemeColors() }
        assertNotNull(vm.uiState.value.colorSettings)
    }

    @Test
    fun `init seeds the US schedules when not initialized`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val usSchedules = ScheduleCode.forRegion(Region.US)
        assertEquals(usSchedules.toSet(), vm.selectedSchedules.value)
        verify { preferenceHelper.setControlDrugTypes(Region.US, usSchedules.map { it.name }.toSet()) }
    }

    @Test
    fun `canada user gets C, N and TS seeded under the canada keys`() = runTest(testDispatcher) {
        every { regionProvider.current } returns Region.CA
        every { preferenceHelper.isControlDrugTypesInitialized(Region.CA) } returns false

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(setOf(ScheduleCode.C, ScheduleCode.N, ScheduleCode.TS), vm.selectedSchedules.value)
        verify { preferenceHelper.setControlDrugTypes(Region.CA, setOf("C", "N", "TS")) }
    }

    @Test
    fun `canada saved selection drops US names`() = runTest(testDispatcher) {
        every { regionProvider.current } returns Region.CA
        every { preferenceHelper.isControlDrugTypesInitialized(Region.CA) } returns true
        every { preferenceHelper.getControlDrugTypes(Region.CA) } returns setOf("TS", "CII")

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(setOf(ScheduleCode.TS), vm.selectedSchedules.value)
    }

    @Test
    fun `canada updateSchedules saves under the canada keys`() = runTest(testDispatcher) {
        every { regionProvider.current } returns Region.CA
        every { preferenceHelper.isControlDrugTypesInitialized(Region.CA) } returns true
        every { preferenceHelper.getControlDrugTypes(Region.CA) } returns emptySet()
        val vm = createViewModel()

        vm.updateSchedules(setOf(ScheduleCode.N))

        verify { preferenceHelper.setControlDrugTypes(Region.CA, setOf("N")) }
    }

    @Test
    fun `init parses saved schedules and skips invalid names`() = runTest(testDispatcher) {
        every { preferenceHelper.isControlDrugTypesInitialized(Region.US) } returns true
        every { preferenceHelper.getControlDrugTypes(Region.US) } returns setOf("CII", "CIV", "BOGUS")

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(setOf(ScheduleCode.CII, ScheduleCode.CIV), vm.selectedSchedules.value)
    }

    // ─────────────────────────── fetchApplicationSettings ───────────────────────────

    @Test
    fun `fetch success with theme persists theme and updates state`() = runTest(testDispatcher) {
        coEvery { repository.getApplicationSettings() } returns apiResponse(
            dto(isMaintenanceMode = true),
        )

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("logo_url", state.appLogoUrl)
        assertTrue(state.isMaintenanceMode)
        assertNotNull(state.appSettings)
        verify { preferenceHelper.saveThemeColors(any()) }
    }

    @Test
    fun `fetch success with null data keeps existing colors`() = runTest(testDispatcher) {
        coEvery { repository.getApplicationSettings() } returns apiResponse(null)

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        // colors remain from fallback (non-null), maintenance defaults to false
        assertNotNull(state.colorSettings)
        assertFalse(state.isMaintenanceMode)
        assertNull(state.appSettings)
    }

    @Test
    fun `fetch exception sets errorMessage`() = runTest(testDispatcher) {
        coEvery { repository.getApplicationSettings() } throws RuntimeException("network down")

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("network down", vm.uiState.value.errorMessage)
    }

    // ─────────────────────────── isUpdateRequired / versions ───────────────────────────

    private fun mockContextVersion(versionName: String?) {
        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true)
        val info = PackageInfo().apply { this.versionName = versionName }
        every { context.packageManager } returns pm
        every { context.packageName } returns "com.dispensesure.retail"
        every { pm.getPackageInfo(any<String>(), any<Int>()) } returns info
        every { preferenceHelper.getContext() } returns context
    }

    @Test
    fun `minVersion blank yields no update required`() = runTest(testDispatcher) {
        coEvery { repository.getApplicationSettings() } returns apiResponse(dto(minVersion = "   "))

        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isUpdateRequired)
    }

    @Test
    fun `minVersion greater than current requires update`() = runTest(testDispatcher) {
        mockContextVersion("1.0.0")
        coEvery { repository.getApplicationSettings() } returns apiResponse(dto(minVersion = "2.0.0"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isUpdateRequired)
    }

    @Test
    fun `minVersion less than current does not require update`() = runTest(testDispatcher) {
        mockContextVersion("2.0.0")
        coEvery { repository.getApplicationSettings() } returns apiResponse(dto(minVersion = "1.0.0"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isUpdateRequired)
    }

    @Test
    fun `minVersion equal to current does not require update`() = runTest(testDispatcher) {
        mockContextVersion("1.2.3")
        coEvery { repository.getApplicationSettings() } returns apiResponse(dto(minVersion = "1.2.3"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isUpdateRequired)
    }

    @Test
    fun `version compare with different lengths`() = runTest(testDispatcher) {
        mockContextVersion("1.0")
        coEvery { repository.getApplicationSettings() } returns apiResponse(dto(minVersion = "1.0.1"))

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isUpdateRequired)
    }

    @Test
    fun `getCurrentAppVersion null versionName falls back then no update`() = runTest(testDispatcher) {
        mockContextVersion(null) // versionName null -> "0.0.0"
        coEvery { repository.getApplicationSettings() } returns apiResponse(dto(minVersion = "0.0.0"))

        val vm = createViewModel()
        advanceUntilIdle()

        // 0.0.0 vs 0.0.0 equal -> not required
        assertFalse(vm.uiState.value.isUpdateRequired)
    }

    @Test
    fun `getCurrentAppVersion throws yields 0_0_0 and update required when minVersion higher`() = runTest(testDispatcher) {
        every { preferenceHelper.getContext() } throws RuntimeException("no context")
        coEvery { repository.getApplicationSettings() } returns apiResponse(dto(minVersion = "1.0.0"))

        val vm = createViewModel()
        advanceUntilIdle()

        // current resolves to "0.0.0"; 1.0.0 > 0.0.0 -> update required
        assertTrue(vm.uiState.value.isUpdateRequired)
    }

    // ─────────────────────────── updateHl7Config ───────────────────────────

    @Test
    fun `updateHl7Config skips when hl7 disabled`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns false

        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.uiState.value.isHl7Enabled)
        verify(exactly = 0) { preferenceHelper.saveHl7Config(any(), any()) }
    }

    @Test
    fun `updateHl7Config uses server values over cached prefs`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns true
        every { preferenceHelper.getHl7PillCounterHost() } returns "_pillcounting._tcp"
        every { preferenceHelper.getHl7PmsHost() } returns "_ritepmsserver._tcp"
        coEvery { repository.getApplicationSettings() } returns apiResponse(
            dto(
                hl7Config = ApplicationSettingsHL7Config(
                    pmsHostName = "_newpms._tcp",
                    pillCounterHostName = "_newcounter._tcp"
                )
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(true, state.isHl7Enabled)
        assertEquals("_newcounter._tcp", state.nsdBroadcastType)
        assertEquals("_newpms._tcp", state.nsdDiscoveryType)
        verify {
            preferenceHelper.saveHl7Config(
                pmsHost = "_newpms._tcp",
                pillCounterHost = "_newcounter._tcp"
            )
        }
    }

    @Test
    fun `updateHl7Config falls back to constants when server omits host names`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        coEvery { repository.getApplicationSettings() } returns apiResponse(
            dto(
                hl7Config = ApplicationSettingsHL7Config(
                    pmsHostName = null,
                    pillCounterHostName = null
                )
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("_pillcounting._tcp", state.nsdBroadcastType)
        assertEquals("_ritepmsserver._tcp", state.nsdDiscoveryType)
        verify {
            preferenceHelper.saveHl7Config(
                pmsHost = "_ritepmsserver._tcp",
                pillCounterHost = "_pillcounting._tcp",
            )
        }
    }

    @Test
    fun `updateHl7Config falls back to constants when hl7Config is absent`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        coEvery { repository.getApplicationSettings() } returns apiResponse(dto(hl7Config = null))

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("_pillcounting._tcp", state.nsdBroadcastType)
        assertEquals("_ritepmsserver._tcp", state.nsdDiscoveryType)
    }

    @Test
    fun `nsd type change re-points the live service instead of restarting`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isUserLoggedIn() } returns true
        every { preferenceHelper.getSelectedTerminalName() } returns "Terminal-A"
        every { preferenceHelper.isHl7ConfigFetched() } returns true
        every { preferenceHelper.getHl7PillCounterHost() } returns "_pillcounting._tcp"
        every { preferenceHelper.getHl7PmsHost() } returns "_ritepmsserver._tcp"
        coEvery { repository.getApplicationSettings() } returns apiResponse(
            dto(
                hl7Config = ApplicationSettingsHL7Config(
                    pmsHostName = "_newpms._tcp",
                    pillCounterHostName = "_pillcounting._tcp"
                )
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        verify { hl7ServiceManager.updateServiceTypes("_pillcounting._tcp", "_newpms._tcp") }
        verify(exactly = 0) { hl7ServiceManager.shutdown() }
    }

    // ─────────────────────────── evaluateHl7State / start / stop ───────────────────────────

    @Test
    fun `evaluateHl7State stops service when disabled or not logged in`() = runTest(testDispatcher) {
        // hl7 disabled by default
        val vm = createViewModel()
        advanceUntilIdle()

        verify(atLeast = 1) { hl7ServiceManager.shutdown() }
    }

    @Test
    fun `evaluateHl7State enabled and logged in does not stop`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        every { preferenceHelper.isUserLoggedIn() } returns true

        val vm = createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { hl7ServiceManager.shutdown() }
    }

    @Test
    fun `onUserLoginOrLogOut delegates to evaluateHl7State`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onUserLoginOrLogOut()
        advanceUntilIdle()

        verify(atLeast = 1) { hl7ServiceManager.shutdown() }
    }

    @Test
    fun `local logout stops HL7 even when the settings fetch fails`() = runTest(testDispatcher) {
        // Local logout cleared the HL7 config, so init's preload skips; offline, the fetch throws.
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        coEvery { repository.getApplicationSettings() } throws RuntimeException("offline")

        val vm = createViewModel()
        advanceUntilIdle()
        verify(exactly = 0) { hl7ServiceManager.shutdown() }

        vm.onUserLoginOrLogOut()

        verify(exactly = 1) { hl7ServiceManager.shutdown() }
    }

    @Test
    fun `startHl7AfterTerminalLoaded returns when disabled`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startHl7AfterTerminalLoaded()
        advanceUntilIdle()

        verify(exactly = 0) { hl7ServiceManager.initialize(any(), any()) }
    }

    @Test
    fun `startHl7AfterTerminalLoaded returns when terminal name empty`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        every { preferenceHelper.isUserLoggedIn() } returns true
        every { preferenceHelper.getSelectedTerminalName() } returns ""

        val vm = createViewModel()
        advanceUntilIdle()

        vm.startHl7AfterTerminalLoaded()
        advanceUntilIdle()

        verify(exactly = 0) { hl7ServiceManager.initialize(any(), any()) }
    }

    @Test
    fun `startHl7AfterTerminalLoaded starts service with terminal name`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        every { preferenceHelper.isUserLoggedIn() } returns true
        every { preferenceHelper.getSelectedTerminalName() } returns "Terminal-A"

        val vm = createViewModel()
        advanceUntilIdle()

        vm.startHl7AfterTerminalLoaded()
        advanceUntilIdle()

        verify { hl7ServiceManager.initialize(any(), hl7EventHandler) }
    }

    @Test
    fun `startHl7Service proceeds via load-from-prefs branch`() = runTest(testDispatcher) {
        // Exercises startHl7Service when nsd types come from the cached-prefs branch.
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns true
        every { preferenceHelper.getHl7PillCounterHost() } returns "_pillcounting._tcp"
        every { preferenceHelper.getHl7PmsHost() } returns "_ritepmsserver._tcp"
        every { preferenceHelper.isUserLoggedIn() } returns true
        every { preferenceHelper.getSelectedTerminalName() } returns "Terminal-A"

        val vm = createViewModel()
        advanceUntilIdle()

        vm.startHl7AfterTerminalLoaded()
        advanceUntilIdle()

        verify { hl7ServiceManager.initialize(any(), hl7EventHandler) }
    }

    @Test
    fun `startHl7Service uses default terminal name when prefs null`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        every { preferenceHelper.isUserLoggedIn() } returns true
        // first getSelectedTerminalName for guard returns non-empty, second (inside startHl7Service) returns null
        every { preferenceHelper.getSelectedTerminalName() } returnsMany listOf("Terminal-A", null)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.startHl7AfterTerminalLoaded()
        advanceUntilIdle()

        verify { hl7ServiceManager.initialize(any(), hl7EventHandler) }
    }

    // ─────────────────────────── deleteOldTransactions ───────────────────────────

    @Test
    fun `deleteOldTransactions processes txn with images and missing files`() = runTest(testDispatcher) {
        val txn = DispenseTxnEntity(
            txnId = 5L,
            status = CountStatus.COMPLETED,
            bottlesJson = DispenseBottleJson.encode(
                listOf(DispenseBottle(txnId = 5L, barcodeImagePath = "C:/nonexistent/barcode.png")),
            ),
        )
        coEvery { dispenseTxnDao.getTransactionsBefore(any()) } returns listOf(txn)
        coEvery { dispenseTxnDao.getTransactionDetailsImages(5L) } returns listOf("C:/nonexistent/detail.png")

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify { dispenseTxnDao.deleteTransaction(5L) }
    }

    @Test
    fun `retention purge lists only the txns it deleted`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.getTransactionsBefore(any()) } returns listOf(
            DispenseTxnEntity(txnId = 5L, status = CountStatus.COMPLETED),
            DispenseTxnEntity(txnId = 6L, status = CountStatus.COMPLETED),
        )
        coEvery { dispenseTxnDao.deleteTransaction(5L) } returns 0
        coEvery { dispenseTxnDao.deleteTransaction(6L) } returns 1

        createViewModel()
        advanceUntilIdle()

        verify { auditLogger.log(match { it is DataPurged && it.txnIds == listOf(6L) && it.count == 1 }) }
    }

    @Test
    fun `deleteOldTransactions handles exception`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.getTransactionsBefore(any()) } throws RuntimeException("db error")

        val vm = createViewModel()
        advanceUntilIdle()

        // No crash; nothing asserted beyond construction surviving.
        assertNotNull(vm)
    }

    @Test
    fun `updateHistoryOption sets option and deletes old transactions`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateHistoryOption(7)
        advanceUntilIdle()

        assertEquals(7, vm.selectedHistoryOption.value)
        verify { preferenceHelper.saveHistoryRetention(7) }
        coVerify(atLeast = 1) { dispenseTxnDao.getTransactionsBefore(any()) }
    }

    // ─────────────────────────── toggles ───────────────────────────

    @Test
    fun `toggleAskToAddNotes updates state and prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleAskToAddNotes(true)

        assertTrue(vm.isAskToAddNotes.value)
        verify { preferenceHelper.saveShowNotesDialogSetting(true) }
    }

    @Test
    fun `toggleSoundOnOff updates state and prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleSoundOnOff(true)

        assertTrue(vm.isSoundOn.value)
        verify { preferenceHelper.setSoundEnabled(true) }
    }

    @Test
    fun `toggleHapticOnOff updates state and prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleHapticOnOff(true)

        assertTrue(vm.isHapticOn.value)
        verify { preferenceHelper.setHapticEnabled(true) }
    }

    @Test
    fun `toggleRequireBackCountOnOff updates state and prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleRequireBackCountOnOff(true)

        assertTrue(vm.isRequireBackCountEnable.value)
        verify { preferenceHelper.setRequireBackCountEnabled(true) }
    }

    @Test
    fun `toggleRequireDoubleCountOnOff updates state and prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleRequireDoubleCountOnOff(true)

        assertTrue(vm.isRequireDoubleCountEnable.value)
        verify { preferenceHelper.setRequireDoubleCountEnabled(true) }
    }

    @Test
    fun `toggleSoundOverride updates state and prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleSoundOverride(true)

        assertTrue(vm.isSoundOverride.value)
        verify { preferenceHelper.setSoundOverride(true) }
    }

    @Test
    fun `toggleHazardousDrug updates state and prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleHazardousDrug(true)

        assertTrue(vm.isHazardousDrug.value)
        verify { preferenceHelper.setHazardousDrugEnabled(true) }
    }

    @Test
    fun `deleteAllTransaction clears txns and batches`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteAllTransaction()
        advanceUntilIdle()

        coVerify { dispenseTxnDao.deleteAllTransactions() }
        coVerify { stockBatchDao.deleteAll() }
    }

    @Test
    fun `clear all reads and deletes inside one transaction`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.getAllSyncStates() } coAnswers { track("dispense getAllSyncStates"); emptyList() }
        coEvery { stockBatchDao.getAllSyncStates(any()) } coAnswers { track("batch getAllSyncStates"); emptyList() }
        coEvery { dispenseTxnDao.deleteAllTransactions() } coAnswers { track("dispense_txn"); 0 }
        coEvery { stockBottleInfoDao.deleteAll() } coAnswers { track("stock_bottle_info"); 0 }
        coEvery { stockTxnDao.deleteAll() } coAnswers { track("stock_txn"); 0 }
        coEvery { stockBatchDao.deleteAll() } coAnswers { track("stock_batch"); 0 }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteAllTransaction()
        advanceUntilIdle()

        coVerify { stockBatchDao.deleteAll() }
        assertEquals(emptyList<String>(), callsOutsideTransaction)
    }

    @Test
    fun `deleteAllTransaction logs the row counts the deletes returned`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.deleteAllTransactions() } returns 4
        coEvery { stockBottleInfoDao.deleteAll() } returns 3
        coEvery { stockTxnDao.deleteAll() } returns 2
        coEvery { stockBatchDao.deleteAll() } returns 1
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteAllTransaction()
        advanceUntilIdle()

        val expectedRowsDeleted = mapOf("dispense_txn" to 4, "stock_bottle_info" to 3, "stock_txn" to 2, "stock_batch" to 1)
        verify { auditLogger.log(match { it is LocalDataCleared && it.rowsDeleted == expectedRowsDeleted }) }
    }

    @Test
    fun `updateSchedules writes the chosen set to state and prefs`() = runTest(testDispatcher) {
        every { preferenceHelper.isControlDrugTypesInitialized(Region.US) } returns true
        every { preferenceHelper.getControlDrugTypes(Region.US) } returns emptySet()

        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateSchedules(setOf(ScheduleCode.CII, ScheduleCode.CIV))

        assertEquals(setOf(ScheduleCode.CII, ScheduleCode.CIV), vm.selectedSchedules.value)
        verify { preferenceHelper.setControlDrugTypes(Region.US, setOf("CII", "CIV")) }
    }

    @Test
    fun `updateSchedules allows an empty set`() = runTest(testDispatcher) {
        every { preferenceHelper.isControlDrugTypesInitialized(Region.US) } returns true
        every { preferenceHelper.getControlDrugTypes(Region.US) } returns setOf("CII")

        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateSchedules(emptySet())

        assertTrue(vm.selectedSchedules.value.isEmpty())
        verify { preferenceHelper.setControlDrugTypes(Region.US, emptySet()) }
    }

    @Test
    fun `clearTrayColorLists delegates to prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.clearTrayColorLists()

        verify { preferenceHelper.clearAllTrayColorLists() }
    }

    @Test
    fun `clearTrayColorLists logs set to cleared only when a color was stored`() = runTest(testDispatcher) {
        every { preferenceHelper.getHazardousTrayColor() } returns "BLUE"
        val vm = createViewModel()
        advanceUntilIdle()

        vm.clearTrayColorLists()

        verify { auditLogger.log(SettingChanged(SecuritySetting.TRAY_COLORS, "set", "cleared")) }
    }

    @Test
    fun `clearTrayColorLists logs nothing when no color was stored`() = runTest(testDispatcher) {
        every { preferenceHelper.getHazardousTrayColor() } returns null
        val vm = createViewModel()
        advanceUntilIdle()

        vm.clearTrayColorLists()

        verify(exactly = 0) { auditLogger.log(match { it is SettingChanged }) }
    }

    @Test
    fun `fetchApplicationSettings interface method can be re-invoked`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.fetchApplicationSettings()
        advanceUntilIdle()

        coVerify(atLeast = 2) { repository.getApplicationSettings() }
    }

    // ─────────────────────────── testPmsConnection ───────────────────────────

    @Test
    fun `testPmsConnection fails immediately when pms ip is not configured`() = runTest(testDispatcher) {
        every { preferenceHelper.getPmsIP() } returns null
        every { preferenceHelper.getPmsPort() } returns 0

        val vm = createViewModel()
        advanceUntilIdle()

        vm.testPmsConnection()

        val state = vm.pmsTestConnectionState.value
        assertTrue(state is PmsTestConnectionState.Failed)
        assertEquals("PMS IP/port not configured", (state as PmsTestConnectionState.Failed).reason)
    }

    @Test
    fun `testPmsConnection fails immediately when pms port is not configured`() = runTest(testDispatcher) {
        every { preferenceHelper.getPmsIP() } returns "10.0.0.5"
        every { preferenceHelper.getPmsPort() } returns 0

        val vm = createViewModel()
        advanceUntilIdle()

        vm.testPmsConnection()

        assertTrue(vm.pmsTestConnectionState.value is PmsTestConnectionState.Failed)
    }

    @Test
    fun `testPmsConnection succeeds immediately when mllp client already connected`() = runTest(testDispatcher) {
        every { preferenceHelper.getPmsIP() } returns "10.0.0.5"
        every { preferenceHelper.getPmsPort() } returns 2575
        every { hl7ServiceManager.isPmsConnected() } returns true

        val vm = createViewModel()
        advanceUntilIdle()

        vm.testPmsConnection()

        assertEquals(PmsTestConnectionState.Success, vm.pmsTestConnectionState.value)
    }

    @Test
    fun `resetPmsTestConnectionState resets to Idle`() = runTest(testDispatcher) {
        every { preferenceHelper.getPmsIP() } returns null
        every { preferenceHelper.getPmsPort() } returns 0

        val vm = createViewModel()
        advanceUntilIdle()

        vm.testPmsConnection()
        assertTrue(vm.pmsTestConnectionState.value is PmsTestConnectionState.Failed)

        vm.resetPmsTestConnectionState()
        assertEquals(PmsTestConnectionState.Idle, vm.pmsTestConnectionState.value)
    }
}
