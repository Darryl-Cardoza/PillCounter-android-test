package com.rite.pillcounting.feature.unsyncedTransaction.presentation.viewmodel

import android.util.Log
import app.cash.turbine.test
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto
import com.rite.pillcounting.core.room.models.dtos.PillCountWithDrugAndTotal
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnsyncedTransactionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var pillCountTxnDao: PillCountTxnDao
    private lateinit var batchDao: BatchDao
    private lateinit var hl7Repository: Hl7Repository
    private lateinit var hl7EventHandler: Hl7EventHandler

    private lateinit var connectionState: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // AppLogger / java.util Log is not available on the JVM.
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        pillCountTxnDao = mockk(relaxed = true)
        batchDao = mockk(relaxed = true)
        hl7Repository = mockk(relaxed = true)
        hl7EventHandler = mockk(relaxed = true)

        connectionState = MutableStateFlow(false)

        // Sensible defaults so the init observers do not blow up.
        every {
            pillCountTxnDao.observeUnsyncedByCountType(any(), any(), any(), any())
        } returns flowOf(emptyList())
        every { batchDao.observeUnsyncedCompletedBatches() } returns flowOf(emptyList())
        every { hl7EventHandler.connectionState } returns connectionState
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): UnsyncedTransactionViewModel =
        UnsyncedTransactionViewModel(pillCountTxnDao, batchDao, hl7Repository, hl7EventHandler)

    private fun pillCount(
        drugName: String? = "Aspirin",
        targetCount: Int? = 50
    ) = PillCountWithDrugAndTotal(
        txnId = 7L,
        drugName = drugName,
        ndc = "ndc-1",
        drugType = "tablet",
        bucketId = "bucket-1",
        createdAt = 1_700_000_000_000L,
        targetCount = targetCount,
        barcodeImage = "barcode.png",
        totalPillCount = 42,
        isComingFromHL7 = true,
        isNdcVerified = true,
        countType = CountType.FIXED,
        priority = null
    )

    // ────────────────────────────── observeUnsyncedDispense ──────────────────────────────

    @Test
    fun `observeUnsyncedDispense maps txn to CountItem with non-null drugName`() =
        runTest(testDispatcher) {
            every {
                pillCountTxnDao.observeUnsyncedByCountType(any(), any(), any(), any())
            } returns flowOf(listOf(pillCount(drugName = "Aspirin", targetCount = 50)))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.unsyncedTransactionUiState.test {
                val state = awaitItem()
                assertEquals(1, state.dispenseList.size)
                val item = state.dispenseList.first()
                assertEquals(7L, item.id)
                assertEquals("Aspirin", item.name)
                assertEquals("ndc-1", item.ndc)
                assertEquals("tablet", item.drugType)
                assertEquals("bucket-1", item.bucketId)
                assertEquals(42, item.pillCount)
                assertEquals(50, item.target)
                assertEquals("barcode.png", item.barcodeImage)
                assertTrue(item.isComingFromHL7)
                assertTrue(item.isNdcVerified)
                assertEquals(CountType.FIXED, item.countType)
                // Real toFormattedDate call on a positive epoch millis -> not the fallback.
                assertTrue(item.date.isNotBlank())
                assertTrue(item.date != "-")
            }
        }

    @Test
    fun `observeUnsyncedDispense maps null drugName to empty and null target to zero`() =
        runTest(testDispatcher) {
            every {
                pillCountTxnDao.observeUnsyncedByCountType(any(), any(), any(), any())
            } returns flowOf(listOf(pillCount(drugName = null, targetCount = null)))

            val vm = createViewModel()
            advanceUntilIdle()

            val item = vm.unsyncedTransactionUiState.value.dispenseList.first()
            assertEquals("", item.name)
            assertEquals(0, item.target)
        }

    @Test
    fun `observeUnsyncedDispense catch keeps default empty list`() = runTest(testDispatcher) {
        every {
            pillCountTxnDao.observeUnsyncedByCountType(any(), any(), any(), any())
        } returns flow { throw RuntimeException("dispense error") }

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.unsyncedTransactionUiState.value.dispenseList.isEmpty())
    }

    // ────────────────────────────── observeUnsyncedBatches ──────────────────────────────

    @Test
    fun `observeUnsyncedBatches maps dto to BatchSummary`() = runTest(testDispatcher) {
        every { batchDao.observeUnsyncedCompletedBatches() } returns flowOf(
            listOf(
                BatchSummaryDto(
                    batchId = 9L,
                    createdAt = 1_700_000_000_000L,
                    uniqueNdcCount = 3,
                    status = "COMPLETED",
                    bucketId = "bucket-9",
                    requestIdFromPMS = "req-9"
                )
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.unsyncedTransactionUiState.test {
            val state = awaitItem()
            assertEquals(1, state.batchList.size)
            val batch = state.batchList.first()
            assertEquals(9L, batch.batchId)
            assertEquals(1_700_000_000_000L, batch.createdAt)
            assertEquals(3, batch.uniqueNdcCount)
            assertEquals(BatchStatus.COMPLETED, batch.status)
            assertEquals("bucket-9", batch.bucketId)
            assertEquals("req-9", batch.requestIdFromPMS)
        }
    }

    @Test
    fun `observeUnsyncedBatches catch keeps default empty list`() = runTest(testDispatcher) {
        // Invalid status forces BatchStatus.valueOf to throw -> flow fails -> catch.
        every { batchDao.observeUnsyncedCompletedBatches() } returns flowOf(
            listOf(
                BatchSummaryDto(
                    batchId = 1L,
                    createdAt = 1_700_000_000_000L,
                    uniqueNdcCount = 1,
                    status = "NOT_A_STATUS",
                    bucketId = null,
                    requestIdFromPMS = null
                )
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.unsyncedTransactionUiState.value.batchList.isEmpty())
    }

    // ────────────────────────────── syncBatchesWhenConnected ──────────────────────────────

    @Test
    fun `syncBatchesWhenConnected resends when connection becomes true`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            connectionState.value = true
            advanceUntilIdle()

            verify(exactly = 1) { hl7Repository.resendPendingHl7BatchTransactions() }
            // touch the vm so it is not flagged unused.
            assertTrue(vm.unsyncedTransactionUiState.value.dispenseList.isEmpty())
        }

    @Test
    fun `syncBatchesWhenConnected does not resend while disconnected`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            verify(exactly = 0) { hl7Repository.resendPendingHl7BatchTransactions() }
            assertTrue(vm.unsyncedTransactionUiState.value.batchList.isEmpty())
        }
}
