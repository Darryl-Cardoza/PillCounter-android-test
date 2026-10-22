package com.dispensesure.retail.feature.history.data

import app.cash.turbine.test
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.BatchDeleted
import com.dispensesure.retail.core.audit.model.HistoryBulkDeleted
import com.dispensesure.retail.core.room.models.dtos.RecordSyncState
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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

    private val regionProvider: RegionProvider = mockk { every { current } returns Region.US }

    private val dispenseTxnDao: DispenseTxnDao = mockk(relaxed = true)
    private val stockBatchDao: StockBatchDao = mockk(relaxed = true)
    private val stockTxnDao: StockTxnDao = mockk(relaxed = true)

    private val auditLogger: AuditLogger = mockk(relaxed = true)

    private lateinit var repository: HistoryRepository

    @Before
    fun setup() {
        repository = HistoryRepository(dispenseTxnDao, stockBatchDao, stockTxnDao, regionProvider, auditLogger)
    }

    @Test
    fun `bulk dispense delete logs the deleted and unsynced ids`() = runTest {
        coEvery { dispenseTxnDao.deleteTransactionsByDateReturningSyncStates(any(), any(), any(), any()) } returns listOf(
            RecordSyncState(id = 1L, isSynced = true, isCompleted = true),
            RecordSyncState(id = 2L, isSynced = false, isCompleted = true),
            RecordSyncState(id = 3L, isSynced = null, isCompleted = false)
        )

        repository.deleteTransactionsForDate(today, today, null, 1L)

        verify {
            auditLogger.log(match {
                it is HistoryBulkDeleted && it.count == 3 && it.txnIds == listOf(1L, 2L, 3L) &&
                    it.unsyncedIds == listOf(2L) && it.statusFilter == "ALL"
            })
        }
    }

    @Test
    fun `bulk dispense delete with nothing matching logs nothing`() = runTest {
        coEvery { dispenseTxnDao.deleteTransactionsByDateReturningSyncStates(any(), any(), any(), any()) } returns emptyList()

        repository.deleteTransactionsForDate(today, today, null, 1L)

        verify(exactly = 0) { auditLogger.log(any()) }
    }

    @Test
    fun `bulk batch delete logs the batch ids and the unsynced ones`() = runTest {
        coEvery { stockBatchDao.getBatchIdsByDate(any(), any(), any(), any(), any(), any()) } returns listOf(5L, 6L)
        coEvery { stockBatchDao.getSyncStatesByIds(listOf(5L, 6L), any()) } returns listOf(
            RecordSyncState(id = 5L, isSynced = false, isCompleted = true),
            RecordSyncState(id = 6L, isSynced = true, isCompleted = true)
        )

        repository.deleteBatchesForDateRange(today, today, null, 1L)

        verify {
            auditLogger.log(match { it is BatchDeleted && it.batchIds == listOf(5L, 6L) && it.unsyncedIds == listOf(5L) })
        }
    }

    @After
    fun tearDown() { unmockkAll() }

    private val today: LocalDate = LocalDate.of(2024, 6, 15)

    // HIST_REPO_001
    @Test
    fun `getTransactionsForDateRange delegates to DAO with epoch millis and returns its flow`() = runTest {
        every { dispenseTxnDao.getTransactionsForDateRange(any(), any(), any(), any(), any()) } returns flowOf(emptyList())

        val flow = repository.getTransactionsForDateRange(today, today, 1L)

        flow.test {
            val list = awaitItem()
            assertTrue(list.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_REPO_003
    @Test
    fun `getBatchSummaries maps BatchSummaryDto to BatchSummary with correct field values`() = runTest {
        val dto = BatchSummaryDto(
            batchId = 10L, createdAt = 5000L, uniqueNdcCount = 3,
            status = "COMPLETED", bucketId = "BCK-01", requestIdFromPMS = "REQ-99"
        )
        every { stockBatchDao.getBatchSummaries(any(), any(), any()) } returns flowOf(listOf(dto))

        repository.getBatchSummaries(today, today, 1L).test {
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
        every { stockBatchDao.getBatchSummaries(any(), any(), any()) } returns flowOf(listOf(dto))

        repository.getBatchSummaries(today, today, 1L).test {
            val summary = awaitItem()[0]
            assertEquals(BatchStatus.INPROGRESS, summary.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // HIST_REPO_005
    @Test
    fun `deleteTransactionsForDate delegates to DAO`() = runTest {
        repository.deleteTransactionsForDate(today, today, null, 1L)

        coVerify(exactly = 1) { dispenseTxnDao.deleteTransactionsByDateReturningSyncStates(any(), any(), null, 1L) }
    }

    // HIST_REPO_006
    @Test
    fun `deleteBatchesForDateRange soft-deletes batches and their transactions when batch IDs exist`() = runTest {
        val batchIds = listOf(100L, 200L)
        coEvery { stockBatchDao.getBatchIdsByDate(any(), any(), any(), any(), any(), any()) } returns batchIds
        coJustRun { stockBatchDao.softDeleteBatchesByDate(any(), any(), any(), any(), any(), any()) }
        coJustRun { stockTxnDao.deleteByBatchIds(any()) }

        repository.deleteBatchesForDateRange(today, today, null, 1L)

        coVerify { stockBatchDao.softDeleteBatchesByDate(any(), any(), null, any(), any(), any()) }
        coVerify { stockTxnDao.deleteByBatchIds(batchIds) }
    }

    // HIST_REPO_007
    @Test
    fun `deleteBatchesForDateRange skips soft-delete when no batch IDs found`() = runTest {
        coEvery { stockBatchDao.getBatchIdsByDate(any(), any(), any(), any(), any(), any()) } returns emptyList()

        repository.deleteBatchesForDateRange(today, today, null, 1L)

        coVerify(exactly = 0) { stockBatchDao.softDeleteBatchesByDate(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { stockTxnDao.deleteByBatchIds(any()) }
    }

    // HIST_REPO_008
    @Test
    fun `deleteTransactionsForDate converts LocalDate to epoch millis with startMillis before endMillis`() = runTest {
        var capturedStart = 0L
        var capturedEnd = 0L
        coEvery {
            dispenseTxnDao.deleteTransactionsByDateReturningSyncStates(any(), any(), any(), any())
        } answers {
            capturedStart = firstArg()
            capturedEnd = secondArg()
            emptyList()
        }

        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 1, 31)
        repository.deleteTransactionsForDate(start, end, null, 1L)

        assertTrue("startMillis should be before endMillis", capturedStart < capturedEnd)
        assertTrue("startMillis should be positive", capturedStart > 0)
    }
}
