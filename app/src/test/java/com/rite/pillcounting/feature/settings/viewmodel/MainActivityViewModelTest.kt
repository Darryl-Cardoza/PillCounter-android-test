package com.rite.pillcounting.feature.settings.viewmodel

import com.rite.pillcounting.core.models.ScheduleCode
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import com.rite.pillcounting.feature.settings.domain.data.IApplicationSettingsRepository
import com.rite.pillcounting.feature.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [MainActivityViewModel].
 *
 * fetchApplicationSettings is always mocked to throw so the init path takes the
 * error branch — this avoids touching ApiResponse parsing and PackageManager.
 * All toggle methods are synchronous state mutations verified via StateFlow values
 * and preference calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: IApplicationSettingsRepository = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val txnDao: PillCountTxnDao = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val stockTxnDao: StockTxnDao = mockk(relaxed = true)
    private val bottleInfoDao: BottleInfoDao = mockk(relaxed = true)
    private val hl7ServiceManager: Hl7ServiceManager = mockk(relaxed = true)
    private val hl7EventHandler: Hl7EventHandler = mockk(relaxed = true)

    private lateinit var viewModel: MainActivityViewModel

    @Before
    fun setup() {
        // Let settings fetch fail gracefully so init doesn't block on ApiResponse parsing.
        coEvery { repository.getApplicationSettings() } throws Exception("test — settings unavailable")
        // Seed relaxed bool prefs so init toggles start at known values.
        every { preferenceHelper.getShowNotesDialogSetting() } returns false
        every { preferenceHelper.getHistoryRetention() } returns 7
        every { preferenceHelper.isSoundEnabled() } returns false
        every { preferenceHelper.isHapticEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isSoundOverride() } returns false
        every { preferenceHelper.isHazardousDrugEnabled() } returns false
        // loadSchedulesFromPrefs: not initialized → seed all schedules
        every { preferenceHelper.isControlDrugTypesInitialized() } returns false
        viewModel = MainActivityViewModel(
            repository = repository,
            preferenceHelper = preferenceHelper,
            txnDao = txnDao,
            batchDao = batchDao,
            stockTxnDao = stockTxnDao,
            bottleInfoDao = bottleInfoDao,
            hl7ServiceManager = hl7ServiceManager,
            hl7EventHandler = hl7EventHandler,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // MAIN_VM_001
    @Test
    fun `toggleAskToAddNotes sets isAskToAddNotes to the new value and persists it`() {
        viewModel.toggleAskToAddNotes(true)

        assertTrue(viewModel.isAskToAddNotes.value)
        verify { preferenceHelper.saveShowNotesDialogSetting(true) }
    }

    // MAIN_VM_002
    @Test
    fun `toggleSoundOnOff sets isSoundOn and persists it`() {
        viewModel.toggleSoundOnOff(true)

        assertTrue(viewModel.isSoundOn.value)
        verify { preferenceHelper.setSoundEnabled(true) }
    }

    // MAIN_VM_003
    @Test
    fun `toggleHapticOnOff sets isHapticOn and persists it`() {
        viewModel.toggleHapticOnOff(true)

        assertTrue(viewModel.isHapticOn.value)
        verify { preferenceHelper.setHapticEnabled(true) }
    }

    // MAIN_VM_004
    @Test
    fun `toggleRequireBackCountOnOff sets isRequireBackCountEnable and persists it`() {
        viewModel.toggleRequireBackCountOnOff(true)

        assertTrue(viewModel.isRequireBackCountEnable.value)
        verify { preferenceHelper.setRequireBackCountEnabled(true) }
    }

    // MAIN_VM_005
    @Test
    fun `toggleRequireDoubleCountOnOff sets isRequireDoubleCountEnable and persists it`() {
        viewModel.toggleRequireDoubleCountOnOff(true)

        assertTrue(viewModel.isRequireDoubleCountEnable.value)
        verify { preferenceHelper.setRequireDoubleCountEnabled(true) }
    }

    // MAIN_VM_006
    @Test
    fun `toggleSoundOverride sets isSoundOverride and persists it`() {
        viewModel.toggleSoundOverride(true)

        assertTrue(viewModel.isSoundOverride.value)
        verify { preferenceHelper.setSoundOverride(true) }
    }

    // MAIN_VM_007
    @Test
    fun `toggleHazardousDrug sets isHazardousDrug and persists it`() {
        viewModel.toggleHazardousDrug(true)

        assertTrue(viewModel.isHazardousDrug.value)
        verify { preferenceHelper.setHazardousDrugEnabled(true) }
    }

    // MAIN_VM_008
    @Test
    fun `toggleSchedule adds a code that is not yet selected and removes it on second call`() {
        // All schedules start selected because isControlDrugTypesInitialized = false
        val all = ScheduleCode.entries.toSet()
        assertEquals(all, viewModel.selectedSchedules.value)

        // Remove CII
        viewModel.toggleSchedule(ScheduleCode.CII)
        assertFalse(viewModel.selectedSchedules.value.contains(ScheduleCode.CII))

        // Add it back
        viewModel.toggleSchedule(ScheduleCode.CII)
        assertTrue(viewModel.selectedSchedules.value.contains(ScheduleCode.CII))
    }

    // MAIN_VM_009
    @Test
    fun `deleteAllTransaction calls deleteAllTransactions and deleteAll on both DAOs`() = runTest {
        viewModel.deleteAllTransaction()
        advanceUntilIdle()

        io.mockk.coVerify { txnDao.deleteAllTransactions() }
        io.mockk.coVerify { batchDao.deleteAll() }
    }

    // MAIN_VM_010
    @Test
    fun `updateHistoryOption stores the option in preferences and selectedHistoryOption`() = runTest {
        viewModel.updateHistoryOption(60)
        advanceUntilIdle()

        assertEquals(60, viewModel.selectedHistoryOption.value)
        verify { preferenceHelper.saveHistoryRetention(60) }
    }

    // MAIN_VM_011
    @Test
    fun `clearTrayColorLists calls preferenceHelper clearAllTrayColorLists`() {
        viewModel.clearTrayColorLists()

        verify { preferenceHelper.clearAllTrayColorLists() }
    }

    // MAIN_VM_012
    @Test
    fun `evaluateHl7State with hl7 disabled stops the hl7 service`() {
        every { preferenceHelper.isUserLoggedIn() } returns true
        // uiState.isHl7Enabled starts at null/false — should trigger stopHl7Service
        viewModel.evaluateHl7State()

        verify { hl7ServiceManager.shutdown() }
    }
}
