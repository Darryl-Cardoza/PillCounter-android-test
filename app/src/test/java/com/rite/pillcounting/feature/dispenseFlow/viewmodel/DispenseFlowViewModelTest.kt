package com.rite.pillcounting.feature.dispenseFlow.viewmodel

import android.content.Context
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseStage
import com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel.DispenseFlowViewModel
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Unit tests for [DispenseFlowViewModel].
 *
 * All tests cover synchronous state-mutation methods and simple guard conditions.
 * The complex scan flows (onRxBarcodeRead, onNdcBarcodeRead) that depend on
 * parseScanData, DrugMasterDao, or network are intentionally not covered here —
 * they require integration-level setup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispenseFlowViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val appContext: Context = mockk(relaxed = true)
    private val drugRepository: IDrugRepository = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val stockTxnDao: StockTxnDao = mockk(relaxed = true)
    private val bottleInfoDao: BottleInfoDao = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val drugImageDownloader: DrugImageDownloader = mockk(relaxed = true)

    private lateinit var viewModel: DispenseFlowViewModel

    @Before
    fun setup() {
        viewModel = DispenseFlowViewModel(
            appContext = appContext,
            drugRepository = drugRepository,
            drugMasterDao = drugMasterDao,
            preferenceHelper = preferenceHelper,
            pillCountTxnDao = pillCountTxnDao,
            stockTxnDao = stockTxnDao,
            bottleInfoDao = bottleInfoDao,
            batchDao = batchDao,
            drugImageDownloader = drugImageDownloader,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // DISP_VM_001
    @Test
    fun `setCountType REGULAR sets stage to PRE_NDC`() {
        viewModel.setCountType("REGULAR")

        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
        assertEquals("REGULAR", viewModel.uiState.value.scanType)
    }

    // DISP_VM_002
    @Test
    fun `setCountType FIXED sets stage to PRE_RX`() {
        viewModel.setCountType("FIXED")

        assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
    }

    // DISP_VM_003
    @Test
    fun `setBatchId with non-zero value stores batchId in uiState`() {
        viewModel.setBatchId(batchId = 5L)

        assertEquals(5L, viewModel.uiState.value.batchId)
    }

    // DISP_VM_004
    @Test
    fun `setBatchId with zero does not change batchId`() {
        viewModel.setBatchId(0L)

        assertEquals(0L, viewModel.uiState.value.batchId)
    }

    // DISP_VM_005
    @Test
    fun `onRxCancelled clears RX fields and hides the sheet`() {
        viewModel.onRxCancelled()

        val state = viewModel.uiState.value
        assertFalse(state.showRxDetails)
        assertEquals("", state.drugName)
        assertEquals("", state.ndc)
        assertNull(state.rxNo)
        assertNull(state.qty)
    }

    // DISP_VM_006
    @Test
    fun `dismissOnHoldDialog sets showOnHoldDialog to false`() {
        viewModel.dismissOnHoldDialog()

        assertFalse(viewModel.uiState.value.showOnHoldDialog)
    }

    // DISP_VM_007
    @Test
    fun `dismissNdcEquivalenceDialog clears equivalence dialog and resets scanned fields`() {
        viewModel.dismissNdcEquivalenceDialog()

        val state = viewModel.uiState.value
        assertFalse(state.showNdcEquivalenceDialog)
        assertEquals("", state.ndcScannedValue)
        assertEquals("", state.ndcDrugName)
        assertNull(state.ndcPackageQty)
        assertNull(state.ndcDrugType)
    }

    // DISP_VM_008
    @Test
    fun `onNdcCancelled hides the NDC sheet and resets scanned fields`() {
        viewModel.onNdcCancelled()

        val state = viewModel.uiState.value
        assertFalse(state.showNdcDetails)
        assertEquals("", state.ndcScannedValue)
        assertEquals("", state.ndcDrugName)
        assertFalse(state.isSubstituteConfirmed)
    }

    // DISP_VM_009
    @Test
    fun `dismissInvalidScanDialog sets showInvalidScanDialog to false`() {
        viewModel.dismissInvalidScanDialog()

        assertFalse(viewModel.uiState.value.showInvalidScanDialog)
    }

    // DISP_VM_010
    @Test
    fun `dismissNdcNotFoundDialog sets showNdcNotFoundDialog to false`() {
        viewModel.dismissNdcNotFoundDialog()

        assertFalse(viewModel.uiState.value.showNdcNotFoundDialog)
    }

    // DISP_VM_011
    @Test
    fun `setAllowedNdcs stores the provided set in uiState`() {
        viewModel.setAllowedNdcs(setOf("00000000000001", "00000000000002"))

        assertEquals(setOf("00000000000001", "00000000000002"), viewModel.uiState.value.allowedNdcs)
    }

    // DISP_VM_012
    @Test
    fun `clearNavigateToBatch sets navigateToBatchId to null`() {
        viewModel.clearNavigateToBatch()

        assertNull(viewModel.uiState.value.navigateToBatchId)
    }

    // DISP_VM_013
    @Test
    fun `clearError sets error to null`() {
        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    // DISP_VM_014
    @Test
    fun `clearNavigateToDashboard sets navigateToDashboard to false`() {
        viewModel.clearNavigateToDashboard()

        assertFalse(viewModel.uiState.value.navigateToDashboard)
    }

    // DISP_VM_015
    @Test
    fun `dismissContinueRxDialog sets showContinueRxDialog to false and resets txnId`() {
        viewModel.dismissContinueRxDialog()

        assertFalse(viewModel.uiState.value.showContinueRxDialog)
        assertEquals(0L, viewModel.uiState.value.txnId)
    }

    // DISP_VM_016
    @Test
    fun `onRxScannedInNdcStage increments scanNdcToastTick when stage is PRE_NDC`() {
        viewModel.setCountType("REGULAR") // → PRE_NDC
        val before = viewModel.uiState.value.scanNdcToastTick

        viewModel.onRxScannedInNdcStage()

        assertEquals(before + 1, viewModel.uiState.value.scanNdcToastTick)
    }

    // DISP_VM_017
    @Test
    fun `onRxScannedInNdcStage is a no-op when stage is not PRE_NDC`() {
        // Default stage is PRE_RX
        val before = viewModel.uiState.value.scanNdcToastTick

        viewModel.onRxScannedInNdcStage()

        assertEquals(before, viewModel.uiState.value.scanNdcToastTick)
    }

    // DISP_VM_018
    @Test
    fun `onRxScannedInStockCount shows blocking dialog when stage is PRE_NDC`() {
        viewModel.setCountType("REGULAR") // → PRE_NDC

        viewModel.onRxScannedInStockCount()

        assertTrue(viewModel.uiState.value.showRxScannedInStockCountDialog)
    }

    // DISP_VM_019
    @Test
    fun `dismissRxScannedInStockCountDialog sets showRxScannedInStockCountDialog to false`() {
        viewModel.dismissRxScannedInStockCountDialog()

        assertFalse(viewModel.uiState.value.showRxScannedInStockCountDialog)
    }

    // DISP_VM_021
    @Test
    fun `confirmSubstitute with FIXED countType sets isSubstituteConfirmed and hides equivalence dialog`() = runTest {
        // Default countType is FIXED → needsSheet = false
        viewModel.confirmSubstitute()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSubstituteConfirmed)
        assertFalse(viewModel.uiState.value.showNdcEquivalenceDialog)
    }

    // DISP_VM_022
    @Test
    fun `setQueueFilter stores the filter in uiState`() {
        viewModel.setQueueFilter(KpiFilter.DISP_PENDING)

        assertEquals(KpiFilter.DISP_PENDING, viewModel.uiState.value.selectedQueueFilter)
    }

    // DISP_VM_023
    @Test
    fun `setQueueFilter with null is a no-op`() {
        viewModel.setQueueFilter(KpiFilter.DISP_PENDING)
        viewModel.setQueueFilter(null)

        // Filter stays at whatever was set before
        assertEquals(KpiFilter.DISP_PENDING, viewModel.uiState.value.selectedQueueFilter)
    }
}
