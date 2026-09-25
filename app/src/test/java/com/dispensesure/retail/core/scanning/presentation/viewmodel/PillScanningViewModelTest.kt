package com.dispensesure.retail.core.scanning.presentation.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.dao.BatchDao
import com.dispensesure.retail.core.room.dao.BottleInfoDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.PillCountTxnDao
import com.dispensesure.retail.core.room.dao.PillCountTxnDetailsDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.BatchEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.PillCountTxnDetailsEntity
import com.dispensesure.retail.core.room.models.PillCountTxnEntity
import com.dispensesure.retail.core.room.models.dtos.TxnWithDetails
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.CountType
import com.dispensesure.retail.core.scanning.data.DrugImageDownloader
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.core.scanning.domain.data.IDrugRepository
import com.dispensesure.retail.core.scanning.domain.data.PillScanningEvent
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.common.SoundUtils
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.util.MainDispatcherRule
import androidx.room.withTransaction
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

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
    private val operatorNameProvider: OperatorNameProvider = mockk(relaxed = true)
    private val pillCountTxnDetailsDao: PillCountTxnDetailsDao = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val modelLoader: PillDetectionModelLoader = mockk(relaxed = true)
    private val performanceLogger: PerformanceLogger = mockk(relaxed = true)
    private val barcodeDecoder: BarcodeDecoder = mockk(relaxed = true)
    private val drugRepository: IDrugRepository = mockk(relaxed = true)
    private val drugImageDownloader: DrugImageDownloader = mockk(relaxed = true)
    private val hl7Repository: Hl7Repository = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val appDatabase: AppDatabase = mockk(relaxed = true)

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
            operatorNameProvider = operatorNameProvider,
            pillCountTxnDetailsDao = pillCountTxnDetailsDao,
            locationProvider = locationProvider,
            drugMasterDao = drugMasterDao,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            barcodeDecoder = barcodeDecoder,
            drugRepository = drugRepository,
            drugImageDownloader = drugImageDownloader,
            hl7Repository = hl7Repository,
            appDatabase = appDatabase,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // ─────────────────────────── playCountSoundIfEnabled ───────────────────────────

    @Test
    fun `playCountSoundIfEnabled routes the count cue through SoundUtils`() {
        mockkObject(SoundUtils)
        every { SoundUtils.playCountSound(any()) } returns Unit
        every { preferenceHelper.isSoundEnabled() } returns true
        every { preferenceHelper.isHapticEnabled() } returns false

        viewModel.playCountSoundIfEnabled()

        verify(exactly = 1) { SoundUtils.playCountSound(any()) }
    }

    @Test
    fun `playCountSoundIfEnabled stays silent when the sound preference is off`() {
        mockkObject(SoundUtils)
        every { SoundUtils.playCountSound(any()) } returns Unit
        every { preferenceHelper.isSoundEnabled() } returns false
        every { preferenceHelper.isHapticEnabled() } returns false

        viewModel.playCountSoundIfEnabled()

        verify(exactly = 0) { SoundUtils.playCountSound(any()) }
    }

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

    // ─────────────────────────── enterScanStep ───────────────────────────

    @Test
    fun `enterScanStep publishes the dispense workflow steps`() = runTest {
        viewModel.setScanType(CountType.FIXED.name)
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getTxnWithDetails(7L) } returns TxnWithDetails(
            txnId = 7L,
            drugName = "Drug",
            drugId = 42L,
            ndc = "NDC1",
            targetCount = 30,
            note = null,
            createdAt = 0L,
            bottleInfoListJson = null,
            totalPillCount = 0,
            isDispense = true,
            drugType = "REGULAR",
            txnDetails = emptyList(),
            isComingFromHL7 = false,
        )
        coEvery { drugMasterDao.getDrugById(42L) } returns DrugMasterEntity(
            drugId = 42L, ndc = "NDC1", drugName = "Drug", drugType = "REGULAR"
        )

        viewModel.enterScanStep()
        advanceUntilIdle()

        assertEquals(
            listOf(StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION, StepState.VIAL),
            viewModel.steps.value
        )
    }

    @Test
    fun `enterScanStep makes SCAN the current step without waiting on the steps lookup`() = runTest {
        viewModel.setScanType(CountType.FIXED.name)
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDao.getTxnWithDetails(7L) } returns null

        viewModel.enterScanStep()

        assertEquals(StepState.SCAN, viewModel.currentStep.value)
    }

    @Test
    fun `enterScanStep uses the stock-count steps for a REGULAR count`() = runTest {
        viewModel.setScanType(CountType.REGULAR.name)

        viewModel.enterScanStep()
        advanceUntilIdle()

        assertEquals(listOf(StepState.SCAN, StepState.TARGET_VERIFICATION), viewModel.steps.value)
    }

    // ─────────────────────────── resetTransaction ───────────────────────────

    @Test
    fun `resetTransaction hard-deletes the details and clears the txn fields`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDetailsDao.getImagePathsForTxn(7L) } returns emptyList()

        viewModel.resetTransaction()

        coVerify(exactly = 1) { pillCountTxnDetailsDao.deleteAllForTxn(7L) }
        coVerify(exactly = 1) { pillCountTxnDao.resetForRecount(7L, any()) }
    }

    @Test
    fun `resetTransaction deletes the captured image files`() = runTest {
        val image = File.createTempFile("reset", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDetailsDao.getImagePathsForTxn(7L) } returns listOf(image.absolutePath)

        viewModel.resetTransaction()

        assertFalse(image.exists())
    }

    @Test
    fun `resetTransaction clears the staging buffer`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { pillCountTxnDetailsDao.getImagePathsForTxn(7L) } returns emptyList()
        stageDetail(pillCount = 5)

        viewModel.resetTransaction()

        @Suppress("UNCHECKED_CAST")
        val staged = getPrivateField("stagedDetails") as MutableList<PillCountTxnDetailsEntity>
        assertTrue(staged.isEmpty())
    }

    // ─────────────────────────── observeResetAvailability ───────────────────────────

    @Test
    fun `reset is unavailable once the dispense txn has synced`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        every { pillCountTxnDao.observeById(7L) } returns flowOf(
            PillCountTxnEntity(
                txnId = 7L, isDispense = true, status = CountStatus.PARTIAL, isSynced = true,
            )
        )

        viewModel.observeResetAvailability(batchId = 0L)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canReset)
    }

    @Test
    fun `reset is available while the dispense txn is unsynced`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        every { pillCountTxnDao.observeById(7L) } returns flowOf(
            PillCountTxnEntity(
                txnId = 7L, isDispense = true, status = CountStatus.PARTIAL, isSynced = false,
            )
        )

        viewModel.observeResetAvailability(batchId = 0L)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canReset)
    }

    @Test
    fun `reset is unavailable once the batch has acked a chunk`() = runTest {
        every { preferenceHelper.getTxnId() } returns 0L
        every { batchDao.observeById(9L) } returns flowOf(
            BatchEntity(batchId = 9L, isSynced = false, lastAckedChunkIndex = 2)
        )

        viewModel.observeResetAvailability(batchId = 9L)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canReset)
    }

    @Test
    fun `reset is available for a batch-less stock count`() = runTest {
        every { preferenceHelper.getTxnId() } returns 0L

        viewModel.observeResetAvailability(batchId = 0L)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canReset)
    }

    @Test
    fun `reset goes unavailable when the txn syncs while the screen is up`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        val txn = MutableStateFlow(
            PillCountTxnEntity(
                txnId = 7L, isDispense = true, status = CountStatus.PARTIAL, isSynced = false,
            )
        )
        every { pillCountTxnDao.observeById(7L) } returns txn

        viewModel.observeResetAvailability(batchId = 0L)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canReset)

        txn.value = txn.value.copy(isSynced = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canReset)
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

    // ─────────────────────────── handleDone → notes dialog ───────────────────────────

    @Test
    fun `DoneClicked shows the notes dialog for an HL7 txn when the notes setting is on`() = runTest {
        // HL7 txns used to skip the notes prompt; the setting is now the only gate.
        seedTxnInfoIsDispense(isDispense = true, isComingFromHL7 = true)
        setCurrentStep(StepState.VIAL)
        every { preferenceHelper.getShowNotesDialogSetting() } returns true
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(any()) } returns 30

        viewModel.onEvent(PillScanningEvent.DoneClicked)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNotesDialog)
        // getById is only reached by handleConfirmDone, so the notes prompt still gates completion.
        coVerify(exactly = 0) { pillCountTxnDao.getById(any()) }
    }

    @Test
    fun `DoneClicked skips the notes dialog for an HL7 txn when the notes setting is off`() = runTest {
        seedTxnInfoIsDispense(isDispense = true, isComingFromHL7 = true)
        setCurrentStep(StepState.VIAL)
        every { preferenceHelper.getShowNotesDialogSetting() } returns false
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(any()) } returns 30

        viewModel.onEvent(PillScanningEvent.DoneClicked)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNotesDialog)
        // No confirm dialog any more: Done completes the txn straight through.
        coVerify(exactly = 1) { pillCountTxnDao.getById(any()) }
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

    // ─────────────────────────── handleAddTransaction: frame at the tap ───────────────────────────

    private fun tapAdd() = viewModel.onEvent(
        PillScanningEvent.AddTransactionDetailClicked(
            filteredCount = 5,
            stepType = StepState.TARGET_VERIFICATION,
        )
    )

    // drugImagePlaceholder() is outside any try; with stubbed Android its toBitmap() hits a
    // null Bitmap and would kill the save before the insert. No drawable = no placeholder.
    private fun stubSavePath() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.getDrawable(any(), any()) } returns null
    }

    @Test
    fun `Add with no camera frame shows a toast and records nothing`() = runTest {
        mockkObject(UserInterfaceUtils)
        every { UserInterfaceUtils.showToast(any(), any<String>(), any()) } returns Unit

        tapAdd()
        advanceUntilIdle()

        verify(exactly = 1) { UserInterfaceUtils.showToast(any(), any<String>(), any()) }
        assertFalse(viewModel.uiState.value.isAddCooldown)
        // Signature unset, so the retry isn't rejected as a duplicate.
        assertNull(getPrivateField("lastAddedScanSignature"))
        coVerify(exactly = 0) { pillCountTxnDetailsDao.insert(any()) }
    }

    @Test
    fun `Add takes the frame at the tap and a newer frame is left alone`() = runTest {
        stubSavePath()
        val tapFrame = mockk<Bitmap>(relaxed = true)
        setPrivateField("currentFrameBitmap", tapFrame)

        tapAdd()
        // Taken synchronously on the tap, before the save coroutine runs.
        assertNull(getPrivateField("currentFrameBitmap"))

        val newerFrame = mockk<Bitmap>(relaxed = true)
        setPrivateField("currentFrameBitmap", newerFrame)

        coVerify(timeout = 3000) { pillCountTxnDetailsDao.insert(any()) }
        verify { tapFrame.recycle() }
        verify(exactly = 0) { newerFrame.recycle() }
        assertEquals(newerFrame, getPrivateField("currentFrameBitmap"))
    }

    @Test
    fun `Add does not wait for a location lookup`() = runTest {
        stubSavePath()
        coEvery { locationProvider.getCurrentLocationAsString() } coAnswers { awaitCancellation() }
        setPrivateField("currentFrameBitmap", mockk<Bitmap>(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) { pillCountTxnDetailsDao.insert(any()) }
    }

    @Test
    fun `attachCameraHelper starts a location lookup`() = runTest {
        viewModel.attachCameraHelper(mockk(relaxed = true))

        coVerify(timeout = 3000) { locationProvider.getCurrentLocationAsString() }
    }

    // ─────────────────────────── flushStagedDetails (deferred stock commit) ───────────────────────────

    private fun setPrivateField(name: String, value: Any?) {
        val field = PillScanningViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(viewModel, value)
    }

    private fun getPrivateField(name: String): Any? {
        val field = PillScanningViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(viewModel)
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedTxnInfoIsDispense(isDispense: Boolean, isComingFromHL7: Boolean = false) {
        val flow = getPrivateField("_txnInfo") as MutableStateFlow<TxnWithDetails?>
        flow.value = TxnWithDetails(
            txnId = 0L,
            drugName = null,
            drugId = 42L,
            ndc = null,
            targetCount = null,
            note = null,
            createdAt = 0L,
            bottleInfoListJson = null,
            totalPillCount = 0,
            isDispense = isDispense,
            drugType = null,
            txnDetails = emptyList(),
            isComingFromHL7 = isComingFromHL7,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun stageDetail(pillCount: Int) {
        val list = getPrivateField("stagedDetails") as MutableList<PillCountTxnDetailsEntity>
        list.add(
            PillCountTxnDetailsEntity(
                pillCount = pillCount,
                type = StepState.SCAN.toString(),
            )
        )
    }

    /**
     * Puts the VM into the deferred-DispenseFlow stock session shape so
     * flushStagedDetails takes the `stockDrugId != 0L` branch that runs
     * withTransaction. Fresh batch (stockCountBatchId == 0L), no pre-existing
     * bottle (stockBottleId == 0L), no pre-existing header (stockTxnId == 0L).
     */
    private fun seedDeferredStockSession() {
        setPrivateField("isStockCountSession", true)
        setPrivateField("stockDrugId", 42L)
        setPrivateField("stockBottleId", 0L)
        setPrivateField("stockTxnId", 0L)
        setPrivateField("stockCountBatchId", 0L)
        seedTxnInfoIsDispense(isDispense = false)
    }

    @Test
    fun `flushStagedDetails deferred path commits withTransaction and publishes minted batchId`() = runTest {
        // Standard trick for suspend-extension `withTransaction` (RoomDatabaseKt):
        // a plain relaxed AppDatabase mock returns the default suspend result WITHOUT
        // running the lambda, so the transaction body never executes and the caller
        // suspends indefinitely on the returned Continuation. mockkStatic on the file
        // is the only way to make the mock actually invoke the block.
        mockkStatic("androidx.room.RoomDatabaseKt")
        val txBlock = slot<suspend () -> Pair<Long, Long>>()
        coEvery {
            appDatabase.withTransaction<Pair<Long, Long>>(capture(txBlock))
        } coAnswers { txBlock.captured.invoke() }

        coEvery { batchDao.insert(any()) } returns 111L
        coEvery { stockTxnDao.findByDrugInBatch(111L, 42L) } returns null
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 222L
        coEvery { bottleInfoDao.insert(any()) } returns 333L
        every { preferenceHelper.getTxnId() } returns 5L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(5L) } returns 0

        seedDeferredStockSession()
        stageDetail(pillCount = 7)

        viewModel.onEvent(PillScanningEvent.DoneClicked)
        advanceUntilIdle()

        coVerify(exactly = 1) { batchDao.insert(any()) }
        coVerify(exactly = 1) { stockTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 1) { bottleInfoDao.insert(any()) }
        coVerify(exactly = 1) { stockTxnDao.refreshBatchTotalNdcs(111L) }
        assertEquals(111L, viewModel.stockCountCommittedBatchId.value)
    }

    @Test
    fun `flushStagedDetails deferred path stamps the scanned lot expiry and serial on the loose line`() = runTest {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val txBlock = slot<suspend () -> Pair<Long, Long>>()
        coEvery {
            appDatabase.withTransaction<Pair<Long, Long>>(capture(txBlock))
        } coAnswers { txBlock.captured.invoke() }
        coEvery { batchDao.insert(any()) } returns 111L
        coEvery { stockTxnDao.findByDrugInBatch(111L, 42L) } returns null
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 222L
        coEvery { bottleInfoDao.insert(any()) } returns 333L
        every { preferenceHelper.getTxnId() } returns 5L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(5L) } returns 0

        seedDeferredStockSession()
        setPrivateField("stockLotNo", "10522")
        setPrivateField("stockExpNo", "04-30-2028")
        setPrivateField("stockSerialNo", "1000002616")
        stageDetail(pillCount = 30)

        viewModel.onEvent(PillScanningEvent.DoneClicked)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            bottleInfoDao.insert(match {
                it.lotNo == "10522" && it.expNo == "04-30-2028" && it.serialNo == "1000002616" &&
                    it.bottleQty == 0 && it.looseQty == 30
            })
        }
    }

    @Test
    fun `flushStagedDetails existing-header path stamps the scanned lot expiry and serial on the loose line`() = runTest {
        coEvery { bottleInfoDao.insert(any()) } returns 333L
        every { preferenceHelper.getTxnId() } returns 5L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(5L) } returns 0

        seedDeferredStockSession()
        setPrivateField("stockTxnId", 9L)
        setPrivateField("stockLotNo", "10522")
        setPrivateField("stockExpNo", "04-30-2028")
        setPrivateField("stockSerialNo", "1000002616")
        stageDetail(pillCount = 30)

        viewModel.onEvent(PillScanningEvent.DoneClicked)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            bottleInfoDao.insert(match {
                it.stockTxnId == 9L && it.lotNo == "10522" && it.expNo == "04-30-2028" &&
                    it.serialNo == "1000002616" && it.looseQty == 30
            })
        }
    }

    @Test
    fun `flushStagedDetails deferred path preserves staging and surfaces error when withTransaction throws`() = runTest {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery {
            appDatabase.withTransaction<Pair<Long, Long>>(any())
        } throws RuntimeException("db boom")

        every { preferenceHelper.getTxnId() } returns 5L
        coEvery { pillCountTxnDetailsDao.getTotalPillCountForTxn(5L) } returns 0

        seedDeferredStockSession()
        stageDetail(pillCount = 7)

        viewModel.onEvent(PillScanningEvent.DoneClicked)
        advanceUntilIdle()

        assertEquals("test error", viewModel.uiState.value.showErrorMessage)
        val staged = getPrivateField("stagedDetails") as List<*>
        assertEquals(1, staged.size)
        coVerify(exactly = 0) { stockTxnDao.updateStatus(any(), any()) }
        assertEquals(null, viewModel.stockCountCommittedBatchId.value)
    }
}
