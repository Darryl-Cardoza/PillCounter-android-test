package com.rite.pillcounting.feature.settings.presentation.viewmodel

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.rite.pillcounting.core.faceAuth.logic.SessionLockController
import com.rite.pillcounting.core.health.logic.SessionHealthController
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.models.ScheduleCode
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import com.rite.pillcounting.feature.settings.domain.data.IApplicationSettingsRepository
import com.rite.pillcounting.feature.settings.domain.model.ApplicationSettingsHL7Config
import com.rite.pillcounting.feature.settings.domain.model.ApplicationSettingsResponse
import com.rite.pillcounting.feature.settings.domain.model.ColorSettings
import com.rite.pillcounting.feature.settings.domain.model.SettingsDataDto
import com.rite.pillcounting.feature.settings.domain.model.ThemeColors
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: IApplicationSettingsRepository
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var txnDao: PillCountTxnDao
    private lateinit var batchDao: BatchDao
    private lateinit var stockTxnDao: StockTxnDao
    private lateinit var bottleInfoDao: BottleInfoDao
    private lateinit var hl7ServiceManager: Hl7ServiceManager
    private lateinit var hl7EventHandler: Hl7EventHandler
    private lateinit var sessionLockController: SessionLockController
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
        statusChipBackgroundOnSecondary = "#888888"
    )

    private fun colorSettings() = ColorSettings(
        light = themeColors("#aaaaaa"),
        dark = themeColors("#bbbbbb")
    )

    private fun settingsResponse(
        colors: ColorSettings = colorSettings(),
        appLogo: String = "logo_url"
    ) = ApplicationSettingsResponse(
        colors = colors,
        appLogo = appLogo,
        placeholderLogo = "placeholder"
    )

    private fun dto(
        minVersion: String? = null,
        isMaintenanceMode: Boolean = false,
        settings: ApplicationSettingsResponse = settingsResponse(),
        hl7Config: ApplicationSettingsHL7Config? = null
    ) = SettingsDataDto(
        minVersion = minVersion,
        isMaintenanceMode = isMaintenanceMode,
        settings = settings,
        hl7Config = hl7Config
    )

    private fun apiResponse(data: SettingsDataDto?) = ApiResponse(
        status = 200,
        isSuccess = true,
        message = "ok",
        token = null,
        data = data
    )

    @Before
    fun setup() {
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
        txnDao = mockk(relaxed = true)
        batchDao = mockk(relaxed = true)
        stockTxnDao = mockk(relaxed = true)
        bottleInfoDao = mockk(relaxed = true)
        hl7ServiceManager = mockk(relaxed = true)
        hl7EventHandler = mockk(relaxed = true)
        sessionLockController = mockk(relaxed = true)
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
        every { preferenceHelper.isControlDrugTypesInitialized() } returns false
        every { preferenceHelper.getControlDrugTypes() } returns emptySet()
        every { preferenceHelper.isHl7Enabled() } returns false
        every { preferenceHelper.isHl7ConfigFetched() } returns false
        every { preferenceHelper.isUserLoggedIn() } returns false
        every { preferenceHelper.getSelectedTerminalName() } returns null

        coEvery { repository.getApplicationSettings() } returns apiResponse(dto())
        coEvery { txnDao.getTransactionsBefore(any()) } returns emptyList()
        coEvery { txnDao.getTransactionDetailsImages(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() =
        MainActivityViewModel(repository, preferenceHelper, txnDao, batchDao, stockTxnDao, bottleInfoDao, hl7ServiceManager, hl7EventHandler, sessionLockController, sessionHealthController)

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
    fun `init seeds default schedules when not initialized`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(ScheduleCode.entries.toSet(), vm.selectedSchedules.value)
        verify { preferenceHelper.setControlDrugTypes(ScheduleCode.entries.map { it.name }.toSet()) }
    }

    @Test
    fun `init parses saved schedules and skips invalid names`() = runTest(testDispatcher) {
        every { preferenceHelper.isControlDrugTypesInitialized() } returns true
        every { preferenceHelper.getControlDrugTypes() } returns setOf("CII", "CIV", "BOGUS")

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(setOf(ScheduleCode.CII, ScheduleCode.CIV), vm.selectedSchedules.value)
    }

    // ─────────────────────────── fetchApplicationSettings ───────────────────────────

    @Test
    fun `fetch success with theme persists theme and updates state`() = runTest(testDispatcher) {
        coEvery { repository.getApplicationSettings() } returns apiResponse(
            dto(isMaintenanceMode = true)
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
    fun `fetch with non-blank barcode format saves regex`() = runTest(testDispatcher) {
        coEvery { repository.getApplicationSettings() } returns apiResponse(
            dto(hl7Config = ApplicationSettingsHL7Config(barcodeFormat = "REGEX123"))
        )

        val vm = createViewModel()
        advanceUntilIdle()

        verify { preferenceHelper.saveBarcodeRegex("REGEX123") }
    }

    @Test
    fun `fetch with blank barcode format does not save regex`() = runTest(testDispatcher) {
        coEvery { repository.getApplicationSettings() } returns apiResponse(
            dto(hl7Config = ApplicationSettingsHL7Config(barcodeFormat = "   "))
        )

        val vm = createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { preferenceHelper.saveBarcodeRegex(any()) }
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
        every { context.packageName } returns "com.rite.pillcounting"
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
    fun `updateHl7Config loads from prefs when already fetched`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns true
        every { preferenceHelper.getHl7PillCounterHost() } returns "_pillcounting._tcp"
        every { preferenceHelper.getHl7PmsHost() } returns "_ritepmsserver._tcp"

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(true, state.isHl7Enabled)
        assertEquals("_pillcounting._tcp", state.nsdBroadcastType)
        assertEquals("_ritepmsserver._tcp", state.nsdDiscoveryType)
        verify(exactly = 0) { preferenceHelper.saveHl7Config(any(), any()) }
    }

    @Test
    fun `updateHl7Config saves config and updates state when fresh`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { preferenceHelper.isHl7ConfigFetched() } returns false

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(true, state.isHl7Enabled)
        assertEquals("_pillcounting._tcp", state.nsdBroadcastType)
        assertEquals("_ritepmsserver._tcp", state.nsdDiscoveryType)
        verify {
            preferenceHelper.saveHl7Config(
                pmsHost = "_ritepmsserver._tcp",
                pillCounterHost = "_pillcounting._tcp"
            )
        }
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
        val txn = PillCountTxnEntity(
            txnId = 5L,
            isDispense = true,
            status = CountStatus.COMPLETED,
            bottleInfoListJson = BottleInfoJson.encode(
                listOf(BottleInfo(txnId = 5L, barcodeImagePath = "C:/nonexistent/barcode.png"))
            ),
        )
        coEvery { txnDao.getTransactionsBefore(any()) } returns listOf(txn)
        coEvery { txnDao.getTransactionDetailsImages(5L) } returns listOf("C:/nonexistent/detail.png")

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify { txnDao.deleteTransaction(5L) }
    }

    @Test
    fun `deleteOldTransactions handles exception`() = runTest(testDispatcher) {
        coEvery { txnDao.getTransactionsBefore(any()) } throws RuntimeException("db error")

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
        coVerify(atLeast = 1) { txnDao.getTransactionsBefore(any()) }
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

        coVerify { txnDao.deleteAllTransactions() }
        coVerify { batchDao.deleteAll() }
    }

    @Test
    fun `toggleSchedule adds then removes a code`() = runTest(testDispatcher) {
        // start with empty saved selection
        every { preferenceHelper.isControlDrugTypesInitialized() } returns true
        every { preferenceHelper.getControlDrugTypes() } returns emptySet()

        val vm = createViewModel()
        advanceUntilIdle()

        // add
        vm.toggleSchedule(ScheduleCode.CII)
        assertTrue(vm.selectedSchedules.value.contains(ScheduleCode.CII))
        verify { preferenceHelper.setControlDrugTypes(setOf("CII")) }

        // remove
        vm.toggleSchedule(ScheduleCode.CII)
        assertFalse(vm.selectedSchedules.value.contains(ScheduleCode.CII))
        verify { preferenceHelper.setControlDrugTypes(emptySet()) }
    }

    @Test
    fun `clearTrayColorLists delegates to prefs`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.clearTrayColorLists()

        verify { preferenceHelper.clearAllTrayColorLists() }
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
        every { hl7EventHandler.connectionState } returns kotlinx.coroutines.flow.MutableStateFlow(true)

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
