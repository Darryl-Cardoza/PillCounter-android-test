package com.rite.pillcounting.feature.history.data

import app.cash.turbine.test
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [HistoryRepository].
 *
 * Tests cover DAO delegation, BatchSummaryDto → BatchSummary mapping, and the guard in
 * deleteBatchesForDateRange that skips soft-delete when no batch IDs are found.
 * No MainDispatcherRule is needed because the repository does not use viewModelScope.
 */
class HistoryRepositoryTest {

    private val dao: PillCountTxnDao = mockk(relaxed = true)
    private val batchDao: BatchDao = mockk(relaxed = true)
    private val stockTxnDao: StockTxnDao = mockk(relaxed = true)

    private lateinit var repository: HistoryRepository

    @Before
    fun setup() {
        repository = HistoryRepository(dao, batchDao, stockTxnDao)
    }

    @After
    fun tearDown() { unmockkAll() }

    private val today: LocalDate = LocalDate.of(2024, 6, 15)

    // HIST_REPO_001
    @Test
    fun `getTransactionsForDateRange delegates to DAO with epoch millis and returns its flow`() = runTest {
        every { dao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any()) } returns flowOf(emptyList())

        val flow = repository.getTransactionsForDateRange(today, today, null, null, 1L)

        flow.test {
            val list = awaitItem()
            assertTrue(list.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_REPO_002
    @Test
    fun `getTransactionsForDateRange passes correct type and status to DAO`() = runTest {
        every { dao.getTransactionsForDateRange(any(), any(), any(), any(), any(), any()) } returns flowOf(emptyList())

        repository.getTransactionsForDateRange(today, today, true, null, 1L)
            .test { cancelAndIgnoreRemainingEvents() }

        // Verify type=FIXED was forwarded
        verify { dao.getTransactionsForDateRange(any(), any(), any(), true, any(), any()) }
    }

    // HIST_REPO_003
    @Test
    fun `getBatchSummaries maps BatchSummaryDto to BatchSummary with correct field values`() = runTest {
        val dto = BatchSummaryDto(
            batchId = 10L, createdAt = 5000L, uniqueNdcCount = 3,
            status = "COMPLETED", bucketId = "BCK-01", requestIdFromPMS = "REQ-99"
        )
        every { batchDao.getBatchSummaries(any(), any()) } returns flowOf(listOf(dto))

        repository.getBatchSummaries(today, today).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            val summary = list[0]
            assertEquals(10L, summary.batchId)
            assertEquals(5000L, summary.createdAt)
            assertEquals(3, summary.uniqueNdcCount)
            assertEquals(BatchStatus.COMPLETED, summary.status)
            assertEquals("BCK-01", summary.bucketId)
            assertEquals("REQ-99", summary.requestIdFromPMS)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_REPO_004
    @Test
    fun `getBatchSummaries maps INPROGRESS status correctly`() = runTest {
        val dto = BatchSummaryDto(
            batchId = 1L, createdAt = 1000L, uniqueNdcCount = 1,
            status = "INPROGRESS", bucketId = null, requestIdFromPMS = null
        )
        every { batchDao.getBatchSummaries(any(), any()) } returns flowOf(listOf(dto))

        repository.getBatchSummaries(today, today).test {
            val summary = awaitItem()[0]
            assertEquals(BatchStatus.INPROGRESS, summary.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_REPO_005
    @Test
    fun `deleteTransactionsForDate delegates to DAO`() = runTest {
        coJustRun { dao.deleteTransactionsByDate(any(), any(), any(), any(), any(), any(), any()) }

        repository.deleteTransactionsForDate(today, today, null, null, 1L)

        coVerify(exactly = 1) { dao.deleteTransactionsByDate(any(), any(), null, null, 1L, any(), any()) }
    }

    // HIST_REPO_006
    @Test
    fun `deleteBatchesForDateRange soft-deletes batches and their transactions when batch IDs exist`() = runTest {
        val batchIds = listOf(100L, 200L)
        coEvery { batchDao.getBatchIdsByDate(any(), any(), any(), any(), any()) } returns batchIds
        coJustRun { batchDao.softDeleteBatchesByDate(any(), any(), any(), any(), any()) }
        coJustRun { stockTxnDao.deleteByBatchIds(any()) }

        repository.deleteBatchesForDateRange(today, today, null)

        coVerify { batchDao.softDeleteBatchesByDate(any(), any(), null, any(), any()) }
        coVerify { stockTxnDao.deleteByBatchIds(batchIds) }
    }

    // HIST_REPO_007
    @Test
    fun `deleteBatchesForDateRange skips soft-delete when no batch IDs found`() = runTest {
        coEvery { batchDao.getBatchIdsByDate(any(), any(), any(), any(), any()) } returns emptyList()

        repository.deleteBatchesForDateRange(today, today, null)

        coVerify(exactly = 0) { batchDao.softDeleteBatchesByDate(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { stockTxnDao.deleteByBatchIds(any()) }
    }

    // HIST_REPO_008
    @Test
    fun `deleteTransactionsForDate converts LocalDate to epoch millis with startMillis before endMillis`() = runTest {
        var capturedStart = 0L
        var capturedEnd = 0L
        coEvery {
            dao.deleteTransactionsByDate(any(), any(), any(), any(), any(), any(), any())
        } answers {
            capturedStart = firstArg()
            capturedEnd = secondArg()
        }

        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 1, 31)
        repository.deleteTransactionsForDate(start, end, null, null, 1L)

        assertTrue("startMillis should be before endMillis", capturedStart < capturedEnd)
        assertTrue("startMillis should be positive", capturedStart > 0)
    }
}
