package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.StockTxnEntity
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BatchDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.batchDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ───────────────────────── Insert ─────────────────────────

    @Test
    fun `insert returns generated row id for new batch`() = runTest {
        val id = dao.insert(BatchEntity(batchId = 1L))
        assertEquals(1L, id)
    }

    @Test
    fun `insert ignores conflicting batchId and returns minus one`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, note = "first"))
        val secondResult = dao.insert(BatchEntity(batchId = 1L, note = "second"))

        assertEquals(-1L, secondResult)
        val stored = dao.getById(1L)
        assertEquals("first", stored?.note)
    }

    @Test
    fun `insertAll inserts multiple batches and returns their ids`() = runTest {
        val ids = dao.insertAll(
            listOf(
                BatchEntity(batchId = 1L),
                BatchEntity(batchId = 2L),
                BatchEntity(batchId = 3L)
            )
        )
        assertEquals(3, ids.size)
        assertEquals(3, dao.getActiveCount())
    }

    @Test
    fun `insertAll with empty list inserts nothing`() = runTest {
        val ids = dao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
        assertEquals(0L, dao.getActiveCount())
    }

    // ───────────────────────── Read ─────────────────────────

    @Test
    fun `getById returns null when batch does not exist`() = runTest {
        assertNull(dao.getById(999L))
    }

    @Test
    fun `getById returns matching batch`() = runTest {
        dao.insert(BatchEntity(batchId = 5L, note = "hello"))
        val result = dao.getById(5L)
        assertEquals(5L, result?.batchId)
        assertEquals("hello", result?.note)
    }

    @Test
    fun `observeById emits null then updated entity on change`() = runTest {
        dao.observeById(10L).test {
            assertNull(awaitItem())
            dao.insert(BatchEntity(batchId = 10L, note = "created"))
            assertEquals("created", awaitItem()?.note)
        }
    }

    @Test
    fun `getAllActive excludes soft deleted batches and orders newest first`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L))
        dao.insert(BatchEntity(batchId = 2L, startDateTime = 200L))
        dao.insert(BatchEntity(batchId = 3L, startDateTime = 300L))
        dao.softDelete(2L)

        dao.getAllActive().test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertEquals(listOf(3L, 1L), list.map { it.batchId })
        }
    }

    @Test
    fun `getAllByStatus filters by status`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        dao.insert(BatchEntity(batchId = 2L, status = BatchStatus.COMPLETED))

        dao.getAllByStatus(BatchStatus.COMPLETED).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(2L, list.first().batchId)
        }
    }

    @Test
    fun `getAllInProgress wrapper returns only in progress batches`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        dao.insert(BatchEntity(batchId = 2L, status = BatchStatus.COMPLETED))

        dao.getAllInProgress().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(1L, list.first().batchId)
        }
    }

    @Test
    fun `getLatestByStatus returns most recent match or null`() = runTest {
        assertNull(dao.getLatestByStatus(BatchStatus.COMPLETED))

        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.COMPLETED))
        dao.insert(BatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))

        val latest = dao.getLatestByStatus(BatchStatus.COMPLETED)
        assertEquals(2L, latest?.batchId)
    }

    @Test
    fun `getLatest wrapper delegates to in progress status`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS))
        dao.insert(BatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))

        val latest = dao.getLatest()
        assertEquals(1L, latest?.batchId)
    }

    // ───────────────────────── Update / Delete ─────────────────────────

    @Test
    fun `softDelete marks batch as deleted and returns affected row count`() = runTest {
        dao.insert(BatchEntity(batchId = 1L))
        val affected = dao.softDelete(1L)

        assertEquals(1, affected)
        assertTrue(dao.getById(1L)!!.isDeleted)
    }

    @Test
    fun `softDelete on nonexistent batchId affects zero rows`() = runTest {
        val affected = dao.softDelete(404L)
        assertEquals(0, affected)
    }

    @Test
    fun `markAsCompleted sets status and endDateTime`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        val affected = dao.markAsCompleted(1L, endDateTime = 5000L)

        assertEquals(1, affected)
        val updated = dao.getById(1L)!!
        assertEquals(BatchStatus.COMPLETED, updated.status)
        assertEquals(5000L, updated.endDateTime)
    }

    @Test
    fun `updateNote sets and clears note`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, note = "old"))
        dao.updateNote(1L, "new")
        assertEquals("new", dao.getById(1L)?.note)

        dao.updateNote(1L, null)
        assertNull(dao.getById(1L)?.note)
    }

    @Test
    fun `deleteAll removes every row from the table`() = runTest {
        dao.insert(BatchEntity(batchId = 1L))
        dao.insert(BatchEntity(batchId = 2L))
        dao.deleteAll()

        assertEquals(0L, dao.getActiveCount())
        assertNull(dao.getById(1L))
    }

    // ───────────────────────── Utility counts ─────────────────────────

    @Test
    fun `getActiveCount excludes soft deleted rows`() = runTest {
        dao.insert(BatchEntity(batchId = 1L))
        dao.insert(BatchEntity(batchId = 2L))
        dao.softDelete(2L)

        assertEquals(1L, dao.getActiveCount())
    }

    @Test
    fun `observeCountByStatus emits live updates as status changes`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))

        dao.observeCountByStatus(BatchStatus.COMPLETED).test {
            assertEquals(0, awaitItem())
            dao.markAsCompleted(1L)
            assertEquals(1, awaitItem())
        }
    }

    @Test
    fun `observeActiveInProgressCount wrapper reflects in progress rows`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        dao.insert(BatchEntity(batchId = 2L, status = BatchStatus.COMPLETED))

        dao.observeActiveInProgressCount().test {
            assertEquals(1, awaitItem())
        }
    }

    @Test
    fun `observeCompletedBatchCount wrapper reflects completed rows`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.COMPLETED))
        dao.insert(BatchEntity(batchId = 2L, status = BatchStatus.COMPLETED))
        dao.insert(BatchEntity(batchId = 3L, status = BatchStatus.INPROGRESS))

        dao.observeCompletedBatchCount().test {
            assertEquals(2, awaitItem())
        }
    }

    // ───────────────────────── Date-range queries ─────────────────────────

    @Test
    fun `getBatchIdsByDate filters by range and null isCompleted matches any status`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS))
        dao.insert(BatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))
        dao.insert(BatchEntity(batchId = 3L, startDateTime = 500L, status = BatchStatus.COMPLETED))

        val ids = dao.getBatchIdsByDate(start = 0L, end = 300L, isCompleted = null)
        assertEquals(setOf(1L, 2L), ids.toSet())
    }

    @Test
    fun `getBatchIdsByDate with isCompleted true only matches completed status`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS))
        dao.insert(BatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))

        val ids = dao.getBatchIdsByDate(start = 0L, end = 300L, isCompleted = true)
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun `getBatchIdsByDate with isCompleted false only matches in progress status`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS))
        dao.insert(BatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))

        val ids = dao.getBatchIdsByDate(start = 0L, end = 300L, isCompleted = false)
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `getBatchIdsByDate excludes rows outside the range and already deleted rows`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 50L))
        dao.insert(BatchEntity(batchId = 2L, startDateTime = 150L))
        dao.softDelete(2L)

        val ids = dao.getBatchIdsByDate(start = 100L, end = 200L, isCompleted = null)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `softDeleteBatchesByDate marks only matching rows as deleted`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.COMPLETED))
        dao.insert(BatchEntity(batchId = 2L, startDateTime = 900L, status = BatchStatus.COMPLETED))

        dao.softDeleteBatchesByDate(start = 0L, end = 300L, isCompleted = true)

        assertTrue(dao.getById(1L)!!.isDeleted)
        assertFalse(dao.getById(2L)!!.isDeleted)
    }

    // ───────────────────────── Summary / join queries ─────────────────────────

    @Test
    fun `getBatchSummaries counts distinct non deleted drug ids per batch`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS, bucketId = "B1"))
        val drug10 = db.drugMasterDao().insertIgnore(DrugMasterEntity(ndc = "NDC-10"))
        val drug20 = db.drugMasterDao().insertIgnore(DrugMasterEntity(ndc = "NDC-20"))
        val drug30 = db.drugMasterDao().insertIgnore(DrugMasterEntity(ndc = "NDC-30"))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 1L, drugId = drug10, batchId = 1L, isDeleted = false))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 2L, drugId = drug10, batchId = 1L, isDeleted = false))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 3L, drugId = drug20, batchId = 1L, isDeleted = false))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 4L, drugId = drug30, batchId = 1L, isDeleted = true))

        dao.getBatchSummaries(0L, 200L).test {
            val summaries = awaitItem()
            assertEquals(1, summaries.size)
            assertEquals(2, summaries.first().uniqueNdcCount)
            assertEquals("B1", summaries.first().bucketId)
        }
    }

    @Test
    fun `getBatchSummaries includes batches with no transactions at zero count`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, startDateTime = 100L))

        dao.getBatchSummaries(0L, 200L).test {
            val summaries = awaitItem()
            assertEquals(1, summaries.size)
            assertEquals(0, summaries.first().uniqueNdcCount)
        }
    }

    @Test
    fun `observeUnsyncedCompletedBatches only returns completed unsynced batches`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, isSynced = false))
        dao.insert(BatchEntity(batchId = 2L, status = BatchStatus.COMPLETED, isSynced = true))
        dao.insert(BatchEntity(batchId = 3L, status = BatchStatus.INPROGRESS, isSynced = false))

        dao.observeUnsyncedCompletedBatches().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(1L, list.first().batchId)
        }
    }

    @Test
    fun `observeInProgressBatchSummaries only returns in progress batches`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        dao.insert(BatchEntity(batchId = 2L, status = BatchStatus.COMPLETED))

        dao.observeInProgressBatchSummaries().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(1L, list.first().batchId)
        }
    }

    @Test
    fun `getUnsyncedCompletedBatchesOnce returns snapshot without observing`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, isSynced = false))
        dao.insert(BatchEntity(batchId = 2L, status = BatchStatus.INPROGRESS, isSynced = false))

        val result = dao.getUnsyncedCompletedBatchesOnce()
        assertEquals(1, result.size)
        assertEquals(1L, result.first().batchId)
    }

    @Test
    fun `markBatchSynced flips isSynced flag to true`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, isSynced = false))
        dao.markBatchSynced(1L)
        assertTrue(dao.getById(1L)!!.isSynced)
    }

    // ───────────────────────── Chunked sync progress ─────────────────────────

    @Test
    fun `setTotalChunks and markChunkAcked persist chunk progress`() = runTest {
        dao.insert(BatchEntity(batchId = 1L))
        dao.setTotalChunks(1L, totalChunks = 5)
        dao.markChunkAcked(1L, chunkIndex = 2)

        val updated = dao.getById(1L)!!
        assertEquals(5, updated.totalChunks)
        assertEquals(2, updated.lastAckedChunkIndex)
    }

    @Test
    fun `getUnsyncedCompletedBatchCount reflects live count of unsynced completed batches`() = runTest {
        dao.insert(BatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, isSynced = false))

        dao.getUnsyncedCompletedBatchCount().test {
            assertEquals(1, awaitItem())
            dao.markBatchSynced(1L)
            assertEquals(0, awaitItem())
        }
    }
}
