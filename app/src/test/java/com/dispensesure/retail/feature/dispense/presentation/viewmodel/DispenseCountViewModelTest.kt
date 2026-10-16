package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.domain.data.PillScanningEvent
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.scanning.engine.TestEngineFactory
import com.dispensesure.retail.core.scanning.engine.frameForTest
import com.dispensesure.retail.core.scanning.engine.lastAddSignatureForTest
import com.dispensesure.retail.core.scanning.engine.putFrameForTest
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.HelperFunctions
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.common.OverlayUtils
import com.dispensesure.retail.core.utils.common.PhotoInfo
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Unit tests for [DispenseCountViewModel] covering areas not exercised by the sibling
 * Action/Frame/NdcScan/State test files: [DispenseCountViewModel.buildWorkflowSteps] (all
 * branches, pure logic), [DispenseCountViewModel.moveNextStep] transitions, and the
 * dispense-flow bottle rescan handlers (onNdcRescannedDuringCount / ConfirmAddBottle /
 * ConfirmReplaceBottle / CancelAddBottle / CancelReplaceBottle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispenseCountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val dispenseTxnDao: DispenseTxnDao = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val operatorNameProvider: OperatorNameProvider = mockk(relaxed = true)
    private val dispenseCountDao: DispenseCountDao = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val modelLoader: PillDetectionModelLoader = mockk(relaxed = true)
    private val performanceLogger: PerformanceLogger = mockk(relaxed = true)
    private val barcodeDecoder: BarcodeDecoder = mockk(relaxed = true)
    private val hl7Repository: Hl7Repository = mockk(relaxed = true)
    private val appDatabase: AppDatabase = mockk(relaxed = true)

    private lateinit var engineFactory: TestEngineFactory
    private lateinit var viewModel: DispenseCountViewModel

    // True while a withTransaction block runs, so tests can check a write happened inside one.
    @Volatile private var inTransaction = false

    @Before
    fun setup() {
        // Run withTransaction blocks inline; a relaxed AppDatabase never runs them.
        mockkStatic("androidx.room.RoomDatabaseKt")
        val txBlock = slot<suspend () -> Any?>()
        coEvery { appDatabase.withTransaction(capture(txBlock)) } coAnswers {
            inTransaction = true
            try { txBlock.captured.invoke() } finally { inTransaction = false }
        }
        every { application.applicationContext } returns application
        every { application.getString(any<Int>()) } returns "test error"
        every { application.getString(any<Int>(), any()) } returns "test error"
        every { dispenseCountDao.observeAllForTxn(any(), any()) } returns flowOf(emptyList())
        coEvery { dispenseCountDao.getLatestStep(any()) } returns null
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        every { preferenceHelper.getControlDrugTypes() } returns emptySet()
        every { preferenceHelper.isRequireDoubleCountEnabled() } returns false
        every { preferenceHelper.isRequireBackCountEnabled() } returns false

        engineFactory = TestEngineFactory(
            context = application,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            locationProvider = locationProvider,
            userDao = userDao,
            preferenceHelper = preferenceHelper,
        )
        viewModel = DispenseCountViewModel(
            app = application,
            preferenceHelper = preferenceHelper,
            dispenseTxnDao = dispenseTxnDao,
            operatorNameProvider = operatorNameProvider,
            dispenseCountDao = dispenseCountDao,
            drugMasterDao = drugMasterDao,
            scanDecoder = ScanDecoder(barcodeDecoder),
            hl7Repository = hl7Repository,
            appDatabase = appDatabase,
            engineFactory = engineFactory,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // ─────────────────────────── buildWorkflowSteps ───────────────────────────

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
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { dispenseTxnDao.getDispenseTxnDetail(7L) } returns DispenseTxnDetail(
            txnId = 7L,
            drugName = "Drug",
            drugId = 42L,
            ndc = "NDC1",
            targetCount = 30,
            note = null,
            createdAt = 0L,
            bottlesJson = null,
            totalPillCount = 0,
            drugType = "REGULAR",
            counts = emptyList(),
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
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { dispenseTxnDao.getDispenseTxnDetail(7L) } returns null

        viewModel.enterScanStep()

        assertEquals(StepState.SCAN, viewModel.currentStep.value)
    }

    // ─────────────────────────── resetTransaction ───────────────────────────

    @Test
    fun `resetTransaction hard-deletes the details and clears the txn fields`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { dispenseCountDao.getImagePathsForTxn(7L) } returns emptyList()

        viewModel.resetTransaction()

        coVerify(exactly = 1) { dispenseCountDao.deleteAllForTxn(7L) }
        coVerify(exactly = 1) { dispenseTxnDao.resetForRecount(7L, any()) }
    }

    @Test
    fun `resetTransaction deletes the captured image files`() = runTest {
        val image = File.createTempFile("reset", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        every { preferenceHelper.getTxnId() } returns 7L
        coEvery { dispenseCountDao.getImagePathsForTxn(7L) } returns listOf(image.absolutePath)

        viewModel.resetTransaction()

        assertFalse(image.exists())
    }

    // ─────────────────────────── observeResetAvailability ───────────────────────────

    @Test
    fun `reset is unavailable once the dispense txn has synced`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        every { dispenseTxnDao.observeById(7L) } returns flowOf(
            DispenseTxnEntity(
                txnId = 7L, status = CountStatus.PARTIAL, isSynced = true,
            )
        )

        viewModel.observeResetAvailability()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canReset)
    }

    @Test
    fun `reset is available while the dispense txn is unsynced`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        every { dispenseTxnDao.observeById(7L) } returns flowOf(
            DispenseTxnEntity(
                txnId = 7L, status = CountStatus.PARTIAL, isSynced = false,
            )
        )

        viewModel.observeResetAvailability()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canReset)
    }

    @Test
    fun `reset is available when there is no txn`() = runTest {
        every { preferenceHelper.getTxnId() } returns 0L

        viewModel.observeResetAvailability()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canReset)
    }

    @Test
    fun `reset goes unavailable when the txn syncs while the screen is up`() = runTest {
        every { preferenceHelper.getTxnId() } returns 7L
        val txn = MutableStateFlow(
            DispenseTxnEntity(
                txnId = 7L, status = CountStatus.PARTIAL, isSynced = false,
            )
        )
        every { dispenseTxnDao.observeById(7L) } returns txn

        viewModel.observeResetAvailability()
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
        val field = DispenseCountViewModel::class.java.getDeclaredField("_steps")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<StepState>>
        flow.value = steps
    }

    private fun setCurrentStep(step: StepState) {
        val field = DispenseCountViewModel::class.java.getDeclaredField("_currentStep")
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
        coEvery { dispenseCountDao.getTotalPillCountForTxn(any()) } returns 0

        viewModel.moveNextStep()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNoTransaction)
    }

    @Test
    fun `moveNextStep with empty steps list falls through to handleDone`() = runTest {
        setSteps(emptyList())
        coEvery { dispenseCountDao.getTotalPillCountForTxn(any()) } returns 0

        viewModel.moveNextStep()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNoTransaction)
    }

    // ─────────────────────────── handleDone → notes dialog ───────────────────────────

    @Test
    fun `DoneClicked shows the notes dialog for an HL7 txn when the notes setting is on`() = runTest {
        // HL7 txns used to skip the notes prompt; the setting is now the only gate.
        seedLoadedTxnInfo(isComingFromHL7 = true)
        setCurrentStep(StepState.VIAL)
        every { preferenceHelper.getShowNotesDialogSetting() } returns true
        coEvery { dispenseCountDao.getTotalPillCountForTxn(any()) } returns 30

        viewModel.onAction(DispenseCountAction.DoneClicked)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNotesDialog)
        // getById is only reached by handleConfirmDone, so the notes prompt still gates completion.
        coVerify(exactly = 0) { dispenseTxnDao.getById(any()) }
    }

    @Test
    fun `DoneClicked skips the notes dialog for an HL7 txn when the notes setting is off`() = runTest {
        seedLoadedTxnInfo(isComingFromHL7 = true)
        setCurrentStep(StepState.VIAL)
        every { preferenceHelper.getShowNotesDialogSetting() } returns false
        coEvery { dispenseCountDao.getTotalPillCountForTxn(any()) } returns 30

        viewModel.onAction(DispenseCountAction.DoneClicked)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNotesDialog)
        // No confirm dialog any more: Done completes the txn straight through.
        coVerify(exactly = 1) { dispenseTxnDao.getById(any()) }
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
        viewModel.onAction(DispenseCountAction.ConfirmAddBottle)
        advanceUntilIdle()

        coVerify(exactly = 0) { dispenseTxnDao.updateBottles(any(), any()) }
    }

    @Test
    fun `handleConfirmReplaceBottle is a no-op when there is no pending bottle scan`() = runTest {
        viewModel.onAction(DispenseCountAction.ConfirmReplaceBottle)
        advanceUntilIdle()

        coVerify(exactly = 0) { dispenseTxnDao.updateBottles(any(), any()) }
    }

    @Test
    fun `CancelAddBottle dismisses the add-bottle dialog`() = runTest {
        viewModel.onAction(DispenseCountAction.CancelAddBottle)

        assertFalse(viewModel.uiState.value.showAddBottleDialog)
    }

    @Test
    fun `CancelReplaceBottle dismisses the replace-bottle dialog`() = runTest {
        viewModel.onAction(DispenseCountAction.CancelReplaceBottle)

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
    }

    // ─────────────────────────── handleAddTransaction: frame at the tap ───────────────────────────

    // Dispense always caps at the target. 100 keeps the cap out of the way; the
    // over-target tests set their own first.
    private fun tapAdd(stepType: StepState = StepState.TARGET_VERIFICATION, count: Int = 5) {
        if (viewModel.uiState.value.targetCount == 0) viewModel.updateTargetCount(100)
        viewModel.onAction(
            DispenseCountAction.AddTransactionDetailClicked(
                filteredCount = count,
                stepType = stepType,
            )
        )
    }

    // drugImagePlaceholder() is outside any try; with stubbed Android its toBitmap() hits a
    // null Bitmap and would kill the save before the insert. No drawable = no placeholder.
    private fun stubSavePath() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.getDrawable(any(), any()) } returns null
    }

    @Test
    fun `Add with no camera frame shows a toast and records nothing`() = runTest {
        mockkObject(UserInterfaceUtils)
        every { UserInterfaceUtils.showToast(any<String>(), any()) } returns Unit

        tapAdd()
        advanceUntilIdle()

        verify(exactly = 1) { UserInterfaceUtils.showToast(any<String>(), any()) }
        assertFalse(engineFactory.engine.state.value.isAddCooldown)
        // Signature unset, so the retry isn't rejected as a duplicate.
        assertNull(engineFactory.engine.lastAddSignatureForTest())
        coVerify(exactly = 0) { dispenseCountDao.insert(any()) }
    }

    @Test
    fun `Add takes the frame at the tap and a newer frame is left alone`() = runTest {
        stubSavePath()
        val tapFrame = mockk<Bitmap>(relaxed = true)
        engineFactory.engine.putFrameForTest(tapFrame)

        tapAdd()
        // Taken synchronously on the tap, before the save coroutine runs.
        assertNull(engineFactory.engine.frameForTest())

        val newerFrame = mockk<Bitmap>(relaxed = true)
        engineFactory.engine.putFrameForTest(newerFrame)

        coVerify(timeout = 3000) { dispenseCountDao.insert(any()) }
        verify { tapFrame.recycle() }
        verify(exactly = 0) { newerFrame.recycle() }
        assertEquals(newerFrame, engineFactory.engine.frameForTest())
    }

    @Test
    fun `Add does not wait for a location lookup`() = runTest {
        stubSavePath()
        coEvery { locationProvider.getCurrentLocationAsString() } coAnswers { awaitCancellation() }
        engineFactory.engine.putFrameForTest(mockk<Bitmap>(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) { dispenseCountDao.insert(any()) }
    }

    @Test
    fun `attachCameraHelper starts a location lookup`() = runTest {
        viewModel.attachCameraHelper(mockk(relaxed = true))

        coVerify(timeout = 3000) { locationProvider.getCurrentLocationAsString() }
    }

    private fun stubTxn(isSubstitute: Boolean) {
        every { preferenceHelper.getTxnId() } returns 9L
        coEvery { dispenseTxnDao.getById(9L) } returns DispenseTxnEntity(
            txnId = 9L, drugId = 1L, status = CountStatus.PARTIAL,
            isSubstitute = isSubstitute, substitutedDrugId = 2L,
        )
    }

    @Test
    fun `Add ignores substitutedDrugId when isSubstitute is false`() = runTest {
        stubSavePath()
        stubTxn(isSubstitute = false)
        engineFactory.engine.putFrameForTest(mockk<Bitmap>(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) { dispenseCountDao.insert(any()) }
        coVerify(exactly = 0) { drugMasterDao.getDrugById(2L) }
    }

    @Test
    fun `Add uses the substitute drug when isSubstitute is true`() = runTest {
        stubSavePath()
        stubTxn(isSubstitute = true)
        engineFactory.engine.putFrameForTest(mockk<Bitmap>(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) { dispenseCountDao.insert(any()) }
        coVerify { drugMasterDao.getDrugById(2L) }
    }

    @Test
    fun `Add inserts the count and links it to the bottle in one transaction`() = runTest {
        stubSavePath()
        every { preferenceHelper.getTxnId() } returns 9L
        coEvery { dispenseTxnDao.getById(9L) } returns DispenseTxnEntity(
            txnId = 9L, drugId = 1L, status = CountStatus.PARTIAL,
            bottlesJson = DispenseBottleJson.encode(listOf(DispenseBottle(txnId = 9L, countIds = listOf(5L)))),
        )
        var insertInTx = false
        var linkInTx = false
        coEvery { dispenseCountDao.insert(any()) } coAnswers { insertInTx = inTransaction; 6L }
        coEvery { dispenseTxnDao.updateBottles(9L, any(), any()) } coAnswers { linkInTx = inTransaction }
        engineFactory.engine.putFrameForTest(mockk<Bitmap>(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) { dispenseTxnDao.updateBottles(9L, any(), any()) }
        assertTrue(insertInTx)
        assertTrue(linkInTx)
        coVerify(exactly = 1) { appDatabase.withTransaction<Any?>(any()) }
    }

    /** File writes return "/photos/<name>" and the strip drawing is captured. */
    private fun stubPhotoWrites(): io.mockk.CapturingSlot<PhotoInfo> {
        stubSavePath()
        mockkObject(HelperFunctions)
        every { HelperFunctions.saveBitmapToFile(any(), any(), any(), any(), any()) } answers {
            val fileName = thirdArg<String>()
            "/photos/$fileName"
        }
        mockkObject(OverlayUtils)
        val stampedPhotoInfo = slot<PhotoInfo>()
        every {
            OverlayUtils.drawDetectionsOnBitmap(any(), any(), capture(stampedPhotoInfo), any(), any(), any())
        } answers { firstArg() }
        return stampedPhotoInfo
    }

    @Test
    fun `Add over the target is turned down before the engine takes the frame`() = runTest {
        viewModel.updateTargetCount(3)
        val frame = mockk<Bitmap>(relaxed = true)
        engineFactory.engine.putFrameForTest(frame)

        tapAdd(count = 5)

        assertTrue(viewModel.uiState.value.restrictAdd)
        assertSame(frame, engineFactory.engine.frameForTest())
        assertFalse(engineFactory.engine.state.value.isAddCooldown)
    }

    @Test
    fun `Add over the target during the cooldown shows the over-target dialog`() = runTest {
        // Approved order change: the target cap now runs before the cooldown check.
        stubSavePath()
        mockkObject(UserInterfaceUtils)
        every { UserInterfaceUtils.showToast(any<String>(), any()) } returns Unit
        viewModel.updateTargetCount(6)
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))
        tapAdd(count = 5)                       // 5 <= 6: accepted, cooldown starts
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd(count = 7)                       // 7 > 6, inside the cooldown

        assertTrue(viewModel.uiState.value.restrictAdd)
        verify(exactly = 0) { UserInterfaceUtils.showToast(any<String>(), any()) }
    }

    @Test
    fun `Add on TARGET_VERIFICATION saves the raw crop with the count`() = runTest {
        stubPhotoWrites()
        every { preferenceHelper.getTxnId() } returns 9L
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd(stepType = StepState.TARGET_VERIFICATION)

        coVerify(timeout = 3000) {
            dispenseCountDao.insert(match { countRow ->
                countRow.txnId == 9L && countRow.pillCount == 5 &&
                    countRow.step == StepState.TARGET_VERIFICATION.toString() &&
                    countRow.imagePath?.startsWith("/photos/txn_detail_") == true &&
                    countRow.rawImagePath?.endsWith("_raw.jpg") == true
            })
        }
    }

    @Test
    fun `Add on another step saves no raw crop`() = runTest {
        stubPhotoWrites()
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd(stepType = StepState.CONTAINER_INITIATE)

        coVerify(timeout = 3000) {
            dispenseCountDao.insert(match { countRow -> countRow.imagePath != null && countRow.rawImagePath == null })
        }
    }

    @Test
    fun `Add stamps the active bottle and the Rx on the photo`() = runTest {
        val stampedPhotoInfo = stubPhotoWrites()
        every { preferenceHelper.getTxnId() } returns 9L
        coEvery { dispenseTxnDao.getById(9L) } returns DispenseTxnEntity(
            txnId = 9L, drugId = 1L, status = CountStatus.PARTIAL,
            rxNo = "123", refillNo = "2",
            bottlesJson = DispenseBottleJson.encode(listOf(
                DispenseBottle(txnId = 9L, lotNumber = "OLD"),
                DispenseBottle(txnId = 9L, lotNumber = "L1", serialNumber = "S1", expirationDate = "04-30-2028"),
            )),
        )
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd(stepType = StepState.TARGET_VERIFICATION, count = 7)

        coVerify(timeout = 3000) { dispenseCountDao.insert(any()) }
        with(stampedPhotoInfo.captured) {
            assertEquals("L1", lotNumber)
            assertEquals("S1", serialNumber)
            assertEquals("04-30-2028", expirationDate)
            assertEquals(OverlayUtils.rxRefill("123", "2"), rxRefill)
            assertEquals("TARGET_VERIFICATION", stepLabel)
            assertEquals(7, count)
            assertFalse(isStockCount)
        }
    }

    private fun getPrivateField(name: String): Any? {
        val field = DispenseCountViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(viewModel)
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedLoadedTxnInfo(isComingFromHL7: Boolean = false) {
        val txnInfoFlow = getPrivateField("_txnInfo") as MutableStateFlow<DispenseTxnDetail?>
        txnInfoFlow.value = DispenseTxnDetail(
            txnId = 0L,
            drugName = null,
            drugId = 42L,
            ndc = null,
            targetCount = null,
            note = null,
            createdAt = 0L,
            bottlesJson = null,
            totalPillCount = 0,
            drugType = null,
            counts = emptyList(),
            isComingFromHL7 = isComingFromHL7,
        )
    }
}
