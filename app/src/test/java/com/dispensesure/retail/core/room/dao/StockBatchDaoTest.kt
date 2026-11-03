package com.dispensesure.retail.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.dtos.InventoryKpiCountsDto
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.tourguide.TutorialData
import kotlinx.coroutines.flow.first
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
class StockBatchDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var stockBatchDao: StockBatchDao
    private lateinit var stockTxnDao: StockTxnDao
    private lateinit var drugDao: DrugMasterDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        stockBatchDao = db.stockBatchDao()
        stockTxnDao = db.stockTxnDao()
        drugDao = db.drugMasterDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ───────────────────────── Insert ─────────────────────────

    @Test
    fun `insert returns generated row id for new batch`() = runTest {
        val id = stockBatchDao.insert(StockBatchEntity(batchId = 1L))
        assertEquals(1L, id)
    }

    @Test
    fun `insert ignores conflicting batchId and returns minus one`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, note = "first"))
        val secondResult = stockBatchDao.insert(StockBatchEntity(batchId = 1L, note = "second"))

        assertEquals(-1L, secondResult)
        val stored = stockBatchDao.getById(1L)
        assertEquals("first", stored?.note)
    }

    @Test
    fun `insertAll inserts multiple batches and returns their ids`() = runTest {
        val ids = stockBatchDao.insertAll(
            listOf(
                StockBatchEntity(batchId = 1L),
                StockBatchEntity(batchId = 2L),
                StockBatchEntity(batchId = 3L)
            )
        )
        assertEquals(3, ids.size)
        assertEquals(3, stockBatchDao.getActiveCount())
    }

    @Test
    fun `insertAll with empty list inserts nothing`() = runTest {
        val ids = stockBatchDao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
        assertEquals(0L, stockBatchDao.getActiveCount())
    }

    // ───────────────────────── Read ─────────────────────────

    @Test
    fun `getById returns null when batch does not exist`() = runTest {
        assertNull(stockBatchDao.getById(999L))
    }

    @Test
    fun `getById returns matching batch`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 5L, note = "hello"))
        val result = stockBatchDao.getById(5L)
        assertEquals(5L, result?.batchId)
        assertEquals("hello", result?.note)
    }

    @Test
    fun `observeById emits null then updated entity on change`() = runTest {
        stockBatchDao.observeById(10L).test {
            assertNull(awaitItem())
            stockBatchDao.insert(StockBatchEntity(batchId = 10L, note = "created"))
            assertEquals("created", awaitItem()?.note)
        }
    }

    @Test
    fun `getAllActive excludes soft deleted batches and orders newest first`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 200L))
        stockBatchDao.insert(StockBatchEntity(batchId = 3L, startDateTime = 300L))
        stockBatchDao.softDelete(2L)

        stockBatchDao.getAllActive().test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertEquals(listOf(3L, 1L), list.map { it.batchId })
        }
    }

    @Test
    fun `getAllByStatus filters by status`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, status = BatchStatus.COMPLETED))

        stockBatchDao.getAllByStatus(BatchStatus.COMPLETED).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(2L, list.first().batchId)
        }
    }

    @Test
    fun `getAllInProgress wrapper returns only in progress batches`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, status = BatchStatus.COMPLETED))

        stockBatchDao.getAllInProgress().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(1L, list.first().batchId)
        }
    }

    @Test
    fun `getLatestByStatus returns most recent match or null`() = runTest {
        assertNull(stockBatchDao.getLatestByStatus(BatchStatus.COMPLETED, Region.US))

        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.COMPLETED))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))

        val latest = stockBatchDao.getLatestByStatus(BatchStatus.COMPLETED, Region.US)
        assertEquals(2L, latest?.batchId)
    }

    @Test
    fun `getLatest wrapper delegates to in progress status`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))

        val latest = stockBatchDao.getLatest(Region.US)
        assertEquals(1L, latest?.batchId)
    }

    // ───────────────────────── Update / Delete ─────────────────────────

    @Test
    fun `softDelete marks batch as deleted and returns affected row count`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L))
        val affected = stockBatchDao.softDelete(1L)

        assertEquals(1, affected)
        assertTrue(stockBatchDao.getById(1L)!!.isDeleted)
    }

    @Test
    fun `softDelete on nonexistent batchId affects zero rows`() = runTest {
        val affected = stockBatchDao.softDelete(404L)
        assertEquals(0, affected)
    }

    @Test
    fun `markAsCompleted sets status and endDateTime`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        val affected = stockBatchDao.markAsCompleted(1L, endDateTime = 5000L)

        assertEquals(1, affected)
        val updated = stockBatchDao.getById(1L)!!
        assertEquals(BatchStatus.COMPLETED, updated.status)
        assertEquals(5000L, updated.endDateTime)
    }

    @Test
    fun `updateNote sets and clears note`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, note = "old"))
        stockBatchDao.updateNote(1L, "new")
        assertEquals("new", stockBatchDao.getById(1L)?.note)

        stockBatchDao.updateNote(1L, null)
        assertNull(stockBatchDao.getById(1L)?.note)
    }

    @Test
    fun `deleteAll removes every row from the table`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L))
        stockBatchDao.deleteAll()

        assertEquals(0L, stockBatchDao.getActiveCount())
        assertNull(stockBatchDao.getById(1L))
    }

    // ───────────────────────── Utility counts ─────────────────────────

    @Test
    fun `getActiveCount excludes soft deleted rows`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L))
        stockBatchDao.softDelete(2L)

        assertEquals(1L, stockBatchDao.getActiveCount())
    }

    // ───────────────────────── Date-range queries ─────────────────────────

    @Test
    fun `getBatchIdsByDate filters by range and null isCompleted matches any status`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))
        stockBatchDao.insert(StockBatchEntity(batchId = 3L, startDateTime = 500L, status = BatchStatus.COMPLETED))

        val ids = stockBatchDao.getBatchIdsByDate(start = 0L, end = 300L, isCompleted = null, region = Region.US)
        assertEquals(setOf(1L, 2L), ids.toSet())
    }

    @Test
    fun `getBatchIdsByDate with isCompleted true only matches completed status`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))

        val ids = stockBatchDao.getBatchIdsByDate(start = 0L, end = 300L, isCompleted = true, region = Region.US)
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun `getBatchIdsByDate with isCompleted false only matches in progress status`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 200L, status = BatchStatus.COMPLETED))

        val ids = stockBatchDao.getBatchIdsByDate(start = 0L, end = 300L, isCompleted = false, region = Region.US)
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `getBatchIdsByDate excludes rows outside the range and already deleted rows`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 50L))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 150L))
        stockBatchDao.softDelete(2L)

        val ids = stockBatchDao.getBatchIdsByDate(start = 100L, end = 200L, isCompleted = null, region = Region.US)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `softDeleteBatchesByDate marks only matching rows as deleted`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.COMPLETED))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 900L, status = BatchStatus.COMPLETED))

        stockBatchDao.softDeleteBatchesByDate(start = 0L, end = 300L, isCompleted = true, region = Region.US)

        assertTrue(stockBatchDao.getById(1L)!!.isDeleted)
        assertFalse(stockBatchDao.getById(2L)!!.isDeleted)
    }

    // ───────────────────────── Summary / join queries ─────────────────────────

    @Test
    fun `getBatchSummaries counts distinct non deleted drug ids per batch`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.INPROGRESS, bucketId = "B1"))
        val drug10 = db.drugMasterDao().insertIgnore(DrugMasterEntity(ndc = "NDC-10"))
        val drug20 = db.drugMasterDao().insertIgnore(DrugMasterEntity(ndc = "NDC-20"))
        val drug30 = db.drugMasterDao().insertIgnore(DrugMasterEntity(ndc = "NDC-30"))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 1L, drugId = drug10, batchId = 1L, isDeleted = false))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 2L, drugId = drug10, batchId = 1L, isDeleted = false))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 3L, drugId = drug20, batchId = 1L, isDeleted = false))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 4L, drugId = drug30, batchId = 1L, isDeleted = true))

        stockBatchDao.getBatchSummaries(0L, 200L, Region.US).test {
            val summaries = awaitItem()
            assertEquals(1, summaries.size)
            assertEquals(2, summaries.first().uniqueNdcCount)
            assertEquals("B1", summaries.first().bucketId)
        }
    }

    @Test
    fun `getBatchSummaries includes batches with no transactions at zero count`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L))

        stockBatchDao.getBatchSummaries(0L, 200L, Region.US).test {
            val summaries = awaitItem()
            assertEquals(1, summaries.size)
            assertEquals(0, summaries.first().uniqueNdcCount)
        }
    }

    @Test
    fun `observeUnsyncedCompletedBatches only returns completed unsynced batches`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, isSynced = false))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, status = BatchStatus.COMPLETED, isSynced = true))
        stockBatchDao.insert(StockBatchEntity(batchId = 3L, status = BatchStatus.INPROGRESS, isSynced = false))

        stockBatchDao.observeUnsyncedCompletedBatches(Region.US).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(1L, list.first().batchId)
        }
    }

    @Test
    fun `observeInProgressBatchSummaries only returns in progress batches`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, status = BatchStatus.COMPLETED))

        stockBatchDao.observeInProgressBatchSummaries(Region.US).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(1L, list.first().batchId)
        }
    }

    @Test
    fun `observeInProgressBatchKpiCounts splits pms and manual in progress batches`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.INPROGRESS, requestIdFromPMS = "pms-1"))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, status = BatchStatus.INPROGRESS, requestIdFromPMS = null))
        stockBatchDao.insert(StockBatchEntity(batchId = 3L, status = BatchStatus.INPROGRESS, requestIdFromPMS = "  "))
        stockBatchDao.insert(StockBatchEntity(batchId = 4L, status = BatchStatus.COMPLETED, requestIdFromPMS = "pms-2"))
        stockBatchDao.insert(StockBatchEntity(batchId = 5L, status = BatchStatus.INPROGRESS, requestIdFromPMS = "pms-3"))
        stockBatchDao.softDelete(5L)

        stockBatchDao.observeInProgressBatchKpiCounts(Region.US).test {
            assertEquals(InventoryKpiCountsDto(cycleCount = 1, pendingBatchCount = 2), awaitItem())
        }
    }

    @Test
    fun `observeInProgressBatchKpiCounts emits zeros when there are no batches`() = runTest {
        stockBatchDao.observeInProgressBatchKpiCounts(Region.US).test {
            assertEquals(InventoryKpiCountsDto(cycleCount = 0, pendingBatchCount = 0), awaitItem())
        }
    }

    @Test
    fun `getUnsyncedCompletedBatchesOnce returns snapshot without observing`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, isSynced = false))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, status = BatchStatus.INPROGRESS, isSynced = false))

        val result = stockBatchDao.getUnsyncedCompletedBatchesOnce()
        assertEquals(1, result.size)
        assertEquals(1L, result.first().batchId)
    }

    @Test
    fun `getUnsyncedCompletedBatchesOnce keeps a real batch that holds the demo drug`() = runTest {
        val tutorialDrugId = drugDao.insertIgnore(DrugMasterEntity(ndc = TutorialData.DEMO_NDC, drugName = "Tutorial Demo Drug"))
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, isSynced = false))
        stockTxnDao.insertIgnore(StockTxnEntity(batchId = 1L, drugId = tutorialDrugId))

        assertEquals(listOf(1L), stockBatchDao.getUnsyncedCompletedBatchesOnce().map { it.batchId })
    }

    @Test
    fun `tutorial bucket batches are hidden from unsynced and in-progress queries`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, isSynced = false, bucketId = TutorialData.BUCKET))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, status = BatchStatus.COMPLETED, isSynced = false, bucketId = "Normal"))
        stockBatchDao.insert(StockBatchEntity(batchId = 3L, status = BatchStatus.INPROGRESS, bucketId = TutorialData.BUCKET))

        assertEquals(listOf(2L), stockBatchDao.getUnsyncedCompletedBatchesOnce().map { it.batchId })
        stockBatchDao.observeInProgressBatchSummaries(Region.US).test {
            assertTrue(awaitItem().isEmpty())
        }
        stockBatchDao.observeInProgressBatchKpiCounts(Region.US).test {
            assertEquals(0, awaitItem().pendingBatchCount)
        }
    }

    @Test
    fun `getTutorialBatchIds finds tutorial bucket and demo-only batches, not real batches`() = runTest {
        val demoDrugId = drugDao.insertIgnore(DrugMasterEntity(ndc = TutorialData.DEMO_NDC, drugName = "Tutorial Demo Drug"))
        val realDrugId = drugDao.insertIgnore(DrugMasterEntity(ndc = "1234", drugName = "Aspirin"))
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, bucketId = TutorialData.BUCKET))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, bucketId = "Normal"))
        // Real bucket but only the demo drug: a demo batch whose insert raced the tour ending.
        stockBatchDao.insert(StockBatchEntity(batchId = 3L, bucketId = "Normal"))
        stockTxnDao.insertIgnore(StockTxnEntity(batchId = 3L, drugId = demoDrugId))
        // Real batch that also holds a real drug is never tutorial data.
        stockBatchDao.insert(StockBatchEntity(batchId = 4L, bucketId = "Normal"))
        stockTxnDao.insertIgnore(StockTxnEntity(batchId = 4L, drugId = demoDrugId))
        stockTxnDao.insertIgnore(StockTxnEntity(batchId = 4L, drugId = realDrugId))

        assertEquals(listOf(1L, 3L), stockBatchDao.getTutorialBatchIds().sorted())
    }

    @Test
    fun `markBatchSynced flips isSynced flag to true`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, isSynced = false))
        stockBatchDao.markBatchSynced(1L)
        assertTrue(stockBatchDao.getById(1L)!!.isSynced)
    }

    // ───────────────────────── Chunked sync progress ─────────────────────────

    @Test
    fun `setTotalChunks and markChunkAcked persist chunk progress`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L))
        stockBatchDao.setTotalChunks(1L, totalChunks = 5)
        stockBatchDao.markChunkAcked(1L, chunkIndex = 2)

        val updated = stockBatchDao.getById(1L)!!
        assertEquals(5, updated.totalChunks)
        assertEquals(2, updated.lastAckedChunkIndex)
    }

    @Test
    fun `getUnsyncedCompletedBatchCount reflects live count of unsynced completed batches`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, isSynced = false))

        stockBatchDao.getUnsyncedCompletedBatchCount(Region.US).test {
            assertEquals(1, awaitItem())
            stockBatchDao.markBatchSynced(1L)
            assertEquals(0, awaitItem())
        }
    }

    // ───────────────────────── region ─────────────────────────

    @Test
    fun `batch lists and counts only show the asked region`() = runTest {
        stockBatchDao.insertNewInProgressBatch(bucketId = null, region = Region.US)
        Thread.sleep(2) // batchId is the creation time
        stockBatchDao.insertNewInProgressBatch(bucketId = null, region = Region.CA)

        assertEquals(1, stockBatchDao.observeInProgressBatchSummaries(Region.CA).first().size)
        assertEquals(1, stockBatchDao.observeInProgressBatchSummaries(Region.US).first().size)
        assertEquals(1, stockBatchDao.observeInProgressBatchKpiCounts(Region.CA).first().pendingBatchCount)
        assertEquals(1, stockBatchDao.getBatchSummaries(0L, Long.MAX_VALUE, Region.CA).first().size)
        assertEquals(Region.CA, stockBatchDao.getLatest(Region.CA)?.region)
    }

    @Test
    fun `unsynced completed batches and their count only show the asked region`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, status = BatchStatus.COMPLETED, region = Region.US))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, status = BatchStatus.COMPLETED, region = Region.CA))

        assertEquals(listOf(2L), stockBatchDao.observeUnsyncedCompletedBatches(Region.CA).first().map { it.batchId })
        assertEquals(1, stockBatchDao.getUnsyncedCompletedBatchCount(Region.US).first())
        // HL7 resends every region's finished batches.
        assertEquals(setOf(1L, 2L), stockBatchDao.getUnsyncedCompletedBatchesOnce().map { it.batchId }.toSet())
    }

    @Test
    fun `deleting history by date only deletes the asked region`() = runTest {
        stockBatchDao.insert(StockBatchEntity(batchId = 1L, startDateTime = 100L, status = BatchStatus.COMPLETED, region = Region.US))
        stockBatchDao.insert(StockBatchEntity(batchId = 2L, startDateTime = 100L, status = BatchStatus.COMPLETED, region = Region.CA))

        assertEquals(listOf(2L), stockBatchDao.getBatchIdsByDate(0L, 200L, isCompleted = null, region = Region.CA))
        stockBatchDao.softDeleteBatchesByDate(0L, 200L, isCompleted = null, region = Region.CA)

        assertEquals(false, stockBatchDao.getById(1L)?.isDeleted)
        assertEquals(true, stockBatchDao.getById(2L)?.isDeleted)
    }
}
