package com.dispensesure.retail.feature.unsyncedTransaction.viewmodel

import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.feature.hl7.core.Hl7EventHandler
import com.dispensesure.retail.feature.hl7.data.repository.StockHl7Repository
import com.dispensesure.retail.feature.unsyncedTransaction.presentation.viewmodel.UnsyncedTransactionViewModel
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [UnsyncedTransactionViewModel].
 *
 * syncBatchesWhenConnected collects hl7EventHandler.connectionState and calls
 * stockHl7Repository.sendPendingBatches() when it transitions to true.
 * The other two init observers use DAO flows that never emit under relaxed mocks,
 * so state stays at defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsyncedTransactionViewModelTest {

    private val regionProvider: RegionProvider = mockk { every { current } returns Region.US }

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dispenseTxnDao: DispenseTxnDao = mockk(relaxed = true)
    private val stockBatchDao: StockBatchDao = mockk(relaxed = true)
    private val stockHl7Repository: StockHl7Repository = mockk(relaxed = true)
    private val hl7EventHandler: Hl7EventHandler = mockk(relaxed = true)

    private val connectionStateFlow = MutableStateFlow(false)

    private lateinit var viewModel: UnsyncedTransactionViewModel

    @Before
    fun setup() {
        every { hl7EventHandler.connectionState } returns connectionStateFlow
        coJustRun { stockHl7Repository.sendPendingBatches() }
        viewModel = UnsyncedTransactionViewModel(
            dispenseTxnDao = dispenseTxnDao,
            stockBatchDao = stockBatchDao,
            stockHl7Repository = stockHl7Repository,
            hl7EventHandler = hl7EventHandler,
            regionProvider = regionProvider,
        )
    }

    @After
    fun tearDown() { unmockkAll() }

    // UNSYNCED_VM_001
    @Test
    fun `initial unsyncedTransactionUiState has empty dispenseList and batchList`() {
        val state = viewModel.unsyncedTransactionUiState.value

        assertTrue(state.dispenseList.isEmpty())
        assertTrue(state.batchList.isEmpty())
    }

    // UNSYNCED_VM_002
    @Test
    fun `syncBatchesWhenConnected calls sendPendingBatches when connectionState becomes true`() = runTest {
        connectionStateFlow.value = true
        advanceUntilIdle()

        coVerify(atLeast = 1) { stockHl7Repository.sendPendingBatches() }
    }

    // UNSYNCED_VM_003
    @Test
    fun `sendPendingBatches is not called when connectionState stays false`() = runTest {
        // connectionStateFlow starts at false; never set to true
        advanceUntilIdle()

        coVerify(exactly = 0) { stockHl7Repository.sendPendingBatches() }
    }

    // UNSYNCED_VM_004
    @Test
    fun `sendPendingBatches is called again on each subsequent true emission`() = runTest {
        connectionStateFlow.value = true
        advanceUntilIdle()
        // Toggle back to false (filter skips), then true again
        connectionStateFlow.value = false
        advanceUntilIdle()
        connectionStateFlow.value = true
        advanceUntilIdle()

        coVerify(atLeast = 2) { stockHl7Repository.sendPendingBatches() }
    }
}
