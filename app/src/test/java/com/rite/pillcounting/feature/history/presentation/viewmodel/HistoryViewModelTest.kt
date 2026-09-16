package com.rite.pillcounting.feature.history.presentation.viewmodel

import app.cash.turbine.test
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.history.data.HistoryRepository
import com.rite.pillcounting.feature.history.domain.model.BatchSummary
import com.rite.pillcounting.feature.history.domain.model.HistoryDeleteFilter
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.feature.history.domain.model.ToggleOption
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [HistoryViewModel].
 *
 * counts and batchGroups are backed by stateIn(WhileSubscribed(5_000)). The upstream only starts
 * when there is an active subscriber, so tests collect via Turbine's test{} to trigger the chain.
 * advanceUntilIdle() is required after mutations because all flow operators run on the test
 * dispatcher (viewModelScope → Dispatchers.Main, replaced by MainDispatcherRule).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: HistoryRepository = mockk()
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)

    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setup() {
        every { preferenceHelper.getLocalId() } returns 1L
        every { repository.getTransactionsForDateRange(any(), any(), any(), any(), any()) } returns flowOf(emptyList())
        every { repository.getBatchSummaries(any(), any()) } returns flowOf(emptyList())
        viewModel = HistoryViewModel(repository, preferenceHelper)
    }

    @After
    fun tearDown() { unmockkAll() }

    private fun txnDto(
        id: Long = 1L,
        drugName: String? = "Drug",
        ndc: String? = "00000",
        note: String? = null
    ) = TxnWithDrugDto(
        txnId = id, isDispense = true, status = CountStatus.COMPLETED,
        pillCount = 10, drugName = drugName, ndc = ndc, bottleInfoListJson = null,
        createdAt = 1000L, targetCount = 10, note = note, bucketId = null, drugType = null
    )

    // ─────────────────────────── Flow emission ───────────────────────────

    // HIST_VM_001
    @Test
    fun `counts emits list returned by repository`() = runTest {
        val txns = listOf(txnDto(id = 1L, drugName = "Aspirin"))
        every { repository.getTransactionsForDateRange(any(), any(), any(), any(), any()) } returns flowOf(txns)

        viewModel.counts.test {
            awaitItem() // stateIn initial: emptyList
            advanceUntilIdle()
            val list = awaitItem()
            assertEquals(txns, list)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_VM_002
    @Test
    fun `setSearchQuery filters counts by drug name`() = runTest {
        val aspirin = txnDto(id = 1L, drugName = "Aspirin")
        val ibuprofen = txnDto(id = 2L, drugName = "Ibuprofen")
        every { repository.getTransactionsForDateRange(any(), any(), any(), any(), any()) } returns flowOf(listOf(aspirin, ibuprofen))

        viewModel.counts.test {
            awaitItem()
            advanceUntilIdle()
            awaitItem() // full list

            viewModel.setSearchQuery("Aspirin")
            advanceUntilIdle()
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals("Aspirin", filtered[0].drugName)

            viewModel.setSearchQuery("")
            advanceUntilIdle()
            val all = awaitItem()
            assertEquals(2, all.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_VM_003
    @Test
    fun `setSearchQuery filters counts by ndc`() = runTest {
        val drug1 = txnDto(id = 1L, ndc = "12345")
        val drug2 = txnDto(id = 2L, ndc = "67890")
        every { repository.getTransactionsForDateRange(any(), any(), any(), any(), any()) } returns flowOf(listOf(drug1, drug2))

        viewModel.counts.test {
            awaitItem()
            advanceUntilIdle()
            awaitItem()

            viewModel.setSearchQuery("123")
            advanceUntilIdle()
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals("12345", filtered[0].ndc)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_VM_004
    @Test
    fun `setSearchQuery filters counts by note field`() = runTest {
        val withNote = txnDto(id = 1L, note = "urgent dispense")
        val noNote = txnDto(id = 2L, note = null)
        every { repository.getTransactionsForDateRange(any(), any(), any(), any(), any()) } returns flowOf(listOf(withNote, noNote))

        viewModel.counts.test {
            awaitItem()
            advanceUntilIdle()
            awaitItem()

            viewModel.setSearchQuery("urgent")
            advanceUntilIdle()
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals("urgent dispense", filtered[0].note)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_VM_005
    @Test
    fun `setHistoryMode DISPENSE queries repository with isDispense true`() = runTest {
        val normalTxns = listOf(txnDto(id = 1L))
        val dispenseTxns = listOf(txnDto(id = 2L))
        every { repository.getTransactionsForDateRange(any(), any(), null, any(), any()) } returns flowOf(normalTxns)
        every { repository.getTransactionsForDateRange(any(), any(), true, any(), any()) } returns flowOf(dispenseTxns)

        viewModel.counts.test {
            awaitItem()
            advanceUntilIdle()
            awaitItem() // normalTxns

            viewModel.setHistoryMode(HistoryMode.DISPENSE)
            advanceUntilIdle()
            val dispense = awaitItem()
            assertEquals(dispenseTxns, dispense)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_VM_006
    @Test
    fun `setHistoryMode NORMAL queries repository with null type`() = runTest {
        val dispenseTxns = listOf(txnDto(id = 1L))
        val normalTxns = listOf(txnDto(id = 2L))
        every { repository.getTransactionsForDateRange(any(), any(), true, any(), any()) } returns flowOf(dispenseTxns)
        every { repository.getTransactionsForDateRange(any(), any(), null, any(), any()) } returns flowOf(normalTxns)

        viewModel.counts.test {
            awaitItem()
            advanceUntilIdle()
            awaitItem() // normalTxns (initial NORMAL mode)

            viewModel.setHistoryMode(HistoryMode.DISPENSE)
            advanceUntilIdle()
            awaitItem() // dispenseTxns

            viewModel.setHistoryMode(HistoryMode.NORMAL)
            advanceUntilIdle()
            val normal = awaitItem()
            assertEquals(normalTxns, normal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─────────────────────────── Date range / date selection ───────────────────────────

    // HIST_VM_007
    @Test
    fun `setDateRange updates startDate and endDate state`() = runTest {
        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 1, 31)

        viewModel.setDateRange(start, end)

        assertEquals(start, viewModel.startDate.value)
        assertEquals(end, viewModel.endDate.value)
    }

    // HIST_VM_008
    @Test
    fun `selectDate updates selectedDate state`() = runTest {
        val date = LocalDate.of(2024, 6, 15)

        viewModel.selectDate(date)

        assertEquals(date, viewModel.selectedDate.value)
    }

    // ─────────────────────────── Delete operations ───────────────────────────

    // HIST_VM_009
    @Test
    fun `deleteCountsForSelectedDate STOCK ALL calls deleteBatchesForDateRange with null isCompleted`() = runTest {
        coJustRun { repository.deleteBatchesForDateRange(any(), any(), isNull()) }

        viewModel.deleteCountsForSelectedDate(ToggleOption.STOCK, HistoryDeleteFilter.ALL)
        advanceUntilIdle()

        coVerify { repository.deleteBatchesForDateRange(any(), any(), null) }
    }

    // HIST_VM_010
    @Test
    fun `deleteCountsForSelectedDate STOCK COMPLETED passes isCompleted=true`() = runTest {
        coJustRun { repository.deleteBatchesForDateRange(any(), any(), any()) }

        viewModel.deleteCountsForSelectedDate(ToggleOption.STOCK, HistoryDeleteFilter.COMPLETED)
        advanceUntilIdle()

        coVerify { repository.deleteBatchesForDateRange(any(), any(), true) }
    }

    // HIST_VM_011
    @Test
    fun `deleteCountsForSelectedDate STOCK PENDING passes isCompleted=false`() = runTest {
        coJustRun { repository.deleteBatchesForDateRange(any(), any(), any()) }

        viewModel.deleteCountsForSelectedDate(ToggleOption.STOCK, HistoryDeleteFilter.PENDING)
        advanceUntilIdle()

        coVerify { repository.deleteBatchesForDateRange(any(), any(), false) }
    }

    // HIST_VM_012
    @Test
    fun `deleteCountsForSelectedDate DISPENSED ALL calls deleteTransactionsForDate with null isCompleted`() = runTest {
        coJustRun { repository.deleteTransactionsForDate(any(), any(), any(), any(), any()) }

        viewModel.deleteCountsForSelectedDate(ToggleOption.DISPENSED, HistoryDeleteFilter.ALL)
        advanceUntilIdle()

        coVerify { repository.deleteTransactionsForDate(any(), any(), any(), null, any()) }
    }

    // HIST_VM_013
    @Test
    fun `deleteCountsForSelectedDate DISPENSED in DISPENSE mode passes isDispense true to repository`() = runTest {
        coJustRun { repository.deleteTransactionsForDate(any(), any(), any(), any(), any()) }
        viewModel.setHistoryMode(HistoryMode.DISPENSE)

        viewModel.deleteCountsForSelectedDate(ToggleOption.DISPENSED, HistoryDeleteFilter.ALL)
        advanceUntilIdle()

        coVerify { repository.deleteTransactionsForDate(any(), any(), true, null, any()) }
    }

    // ─────────────────────────── Transaction selection ───────────────────────────

    // HIST_VM_014
    @Test
    fun `selectCurrentTransaction delegates txnId to preferenceHelper`() = runTest {
        viewModel.selectCurrentTransaction(77L)

        verify { preferenceHelper.saveTxnId(77L) }
    }

    // ─────────────────────────── batchGroups flow ───────────────────────────

    // HIST_VM_015
    @Test
    fun `batchGroups emits batch summaries returned by repository`() = runTest {
        val batch = BatchSummary(
            batchId = 42L, createdAt = 1000L, uniqueNdcCount = 5,
            status = BatchStatus.COMPLETED, bucketId = null, requestIdFromPMS = null
        )
        every { repository.getBatchSummaries(any(), any()) } returns flowOf(listOf(batch))

        viewModel.batchGroups.test {
            awaitItem()
            advanceUntilIdle()
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(42L, list[0].batchId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_VM_016
    @Test
    fun `setSearchQuery filters batchGroups by bucketId`() = runTest {
        val batch1 = BatchSummary(batchId = 1L, createdAt = 1000L, uniqueNdcCount = 3,
            status = BatchStatus.INPROGRESS, bucketId = "BUCKET-A", requestIdFromPMS = null)
        val batch2 = BatchSummary(batchId = 2L, createdAt = 2000L, uniqueNdcCount = 2,
            status = BatchStatus.COMPLETED, bucketId = "BUCKET-B", requestIdFromPMS = null)
        every { repository.getBatchSummaries(any(), any()) } returns flowOf(listOf(batch1, batch2))

        viewModel.batchGroups.test {
            awaitItem()
            advanceUntilIdle()
            awaitItem() // both batches

            viewModel.setSearchQuery("BUCKET-A")
            advanceUntilIdle()
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals("BUCKET-A", filtered[0].bucketId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
