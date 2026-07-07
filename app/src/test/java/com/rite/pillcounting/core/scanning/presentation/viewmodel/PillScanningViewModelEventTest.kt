package com.rite.pillcounting.core.scanning.presentation.viewmodel

import android.app.Application
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.dtos.TxnWithDetails
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.data.PillScanningEvent
import com.rite.pillcounting.core.scanning.domain.model.DetectedPill
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [PillScanningViewModel] event handling: Rescan, CancelDone, delete
 * transaction details, note save/skip, FinalDone routing, and ancillary public API
 * (resetWorkflowSteps, updateFilteredPills, pausePillDetection, isOnNdcScanStep).
 *
 * handleConfirmDialog is private; it is exercised via onEvent(PillScanningEvent.FinalDone).
 * _txnInfo is private; it is populated via showTxnInfo(), which calls getTxnWithDetails().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillScanningViewModelEventTest {

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

    private lateinit var viewModel: PillScanningViewModel

    @Before
    fun setup() {
        every { application.applicationContext } returns application
        every { application.getString(any<Int>()) } returns "test error"
        every { application.getString(any<Int>(), any()) } returns "test error"
        every { pillCountTxnDetailsDao.observeAllForTxn(any(), any()) } returns flowOf(emptyList())
        // getLatestType returns StepState? — relaxed mock yields a child mock instead of null;
        // stub explicitly so getDrugInfo resolves to CONTAINER_INITIATE as expected.
        coEvery { pillCountTxnDetailsDao.getLatestType(any()) } returns null
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        viewModel = PillScanningViewModel(
            app = application,
            preferenceHelper = preferenceHelper,
            pillCountTxnDao = pillCountTxnDao,
            stockTxnDao = stockTxnDao,
            bottleInfoDao = bottleInfoDao,
            userDao = userDao,
            pillCountTxnDetailsDao = pillCountTxnDetailsDao,
            locationProvider = locationProvider,
            drugMasterDao = drugMasterDao,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            barcodeDecoder = barcodeDecoder,
            drugRepository = drugRepository,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // ─────────────────────────── RescanClicked ───────────────────────────

    // SCAN_VM_026
    @Test
    fun `RescanClicked clears detectedPills in uiState`() = runTest {
        viewModel.onEvent(PillScanningEvent.RescanClicked)

        assertTrue(viewModel.uiState.value.detectedPills.isEmpty())
    }

    // ─────────────────────────── CancelDone ───────────────────────────

    // SCAN_VM_027
    @Test
    fun `CancelDone clears capturedBitmap and sets showConfirmDialog to false`() = runTest {
        viewModel.onEvent(PillScanningEvent.CancelDone)

        assertNull(viewModel.capturedBitmap.value)
        assertFalse(viewModel.uiState.value.showConfirmDialog)
    }

    // ─────────────────────────── resetWorkflowSteps ───────────────────────────

    // SCAN_VM_028
    @Test
    fun `resetWorkflowSteps clears steps list and resets currentStep to TARGET_VERIFICATION`() = runTest {
        viewModel.resetWorkflowSteps()

        assertTrue(viewModel.steps.value.isEmpty())
        assert(viewModel.currentStep.value == StepState.TARGET_VERIFICATION)
    }

    // ─────────────────────────── updateFilteredPills ───────────────────────────

    // SCAN_VM_029
    @Test
    fun `updateFilteredPills with a new list updates filteredPills in uiState`() = runTest {
        val newPills = listOf(DetectedPill(x = 10f, y = 20f, confidence = 0.9f))

        viewModel.updateFilteredPills(newPills)

        assert(viewModel.uiState.value.filteredPills == newPills)
    }

    // SCAN_VM_030
    @Test
    fun `updateFilteredPills with the same list reference is a no-op`() = runTest {
        val pills = listOf(DetectedPill(x = 10f, y = 20f, confidence = 0.9f))
        viewModel.updateFilteredPills(pills) // first call — updates state

        val stateBeforeSecondCall = viewModel.uiState.value

        viewModel.updateFilteredPills(pills) // same reference → dedupe guard returns early

        assert(viewModel.uiState.value === stateBeforeSecondCall)
    }

    // ─────────────────────────── TransactionDetailDeleted ───────────────────────────

    // SCAN_VM_031
    @Test
    fun `TransactionDetailDeleted calls softDelete with the provided txnDetailId`() = runTest {
        viewModel.onEvent(PillScanningEvent.TransactionDetailDeleted(txnDetailId = 5L))
        advanceUntilIdle()

        coVerify(exactly = 1) { pillCountTxnDetailsDao.softDelete(eq(5L), any()) }
    }

    // ─────────────────────────── AllTransactionDetailsDeleted ───────────────────────────

    // SCAN_VM_032
    @Test
    fun `AllTransactionDetailsDeleted calls softDeleteAllTransaction for the given stepType`() = runTest {
        viewModel.onEvent(
            PillScanningEvent.AllTransactionDetailsDeleted(stepType = StepState.CONTAINER_INITIATE)
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            pillCountTxnDetailsDao.softDeleteAllTransaction(any(), any(), eq(StepState.CONTAINER_INITIATE))
        }
    }

    // ─────────────────────────── NoteSaved ───────────────────────────

    // SCAN_VM_033
    @Test
    fun `NoteSaved hides the notes dialog and persists the note via DAO`() = runTest {
        viewModel.setNoteDialogShown(true) // set precondition

        viewModel.onEvent(PillScanningEvent.NoteSaved(note = "test note"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNotesDialog)
        coVerify(exactly = 1) { pillCountTxnDao.updateNote(any(), eq("test note"), any()) }
    }

    // ─────────────────────────── NoteSkip ───────────────────────────

    // SCAN_VM_034
    @Test
    fun `NoteSkip hides the notes dialog`() = runTest {
        viewModel.setNoteDialogShown(true) // set precondition

        viewModel.onEvent(PillScanningEvent.NoteSkip)

        assertFalse(viewModel.uiState.value.showNotesDialog)
    }

    // ─────────────────────────── FinalDone routing via handleConfirmDialog ───────────────────────────

    // SCAN_VM_035
    @Test
    fun `FinalDone with REGULAR countType sets showEndStockCountDialog to true`() = runTest {
        val txnInfo = TxnWithDetails(
            txnId = 1L, drugName = null, drugId = 1L, ndc = null,
            targetCount = 10, note = null,
            createdAt = 0L, barcodeImage = null, totalPillCount = 0,
            countType = CountType.REGULAR, drugType = null,
            txnDetails = emptyList(), isComingFromHL7 = false,
        )
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns txnInfo
        viewModel.showTxnInfo(CountType.REGULAR.toString())
        advanceUntilIdle()

        viewModel.onEvent(PillScanningEvent.FinalDone(StepState.CONTAINER_INITIATE, totalCount = 5))

        assertTrue(viewModel.uiState.value.showEndStockCountDialog)
    }

    // SCAN_VM_036
    @Test
    fun `FinalDone with CONTAINER_INITIATE and totalCount below target sets showErrorMessage`() = runTest {
        val txnInfo = TxnWithDetails(
            txnId = 1L, drugName = null, drugId = 1L, ndc = null,
            targetCount = 10, note = null,
            createdAt = 0L, barcodeImage = null, totalPillCount = 0,
            countType = CountType.FIXED, drugType = null,
            txnDetails = emptyList(), isComingFromHL7 = false,
        )
        coEvery { pillCountTxnDao.getTxnWithDetails(any()) } returns txnInfo
        viewModel.showTxnInfo(CountType.FIXED.toString())
        advanceUntilIdle()

        // target=10, totalCount=5 → 10 <= 5 is false → sets showErrorMessage
        viewModel.onEvent(PillScanningEvent.FinalDone(StepState.CONTAINER_INITIATE, totalCount = 5))

        assertNotNull(viewModel.uiState.value.showErrorMessage)
    }

    // ─────────────────────────── isOnNdcScanStep property ───────────────────────────

    // SCAN_VM_037
    @Test
    fun `isOnNdcScanStep is true after enterStockCountScanMode and getDrugInfo resolve to SCAN`() = runTest {
        viewModel.enterStockCountScanMode(batchId = 1L)
        viewModel.getDrugInfo()
        advanceUntilIdle()

        assertTrue(viewModel.isOnNdcScanStep)
    }

    // ─────────────────────────── pausePillDetection side-effects ───────────────────────────

    // SCAN_VM_038
    @Test
    fun `pausePillDetection clears both detectedPills and trayDetections`() = runTest {
        viewModel.pausePillDetection()

        assertTrue(viewModel.uiState.value.detectedPills.isEmpty())
        assertTrue(viewModel.trayDetections.value.isEmpty())
    }
}
