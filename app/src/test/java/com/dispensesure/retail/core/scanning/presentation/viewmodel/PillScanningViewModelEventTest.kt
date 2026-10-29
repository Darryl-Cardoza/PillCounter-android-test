package com.dispensesure.retail.core.scanning.presentation.viewmodel

import android.app.Application
import androidx.room.withTransaction
import com.dispensesure.retail.core.faceAuth.data.OperatorName
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.domain.data.PillScanningEvent
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.scanning.engine.TestEngineFactory
import com.dispensesure.retail.core.scanning.engine.feedGloveFrameForTest
import com.dispensesure.retail.core.scanning.engine.feedTrayFrameForTest
import com.dispensesure.retail.core.scanning.engine.putFrameForTest
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [PillScanningViewModel] event handling: Rescan, delete
 * transaction details, note save/skip, FinalDone routing, and ancillary public API
 * (resetWorkflowSteps, updateFilteredPills, pausePillDetection).
 *
 * handleConfirmDialog is private; it is exercised via onEvent(PillScanningEvent.FinalDone).
 * _txnInfo is private; it is populated via showTxnInfo(), which calls getDispenseTxnDetail().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillScanningViewModelEventTest {

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
    private lateinit var viewModel: PillScanningViewModel

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
        // getLatestStep returns StepState? — relaxed mock yields a child mock instead of null;
        // stub explicitly so getDrugInfo resolves to CONTAINER_INITIATE as expected.
        coEvery { dispenseCountDao.getLatestStep(any()) } returns null
        coJustRun { performanceLogger.logPerformanceSnapshot(any()) }
        engineFactory = TestEngineFactory(
            context = application,
            modelLoader = modelLoader,
            performanceLogger = performanceLogger,
            locationProvider = locationProvider,
            userDao = userDao,
            preferenceHelper = preferenceHelper,
        )
        viewModel = PillScanningViewModel(
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

    // ─────────────────────────── RescanClicked ───────────────────────────

    // SCAN_VM_026
    @Test
    fun `RescanClicked clears detectedPills in uiState`() = runTest {
        viewModel.onEvent(PillScanningEvent.RescanClicked)

        assertTrue(viewModel.uiState.value.detectedPills.isEmpty())
    }

    // ─────────────────────────── resetWorkflowSteps ───────────────────────────

    // SCAN_VM_028
    @Test
    fun `resetWorkflowSteps clears steps list and resets currentStep to TARGET_VERIFICATION`() = runTest {
        viewModel.resetWorkflowSteps()

        assertTrue(viewModel.steps.value.isEmpty())
        assert(viewModel.currentStep.value == StepState.TARGET_VERIFICATION)
    }

    // ─────────────────────────── engine state and events ───────────────────────────

    @Test
    fun `resetTransaction returns false when the DB reset fails`() = runTest {
        coEvery { preferenceHelper.getTxnId() } returns 7L
        coEvery { dispenseTxnDao.resetForRecount(7L, any()) } throws IllegalStateException("boom")

        assertFalse(viewModel.resetTransaction())
    }

    @Test
    fun `resetTransaction returns true when the reset succeeds`() = runTest {
        coEvery { preferenceHelper.getTxnId() } returns 7L

        assertTrue(viewModel.resetTransaction())
    }

    // SCAN_VM_029 (view model side)
    @Test
    fun `updateFilteredPills reaches uiState through the engine`() = runTest {
        val newPills = listOf(DetectedPill(x = 10f, y = 20f, confidence = 0.9f))

        viewModel.updateFilteredPills(newPills)
        runCurrent()

        assertEquals(newPills, viewModel.uiState.value.filteredPills)
    }

    @Test
    fun `addPopEvents re-emits the engine's +N pop`() = runTest {
        val addPopCounts = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.addPopEvents.toList(addPopCounts) }
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        engineFactory.engine.captureCount(5)

        assertEquals(listOf(5), addPopCounts)
    }

    @Test
    fun `gloves confirmed by the engine are saved on the dispense txn`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        runCurrent() // starts the view model's event collector

        repeat(3) { engineFactory.engine.feedGloveFrameForTest(confidence = 0.9f) }
        advanceUntilIdle()

        coVerify { dispenseTxnDao.updateGlovesPresent(42L, true, any()) }
        assertTrue(viewModel.glovesDetected.value)
    }

    @Test
    fun `a tray classification is saved on the dispense txn`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        runCurrent()

        viewModel.classifyTrayColor(TrayColor.RED, isHazardous = true)
        advanceUntilIdle()

        // The save runs on Dispatchers.IO, as before.
        coVerify(timeout = 3000) { dispenseTxnDao.updateHazardousTrayDetected(42L, true, any()) }
        assertEquals(null, viewModel.uiState.value.pendingTrayColorForClassification)
    }

    @Test
    fun `a tray warning from the engine is shown as a toast`() = runTest {
        mockkObject(UserInterfaceUtils)
        every { UserInterfaceUtils.showToast(any<String>(), any()) } returns Unit
        every { preferenceHelper.getHazardousTrayColor() } returns "RED"
        runCurrent() // starts the view model's event collector
        viewModel.setHazardousTransaction(isHazardousByDrug = false)

        engineFactory.engine.feedTrayFrameForTest(TrayColor.RED)
        runCurrent()

        // application.getString returns "test error" for every id in this class.
        verify(exactly = 1) { UserInterfaceUtils.showToast("test error", any()) }
    }

    // ─────────────────────────── TransactionDetailDeleted ───────────────────────────

    // SCAN_VM_031
    @Test
    fun `TransactionDetailDeleted hard-deletes the files then the row`() = runTest {
        coEvery { dispenseCountDao.getImagePathsForCount(5L) } returns emptyList()

        viewModel.onEvent(PillScanningEvent.TransactionDetailDeleted(txnDetailId = 5L))

        // Delete runs on real Dispatchers.IO; wait for the last call, then check order.
        coVerify(timeout = 3000) { dispenseCountDao.hardDelete(5L) }
        coVerifyOrder {
            dispenseCountDao.getImagePathsForCount(5L)
            dispenseCountDao.hardDelete(5L)
        }
    }

    // ─────────────────────────── AllTransactionDetailsDeleted ───────────────────────────

    // SCAN_VM_032
    @Test
    fun `AllTransactionDetailsDeleted hard-deletes the step's files then rows`() = runTest {
        coEvery { dispenseCountDao.getImagePathsForStep(any(), any()) } returns emptyList()

        viewModel.onEvent(
            PillScanningEvent.AllTransactionDetailsDeleted(stepType = StepState.CONTAINER_INITIATE)
        )

        coVerify(timeout = 3000) {
            dispenseCountDao.hardDeleteAllForStep(any(), eq(StepState.CONTAINER_INITIATE))
        }
        coVerifyOrder {
            dispenseCountDao.getImagePathsForStep(any(), eq(StepState.CONTAINER_INITIATE))
            dispenseCountDao.hardDeleteAllForStep(any(), eq(StepState.CONTAINER_INITIATE))
        }
    }

    // ─────────────────────────── Delete: bottle links ───────────────────────────

    private fun txnWithBottleIds(vararg ids: Long) = DispenseTxnEntity(
        txnId = 9L, status = CountStatus.PARTIAL,
        bottlesJson = DispenseBottleJson.encode(listOf(DispenseBottle(txnId = 9L, countIds = ids.toList()))),
    )

    private fun bottleIdsWritten(json: String) = DispenseBottleJson.decode(json).single().countIds

    @Test
    fun `TransactionDetailDeleted removes the id from its bottle`() = runTest {
        every { preferenceHelper.getTxnId() } returns 9L
        coEvery { dispenseCountDao.getImagePathsForCount(5L) } returns emptyList()
        coEvery { dispenseTxnDao.getById(9L) } returns txnWithBottleIds(5L, 6L)
        val json = slot<String>()

        viewModel.onEvent(PillScanningEvent.TransactionDetailDeleted(txnDetailId = 5L))

        coVerify(timeout = 3000) { dispenseTxnDao.updateBottles(9L, capture(json), any()) }
        assertEquals(listOf(6L), bottleIdsWritten(json.captured))
        coVerifyOrder {
            dispenseCountDao.hardDelete(5L)
            dispenseTxnDao.updateBottles(9L, any(), any())
        }
    }

    @Test
    fun `AllTransactionDetailsDeleted removes the step's ids from bottles`() = runTest {
        every { preferenceHelper.getTxnId() } returns 9L
        coEvery { dispenseCountDao.getImagePathsForStep(any(), any()) } returns emptyList()
        coEvery { dispenseCountDao.getIdsForStep(9L, StepState.TARGET_VERIFICATION) } returns listOf(5L, 6L)
        coEvery { dispenseTxnDao.getById(9L) } returns txnWithBottleIds(5L, 6L, 7L)
        val json = slot<String>()

        viewModel.onEvent(PillScanningEvent.AllTransactionDetailsDeleted(stepType = StepState.TARGET_VERIFICATION))

        coVerify(timeout = 3000) { dispenseTxnDao.updateBottles(9L, capture(json), any()) }
        assertEquals(listOf(7L), bottleIdsWritten(json.captured))
    }

    @Test
    fun `TransactionDetailDeleted leaves bottles alone when the id isn't linked`() = runTest {
        every { preferenceHelper.getTxnId() } returns 9L
        coEvery { dispenseCountDao.getImagePathsForCount(5L) } returns emptyList()
        coEvery { dispenseTxnDao.getById(9L) } returns txnWithBottleIds(6L)

        viewModel.onEvent(PillScanningEvent.TransactionDetailDeleted(txnDetailId = 5L))

        coVerify(timeout = 3000) { dispenseTxnDao.getById(9L) }
        coVerify(exactly = 0) { dispenseTxnDao.updateBottles(any(), any(), any()) }
    }

    @Test
    fun `TransactionDetailDeleted deletes the row and unlinks it in one transaction`() = runTest {
        every { preferenceHelper.getTxnId() } returns 9L
        coEvery { dispenseCountDao.getImagePathsForCount(5L) } returns emptyList()
        coEvery { dispenseTxnDao.getById(9L) } returns txnWithBottleIds(5L, 6L)
        var deleteInTx = false
        var unlinkInTx = false
        val unlinked = CountDownLatch(1)
        coEvery { dispenseCountDao.hardDelete(5L) } coAnswers { deleteInTx = inTransaction }
        coEvery { dispenseTxnDao.updateBottles(9L, any(), any()) } coAnswers {
            unlinkInTx = inTransaction
            unlinked.countDown()
        }

        viewModel.onEvent(PillScanningEvent.TransactionDetailDeleted(txnDetailId = 5L))

        // Await the answer itself: coVerify(timeout) returns once the call is recorded, before the flag is set.
        assertTrue(unlinked.await(3, TimeUnit.SECONDS))
        assertTrue(deleteInTx)
        assertTrue(unlinkInTx)
        coVerify(exactly = 1) { appDatabase.withTransaction<Any?>(any()) }
    }

    @Test
    fun `AllTransactionDetailsDeleted deletes the rows and unlinks them in one transaction`() = runTest {
        every { preferenceHelper.getTxnId() } returns 9L
        coEvery { dispenseCountDao.getImagePathsForStep(any(), any()) } returns emptyList()
        coEvery { dispenseCountDao.getIdsForStep(9L, StepState.TARGET_VERIFICATION) } returns listOf(5L, 6L)
        coEvery { dispenseTxnDao.getById(9L) } returns txnWithBottleIds(5L, 6L, 7L)
        var deleteInTx = false
        var unlinkInTx = false
        val unlinked = CountDownLatch(1)
        coEvery { dispenseCountDao.hardDeleteAllForStep(9L, StepState.TARGET_VERIFICATION) } coAnswers {
            deleteInTx = inTransaction
        }
        coEvery { dispenseTxnDao.updateBottles(9L, any(), any()) } coAnswers {
            unlinkInTx = inTransaction
            unlinked.countDown()
        }

        viewModel.onEvent(PillScanningEvent.AllTransactionDetailsDeleted(stepType = StepState.TARGET_VERIFICATION))

        // Await the answer itself: coVerify(timeout) returns once the call is recorded, before the flag is set.
        assertTrue(unlinked.await(3, TimeUnit.SECONDS))
        assertTrue(deleteInTx)
        assertTrue(unlinkInTx)
        coVerify(exactly = 1) { appDatabase.withTransaction<Any?>(any()) }
    }

    // ─────────────────────────── NoteSaved ───────────────────────────

    // SCAN_VM_033
    @Test
    fun `NoteSaved hides the notes dialog and persists the note via DAO`() = runTest {
        viewModel.setNoteDialogShown(true) // set precondition

        viewModel.onEvent(PillScanningEvent.NoteSaved(note = "test note"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNotesDialog)
        coVerify(exactly = 1) { dispenseTxnDao.updateNote(any(), eq("test note"), any()) }
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

    // SCAN_VM_036
    @Test
    fun `FinalDone with CONTAINER_INITIATE and totalCount below target sets showErrorMessage`() = runTest {
        val txnInfo = DispenseTxnDetail(
            txnId = 1L, drugName = null, drugId = 1L, ndc = null,
            targetCount = 10, note = null,
            createdAt = 0L, bottlesJson = null, totalPillCount = 0,
            drugType = null,
            counts = emptyList(), isComingFromHL7 = false,
        )
        coEvery { dispenseTxnDao.getDispenseTxnDetail(any()) } returns txnInfo
        viewModel.showTxnInfo()
        advanceUntilIdle()

        // target=10, totalCount=5 → 10 <= 5 is false → sets showErrorMessage
        viewModel.onEvent(PillScanningEvent.FinalDone(StepState.CONTAINER_INITIATE, totalCount = 5))

        assertNotNull(viewModel.uiState.value.showErrorMessage)
    }

    // SCAN_VM_049
    @Test
    fun `FinalDone with TARGET_VERIFICATION and count below target sets showErrorMessage`() = runTest {
        val txnInfo = DispenseTxnDetail(
            txnId = 1L, drugName = null, drugId = 1L, ndc = null,
            targetCount = 10, note = null,
            createdAt = 0L, bottlesJson = null, totalPillCount = 0,
            drugType = null,
            counts = emptyList(), isComingFromHL7 = false,
        )
        coEvery { dispenseTxnDao.getDispenseTxnDetail(any()) } returns txnInfo
        viewModel.showTxnInfo()
        advanceUntilIdle()

        // target=10, history sums to 0 → error, no step change
        viewModel.onEvent(PillScanningEvent.FinalDone(StepState.TARGET_VERIFICATION, totalCount = 0))

        assertNotNull(viewModel.uiState.value.showErrorMessage)
    }

    // ─────────────────────────── pausePillDetection side-effects ───────────────────────────

    // SCAN_VM_038
    @Test
    fun `pausePillDetection clears detectedPills`() = runTest {
        viewModel.pausePillDetection()

        assertTrue(viewModel.uiState.value.detectedPills.isEmpty())
    }

    // ─────────────────────────── operator stamp on completion ───────────────────────────

    @Test
    fun `completing a count stamps the operator name on the txn`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        coEvery { dispenseCountDao.getTotalPillCountForTxn(42L) } returns 5
        coEvery {
            dispenseCountDao.getPillCountForStep(42L, StepState.TARGET_VERIFICATION.name)
        } returns 5
        coEvery { dispenseTxnDao.getById(42L) } returns DispenseTxnEntity(
            txnId = 42L,
            status = CountStatus.PARTIAL,
            targetCount = 5,
        )
        coEvery { operatorNameProvider() } returns OperatorName("Bruce", "Wayne")

        viewModel.onEvent(PillScanningEvent.NoteSkip)
        advanceUntilIdle()

        coVerify { dispenseTxnDao.updateOperatorName(42L, "Bruce", "Wayne", any()) }
    }

    @Test
    fun `operator is stamped before the status write that wakes the resend sweep`() = runTest {
        // observePendingHl7Txn fires on status = COMPLETED. If the operator lands after
        // that write, the resend sweep can read the row first and send a blank operator.
        every { preferenceHelper.getTxnId() } returns 44L
        coEvery { dispenseCountDao.getTotalPillCountForTxn(44L) } returns 5
        coEvery {
            dispenseCountDao.getPillCountForStep(44L, StepState.TARGET_VERIFICATION.name)
        } returns 5
        coEvery { dispenseTxnDao.getById(44L) } returns DispenseTxnEntity(
            txnId = 44L,
            status = CountStatus.PARTIAL,
            targetCount = 5,
        )
        coEvery { operatorNameProvider() } returns OperatorName("Bruce", "Wayne")

        viewModel.onEvent(PillScanningEvent.NoteSkip)
        advanceUntilIdle()

        coVerifyOrder {
            dispenseTxnDao.updateOperatorName(44L, "Bruce", "Wayne", any())
            dispenseTxnDao.updateTxnStatus(44L, CountStatus.COMPLETED, any())
        }
    }

    @Test
    fun `a short dispense is stored PARTIAL even when other steps counted more pills`() = runTest {
        every { preferenceHelper.getTxnId() } returns 43L
        // All steps together clear the target; the prescribed step alone does not.
        coEvery { dispenseCountDao.getTotalPillCountForTxn(43L) } returns 35
        coEvery {
            dispenseCountDao.getPillCountForStep(43L, StepState.TARGET_VERIFICATION.name)
        } returns 15
        coEvery { dispenseTxnDao.getById(43L) } returns DispenseTxnEntity(
            txnId = 43L,
            status = CountStatus.PARTIAL,
            targetCount = 20,
        )

        viewModel.onEvent(PillScanningEvent.NoteSkip)
        advanceUntilIdle()

        coVerify { dispenseTxnDao.updateTxnStatus(43L, CountStatus.PARTIAL, any()) }
    }
}
