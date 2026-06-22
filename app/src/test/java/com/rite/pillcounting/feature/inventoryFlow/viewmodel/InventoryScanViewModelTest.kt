package com.rite.pillcounting.feature.inventoryFlow.viewmodel

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import com.rite.pillcounting.feature.inventoryFlow.domain.model.BatchStockCountUiState
import com.rite.pillcounting.feature.inventoryFlow.presentation.viewmodel.InventoryScanViewModel
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [InventoryScanViewModel].
 *
 * uiState and recentRows use SharingStarted.WhileSubscribed(5000L).
 * Flow-based assertions use Turbine test{} to hold a subscription and skipUntil to advance past
 * intermediate emissions. Simple StateFlow mutations (showEndCountDialog, batchEnded, errorMessage)
 * are checked directly via .value since they are plain MutableStateFlow fields.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val batchDao: BatchDao = mockk(relaxed = true)
    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val barcodeDecoder: BarcodeDecoder = mockk(relaxed = true)
    private val drugRepository: IDrugRepository = mockk(relaxed = true)
    private val hl7Repository: Hl7Repository = mockk(relaxed = true)
    private val hl7EventHandler: Hl7EventHandler = mockk(relaxed = true)

    private lateinit var viewModel: InventoryScanViewModel

    companion object {
        // 14-digit all-digit string — satisfies the gtin14 validation guard
        private const val TEST_NDC = "00000000000000"
        private const val BATCH_ID = 100L
    }

    @Before
    fun setup() {
        every { hl7EventHandler.connectionState } returns MutableStateFlow(false)
        every { pillCountTxnDao.observeByBatchId(any()) } returns flowOf(emptyList())
        viewModel = createViewModel()
    }

    @After
    fun tearDown() { unmockkAll() }

    private fun createViewModel(batchId: Long = BATCH_ID) = InventoryScanViewModel(
        savedStateHandle = SavedStateHandle(mapOf("batch_id" to batchId)),
        batchDao = batchDao,
        pillCountTxnDao = pillCountTxnDao,
        drugMasterDao = drugMasterDao,
        preferenceHelper = preferenceHelper,
        barcodeDecoder = barcodeDecoder,
        drugRepository = drugRepository,
        hl7Repository = hl7Repository,
        hl7EventHandler = hl7EventHandler,
    )

    /**
     * Sets up mocks for a successful barcode scan of [ndc]:
     * - isGs1Barcode → false (GTIN path via toGtin14)
     * - toGtin14 → ndc (identity, already 14 digits)
     * - getDrugByGtin → local DrugMasterEntity found (skips API)
     * - findSealedTxnInBatch → null (no existing sealed txn)
     * - getDrugIdByNdc → 1L (needed by persistActive to avoid error)
     */
    private fun setupScanMocks(ndc: String = TEST_NDC) {
        val drug = DrugMasterEntity(drugId = 1L, ndc = ndc, drugName = "TestDrug", packageQty = 10)
        every { barcodeDecoder.isGs1Barcode(ndc) } returns false
        every { barcodeDecoder.toGtin14(ndc) } returns ndc
        coEvery { drugMasterDao.getDrugByGtin(ndc) } returns drug
        coEvery { pillCountTxnDao.findSealedTxnInBatch(any(), any(), any(), any()) } returns null
        coEvery { drugMasterDao.getDrugIdByNdc(ndc) } returns 1L
    }

    /** Advances Turbine past emissions that do not satisfy [predicate], returns the matching item. */
    private suspend fun <T> ReceiveTurbine<T>.skipUntil(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) { item = awaitItem() }
        return item
    }

    // ─────────────────────────── Dialog / error state ───────────────────────────

    // INV_VM_001
    @Test
    fun `requestEndCount sets showEndCountDialog to true`() = runTest {
        advanceUntilIdle()

        viewModel.requestEndCount()

        assertTrue(viewModel.showEndCountDialog.value)
    }

    // INV_VM_002
    @Test
    fun `dismissEndCount sets showEndCountDialog to false`() = runTest {
        advanceUntilIdle()
        viewModel.requestEndCount()

        viewModel.dismissEndCount()

        assertFalse(viewModel.showEndCountDialog.value)
    }

    // INV_VM_003
    @Test
    fun `clearErrorMessage clears error set by an invalid scan`() = runTest {
        // Relying on relaxed-mock defaults: toGtin14 returns null → rawDigits="" →
        // length 0 not in 10..14 → scanKey=null → errorMessage set
        advanceUntilIdle()
        viewModel.onBarcodeDetected("XYZ")
        advanceUntilIdle()
        assertNotNull("error should be set after invalid scan", viewModel.errorMessage.value)

        viewModel.clearErrorMessage()

        assertNull(viewModel.errorMessage.value)
    }

    // ─────────────────────────── Scan → activeNdc ───────────────────────────

    // INV_VM_004
    @Test
    fun `onBarcodeDetected with known local drug sets activeNdc in uiState`() = runTest {
        setupScanMocks()

        viewModel.uiState.test {
            awaitItem() // initial BatchStockCountUiState with activeNdc=null
            advanceUntilIdle() // run init coroutine + upstream recentRows

            viewModel.onBarcodeDetected(TEST_NDC)
            advanceUntilIdle()

            val state: BatchStockCountUiState = skipUntil { it.activeNdc != null }
            assertEquals(TEST_NDC, state.activeNdc?.ndc)
            assertEquals(1, state.activeNdc?.bottles)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────── increment / decrement ───────────────────────────

    // INV_VM_005
    @Test
    fun `increment increases bottles count by 1`() = runTest {
        setupScanMocks()

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            viewModel.onBarcodeDetected(TEST_NDC)
            advanceUntilIdle()
            skipUntil { it.activeNdc != null } // bottles=1

            viewModel.increment()
            advanceUntilIdle()
            val state = awaitItem()
            assertEquals(2, state.activeNdc?.bottles)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // INV_VM_006
    @Test
    fun `decrement decreases bottles by 1 when bottles is greater than 1`() = runTest {
        setupScanMocks()

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            viewModel.onBarcodeDetected(TEST_NDC)
            advanceUntilIdle()
            skipUntil { it.activeNdc != null } // bottles=1

            viewModel.increment()      // → bottles=2
            advanceUntilIdle()
            awaitItem()

            viewModel.decrement()      // → bottles=1
            advanceUntilIdle()
            val state = awaitItem()
            assertEquals(1, state.activeNdc?.bottles)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // INV_VM_007
    @Test
    fun `decrement coerces bottles to 1 when already at minimum`() = runTest {
        setupScanMocks()

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            viewModel.onBarcodeDetected(TEST_NDC)
            advanceUntilIdle()
            skipUntil { it.activeNdc != null } // bottles=1

            viewModel.decrement() // coerceAtLeast(1) → no change → no new emission
            advanceUntilIdle()

            // StateFlow suppresses equal values, so no new item; verify current cached value
            assertEquals(1, viewModel.uiState.value.activeNdc?.bottles)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────── onClear ───────────────────────────

    // INV_VM_008
    @Test
    fun `onClear removes activeNdc from uiState`() = runTest {
        setupScanMocks()

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            viewModel.onBarcodeDetected(TEST_NDC)
            advanceUntilIdle()
            skipUntil { it.activeNdc != null }

            viewModel.onClear()
            advanceUntilIdle()
            val cleared = awaitItem()
            assertNull(cleared.activeNdc)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────── confirmEndCount ───────────────────────────

    // INV_VM_009
    @Test
    fun `confirmEndCount with no committed rows sets batchEnded without calling markAsCompleted`() = runTest {
        // recentRows.value stays emptyList() — no subscriber triggers the upstream
        advanceUntilIdle()

        viewModel.confirmEndCount()
        advanceUntilIdle()

        assertTrue(viewModel.batchEnded.value)
        coVerify(exactly = 0) { batchDao.markAsCompleted(any(), any()) }
    }

    // INV_VM_010
    @Test
    fun `confirmEndCount with committed rows calls markAsCompleted and sets batchEnded`() = runTest {
        val txnRow = BatchTxnDto(
            txnId = 1L, drugId = 1L, drugName = "Aspirin", ndc = TEST_NDC,
            lotNo = null, expiry = null, bottleQty = 1, looseQty = 0, packageQty = 10
        )
        every { pillCountTxnDao.observeByBatchId(any()) } returns flowOf(listOf(txnRow))

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle() // recentRows upstream runs → receives listOf(txnRow) → non-empty

            viewModel.confirmEndCount()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(viewModel.batchEnded.value)
        // markAsCompleted(batchId, timestamp) — use any() for the auto-generated timestamp
        coVerify(exactly = 1) { batchDao.markAsCompleted(eq(BATCH_ID), any()) }
    }

    // INV_VM_011
    @Test
    fun `confirmEndCount with non-blank note calls batchDao updateNote`() = runTest {
        val txnRow = BatchTxnDto(
            txnId = 1L, drugId = 1L, drugName = "Aspirin", ndc = TEST_NDC,
            lotNo = null, expiry = null, bottleQty = 1, looseQty = 0, packageQty = 10
        )
        every { pillCountTxnDao.observeByBatchId(any()) } returns flowOf(listOf(txnRow))

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            viewModel.confirmEndCount(note = "end of shift")
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { batchDao.updateNote(BATCH_ID, "end of shift") }
    }

    // INV_VM_012
    @Test
    fun `confirmEndCount with batchId zero sets batchEnded but skips markAsCompleted`() = runTest {
        // batchId=0 → hasCommittedNdc=false → skips markAsCompleted
        // finally block always sets batchEnded=true regardless
        val vm = createViewModel(batchId = 0L)
        advanceUntilIdle()

        vm.confirmEndCount()
        advanceUntilIdle()

        assertTrue(vm.batchEnded.value)
        coVerify(exactly = 0) { batchDao.markAsCompleted(any(), any()) }
    }
}
