package com.rite.pillcounting.feature.unsyncedTransaction.viewmodel

import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import com.rite.pillcounting.feature.unsyncedTransaction.presentation.viewmodel.UnsyncedTransactionViewModel
import com.rite.pillcounting.util.MainDispatcherRule
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
 * hl7Repository.resendPendingHl7BatchTransactions() when it transitions to true.
 * The other two init observers use DAO flows that never emit under relaxed mocks,
 * so state stays at defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsyncedTransactionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val hl7Repository: Hl7Repository = mockk(relaxed = true)
    private val hl7EventHandler: Hl7EventHandler = mockk(relaxed = true)

    private val connectionStateFlow = MutableStateFlow(false)

    private lateinit var viewModel: UnsyncedTransactionViewModel

    @Before
    fun setup() {
        every { hl7EventHandler.connectionState } returns connectionStateFlow
        coJustRun { hl7Repository.resendPendingHl7BatchTransactions() }
        viewModel = UnsyncedTransactionViewModel(
            pillCountTxnDao = pillCountTxnDao,
            batchDao = batchDao,
            hl7Repository = hl7Repository,
            hl7EventHandler = hl7EventHandler,
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
    fun `syncBatchesWhenConnected calls resendPendingHl7BatchTransactions when connectionState becomes true`() = runTest {
        connectionStateFlow.value = true
        advanceUntilIdle()

        coVerify(atLeast = 1) { hl7Repository.resendPendingHl7BatchTransactions() }
    }

    // UNSYNCED_VM_003
    @Test
    fun `resendPendingHl7BatchTransactions is not called when connectionState stays false`() = runTest {
        // connectionStateFlow starts at false; never set to true
        advanceUntilIdle()

        coVerify(exactly = 0) { hl7Repository.resendPendingHl7BatchTransactions() }
    }

    // UNSYNCED_VM_004
    @Test
    fun `resendPendingHl7BatchTransactions is called again on each subsequent true emission`() = runTest {
        connectionStateFlow.value = true
        advanceUntilIdle()
        // Toggle back to false (filter skips), then true again
        connectionStateFlow.value = false
        advanceUntilIdle()
        connectionStateFlow.value = true
        advanceUntilIdle()

        coVerify(atLeast = 2) { hl7Repository.resendPendingHl7BatchTransactions() }
    }
}
