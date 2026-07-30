package com.rite.pillcounting.core.scanning.presentation.viewmodel

import android.app.Application
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.data.PillScanningEvent
import com.rite.pillcounting.core.scanning.domain.model.BarcodeData
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.scanning.logic.PillDetectionModelLoader
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
 * Unit tests for [PillScanningViewModel] covering areas not exercised by the sibling
 * Event/Frame/NdcScan/State test files: [PillScanningViewModel.buildWorkflowSteps] (all
 * branches, pure logic), [PillScanningViewModel.moveNextStep] transitions, and the
 * dispense-flow bottle rescan handlers (onNdcRescannedDuringCount / ConfirmAddBottle /
 * ConfirmReplaceBottle / CancelAddBottle / CancelReplaceBottle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillScanningViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val stockTxnDao: StockTxnDao = mockk(relaxed = true)
    private val bottleInfoDao: BottleInfoDao = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val pillCountTxnDetailsDao: PillCountTxnDetailsDao = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val modelLoader: PillDetectionModelLoader = mockk(relaxed = true)
    private val performanceLogger: PerformanceLogger = mockk(relaxed = true)
    private val barcodeDecoder: BarcodeDecoder = mockk(relaxed = true)
    private val drugRepository: IDrugRepository = mockk(relaxed = true)
    private val drugImageDownloader: DrugImageDownloader = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)

    private lateinit var viewModel: PillScanningViewModel

    @Before
    fun setup() {
        every { application.applicationContext } returns application
        every { application.getString(any<Int>()) } returns "test error"
        every { application.getString(any<Int>(), any()) } returns "test error"
        every { pillCountTxnDetailsDao.observeAllForTxn(any(), any()) } returns flowOf(emptyList())
        coEvery { pillCountTxnDetailsDao.getLatestType(any()) } returns null
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        every { preferenceHelper.getControlDrugTypes() } returns emptySet()
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false

        viewModel = PillScanningViewModel(
            app = application,
            preferenceHelper = preferenceHelper,
            pillCountTxnDao = pillCountTxnDao,
            stockTxnDao = stockTxnDao,
            bottleInfoDao = bottleInfoDao,
            batchDao = batchDao,
            userDao = userDao,
            pillCountTxnDetailsDao = pillCountTxnDetailsDao,
            locationProvider = locationProvider,
            drugMasterDao = drugMasterDao,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            barcodeDecoder = barcodeDecoder,
            drugRepository = drugRepository,
            drugImageDownloader = drugImageDownloader,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // ─────────────────────────── buildWorkflowSteps ───────────────────────────

    @Test
    fun `buildWorkflowSteps returns SCAN and TARGET_VERIFICATION when isDispense is false regardless of other params`() {
        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = true, simpleFlow = false, drugType = "CII", isDispense = false
        )
        assertEquals(listOf(StepState.SCAN, StepState.TARGET_VERIFICATION), steps)
    }

    @Test
    fun `buildWorkflowSteps returns 3-step simple flow when isDispense true, simpleFlow true, and drugType is empty`() {
        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = false, simpleFlow = true, drugType = "", isDispense = true
        )
        assertEquals(listOf(StepState.SCAN, StepState.TARGET_VERIFICATION, StepState.VIAL), steps)
    }

    @Test
    fun `buildWorkflowSteps returns 3-step simple flow when drugType is the literal string null`() {
        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = false, simpleFlow = true, drugType = "null", isDispense = true
        )
        assertEquals(listOf(StepState.SCAN, StepState.TARGET_VERIFICATION, StepState.VIAL), steps)
    }

    @Test
    fun `buildWorkflowSteps returns base 4-step flow when drugType present and no double or back count`() {
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false

        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = false, simpleFlow = false, drugType = "REGULAR", isDispense = true
        )

        assertEquals(
            listOf(StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION, StepState.VIAL),
            steps
        )
    }

    @Test
    fun `buildWorkflowSteps adds TARGET_REVERIFICATION when double count enabled and drugType is a control type`() {
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns true
        every { preferenceHelper.getControlDrugTypes() } returns setOf("CII")
        every { preferenceHelper.isRequireBackCountEnabled() } returns false

        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = false, simpleFlow = false, drugType = "CII", isDispense = true
        )

        assertEquals(
            listOf(
                StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION,
                StepState.TARGET_REVERIFICATION, StepState.VIAL
            ),
            steps
        )
    }

    @Test
    fun `buildWorkflowSteps does not add TARGET_REVERIFICATION when double count enabled but drugType not a control type`() {
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns true
        every { preferenceHelper.getControlDrugTypes() } returns setOf("CII")
        every { preferenceHelper.isRequireBackCountEnabled() } returns false

        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = false, simpleFlow = false, drugType = "REGULAR", isDispense = true
        )

        assertFalse(steps.contains(StepState.TARGET_REVERIFICATION))
    }

    @Test
    fun `buildWorkflowSteps adds CONTAINER_PENDING when back count is enabled`() {
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns true

        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = false, simpleFlow = false, drugType = "REGULAR", isDispense = true
        )

        assertEquals(
            listOf(
                StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION,
                StepState.VIAL, StepState.CONTAINER_PENDING
            ),
            steps
        )
    }

    @Test
    fun `buildWorkflowSteps includes both TARGET_REVERIFICATION and CONTAINER_PENDING when both settings enabled`() {
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns true
        every { preferenceHelper.getControlDrugTypes() } returns setOf("CII")
        every { preferenceHelper.isRequireBackCountEnabled() } returns true

        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = true, simpleFlow = false, drugType = "CII", isDispense = true
        )

        assertEquals(
            listOf(
                StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION,
                StepState.TARGET_REVERIFICATION, StepState.VIAL, StepState.CONTAINER_PENDING
            ),
            steps
        )
    }

    @Test
    fun `buildWorkflowSteps treats isDispense null the same as true for step derivation`() {
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false

        val steps = viewModel.buildWorkflowSteps(
            isFromHl7 = false, simpleFlow = false, drugType = "REGULAR", isDispense = null
        )

        assertEquals(
            listOf(StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION, StepState.VIAL),
            steps
        )
    }

    // ─────────────────────────── isVialLastStep ───────────────────────────

    @Test
    fun `isVialLastStep returns false when steps list is empty`() {
        assertFalse(viewModel.isVialLastStep())
    }

    // ─────────────────────────── moveNextStep ───────────────────────────

    private fun setSteps(steps: List<StepState>) {
        val field = PillScanningViewModel::class.java.getDeclaredField("_steps")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<StepState>>
        flow.value = steps
    }

    private fun setCurrentStep(step: StepState) {
        val field = PillScanningViewModel::class.java.getDeclaredField("_currentStep")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<StepState>
        flow.value = step
    }

    @Test
    fun `moveNextStep advances to the next step in the list`() = runTest {
        setSteps(listOf(StepState.TARGET_VERIFICATION, StepState.VIAL))
        setCurrentStep(StepState.TARGET_VERIFICATION)

        viewModel.moveNextStep()
        advanceUntilIdle()

        assertEquals(StepState.VIAL, viewModel.currentStep.value)
    }

    @Test
    fun `moveNextStep on last step falls through to handleDone and shows no-transaction when total is zero`() = runTest {
        setSteps(listOf(StepState.TARGET_VERIFICATION))
        setCurrentStep(StepState.TARGET_VERIFICATION)
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(any()) } returns 0

        viewModel.moveNextStep()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNoTransaction)
    }

    @Test
    fun `moveNextStep with empty steps list falls through to handleDone`() = runTest {
        setSteps(emptyList())
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(any()) } returns 0

        viewModel.moveNextStep()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNoTransaction)
    }

    // ─────────────────────────── onNdcRescannedDuringCount ───────────────────────────

    @Test
    fun `onNdcRescannedDuringCount is a no-op when currentStep is not a counting step`() = runTest {
        setCurrentStep(StepState.VIAL)

        viewModel.onNdcRescannedDuringCount("012345", null)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAddBottleDialog)
        assertFalse(viewModel.uiState.value.showReplaceBottleDialog)
    }

    @Test
    fun `handleConfirmAddBottle is a no-op when there is no pending bottle scan`() = runTest {
        viewModel.onEvent(PillScanningEvent.ConfirmAddBottle)
        advanceUntilIdle()

        coVerify(exactly = 0) { pillCountTxnDao.updateBottleInfoList(any(), any()) }
    }

    @Test
    fun `handleConfirmReplaceBottle is a no-op when there is no pending bottle scan`() = runTest {
        viewModel.onEvent(PillScanningEvent.ConfirmReplaceBottle)
        advanceUntilIdle()

        coVerify(exactly = 0) { pillCountTxnDao.updateBottleInfoList(any(), any()) }
    }

    @Test
    fun `CancelAddBottle dismisses the add-bottle dialog`() = runTest {
        viewModel.onEvent(PillScanningEvent.CancelAddBottle)

        assertFalse(viewModel.uiState.value.showAddBottleDialog)
    }

    @Test
    fun `CancelReplaceBottle dismisses the replace-bottle dialog`() = runTest {
        viewModel.onEvent(PillScanningEvent.CancelReplaceBottle)

        assertFalse(viewModel.uiState.value.showReplaceBottleDialog)
    }

    // ─────────────────────────── attachCameraHelper / resetIdleTimer / pauseIdleTimer ───────────────────────────

    @Test
    fun `pauseIdleTimer cancels the idle job without throwing`() {
        viewModel.pauseIdleTimer()
        // No observable state change (idleJob is private); this asserts no exception
        // is thrown when called before/without a running timer.
    }

    @Test
    fun `resumePillDetection followed by pausePillDetection clears detectedPills`() {
        viewModel.resumePillDetection()
        viewModel.pausePillDetection()

        assertTrue(viewModel.uiState.value.detectedPills.isEmpty())
        assertTrue(viewModel.trayDetections.value.isEmpty())
    }
}
