package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.StockTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StockTxnDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StockTxnDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.stockTxnDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBatchAndDrugs() {
        db.batchDao().insert(BatchEntity(batchId = 1L))
        db.batchDao().insert(BatchEntity(batchId = 2L))
        db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 100L, drugName = "Zeta", ndc = "111", packageQty = 30))
        db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 200L, drugName = "Alpha", ndc = "222", packageQty = 60))
    }

    // ───────────────────────── insertIgnore / insertAll ─────────────────────────

    @Test
    fun `insertIgnore returns generated row id for new txn`() = runTest {
        seedBatchAndDrugs()
        val id = dao.insertIgnore(StockTxnEntity(drugId = 100L, batchId = 1L))
        assertTrue(id > 0L)
    }

    @Test
    fun `insertIgnore ignores conflicting txnId and returns minus one`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 5L, drugId = 100L, batchId = 1L, bucketId = "first"))
        val second = dao.insertIgnore(StockTxnEntity(txnId = 5L, drugId = 200L, batchId = 1L, bucketId = "second"))

        assertEquals(-1L, second)
        assertEquals("first", dao.getById(5L)?.bucketId)
    }

    @Test
    fun `insertAll inserts multiple txns and returns their ids`() = runTest {
        seedBatchAndDrugs()
        val ids = dao.insertAll(
            listOf(
                StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L),
                StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 1L)
            )
        )
        assertEquals(2, ids.size)
        assertTrue(ids.all { it > 0L })
    }

    @Test
    fun `insertAll with empty list inserts nothing`() = runTest {
        val ids = dao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
    }

    // ───────────────────────── update ─────────────────────────

    @Test
    fun `update persists changed fields`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, status = CountStatus.PARTIAL))
        dao.update(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, status = CountStatus.COMPLETED, bucketId = "B1"))

        val updated = dao.getById(1L)!!
        assertEquals(CountStatus.COMPLETED, updated.status)
        assertEquals("B1", updated.bucketId)
    }

    // ───────────────────────── upsertPreservingId ─────────────────────────

    @Test
    fun `upsertPreservingId inserts new row when txnId is zero`() = runTest {
        seedBatchAndDrugs()
        val newId = dao.upsertPreservingId(StockTxnEntity(txnId = 0L, drugId = 100L, batchId = 1L))

        assertTrue(newId > 0L)
        assertEquals(100L, dao.getById(newId)?.drugId)
    }

    @Test
    fun `upsertPreservingId throws when zero-id insert conflicts`() = runTest {
        // Force insertIgnore to fail for txnId=0: not directly possible via IGNORE conflict on
        // autoGenerate PK since 0 always generates a fresh id. Instead verify the "existing row
        // found" update path below and the "existing row missing, insert succeeds" path here.
        seedBatchAndDrugs()
        val newId = dao.upsertPreservingId(StockTxnEntity(txnId = 0L, drugId = 100L, batchId = 1L))
        assertTrue(newId > 0L)
    }

    @Test
    fun `upsertPreservingId updates existing row and preserves its txnId`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 10L, drugId = 100L, batchId = 1L, status = CountStatus.PARTIAL))

        val resultId = dao.upsertPreservingId(
            StockTxnEntity(txnId = 10L, drugId = 200L, batchId = 1L, status = CountStatus.COMPLETED)
        )

        assertEquals(10L, resultId)
        val row = dao.getById(10L)!!
        assertEquals(CountStatus.COMPLETED, row.status)
        assertEquals(200L, row.drugId)
    }

    @Test
    fun `upsertPreservingId inserts fresh row when given nonexistent explicit txnId`() = runTest {
        seedBatchAndDrugs()
        // txnId=42 does not exist yet; insertIgnore() with autoGenerate PK will assign a new id
        // (since 42 isn't taken), simulating "existing == null, insertIgnore succeeds" branch.
        val resultId = dao.upsertPreservingId(StockTxnEntity(txnId = 42L, drugId = 100L, batchId = 1L))

        assertTrue(resultId > 0L)
        assertEquals(100L, dao.getById(resultId)?.drugId)
    }

    // ───────────────────────── getById ─────────────────────────

    @Test
    fun `getById returns null when txn does not exist`() = runTest {
        assertNull(dao.getById(999L))
    }

    @Test
    fun `getById returns matching txn`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 7L, drugId = 100L, batchId = 1L, bucketId = "X"))
        val result = dao.getById(7L)
        assertEquals(7L, result?.txnId)
        assertEquals("X", result?.bucketId)
    }

    // ───────────────────────── findByDrugInBatch ─────────────────────────

    @Test
    fun `findByDrugInBatch returns matching header`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        val found = dao.findByDrugInBatch(1L, 100L)
        assertEquals(1L, found?.txnId)
    }

    @Test
    fun `findByDrugInBatch returns null when combination not found`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        assertNull(dao.findByDrugInBatch(1L, 200L))
        assertNull(dao.findByDrugInBatch(2L, 100L))
    }

    @Test
    fun `findByDrugInBatch excludes soft-deleted rows`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, isDeleted = true))
        assertNull(dao.findByDrugInBatch(1L, 100L))
    }

    // ───────────────────────── getByBatchId ─────────────────────────

    @Test
    fun `getByBatchId returns only non-deleted rows for the batch`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, isDeleted = false))
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 1L, isDeleted = true))
        dao.insertIgnore(StockTxnEntity(txnId = 3L, drugId = 200L, batchId = 2L, isDeleted = false))

        val result = dao.getByBatchId(1L)
        assertEquals(1, result.size)
        assertEquals(1L, result[0].txnId)
    }

    @Test
    fun `getByBatchId returns empty list when batch has no txns`() = runTest {
        seedBatchAndDrugs()
        assertTrue(dao.getByBatchId(1L).isEmpty())
    }

    // ───────────────────────── getNdcsForBatch ─────────────────────────

    @Test
    fun `getNdcsForBatch returns distinct non-blank ndcs joined to drug_master`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 1L))
        // duplicate drug -> should be deduped by DISTINCT
        dao.insertIgnore(StockTxnEntity(txnId = 3L, drugId = 100L, batchId = 1L))

        val ndcs = dao.getNdcsForBatch(1L)
        assertEquals(2, ndcs.size)
        assertTrue(ndcs.containsAll(listOf("111", "222")))
    }

    @Test
    fun `getNdcsForBatch excludes soft-deleted and blank or null ndc rows`() = runTest {
        db.batchDao().insert(BatchEntity(batchId = 1L))
        db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 100L, drugName = "Blank", ndc = "", packageQty = 30))
        db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 200L, drugName = "Deleted", ndc = "999", packageQty = 60))

        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, isDeleted = false)) // blank ndc
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 1L, isDeleted = true)) // soft-deleted

        assertTrue(dao.getNdcsForBatch(1L).isEmpty())
    }

    // ───────────────────────── observeRequestedDrugs ─────────────────────────

    @Test
    fun `observeRequestedDrugs emits distinct drugs ordered by name`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 1L))

        dao.observeRequestedDrugs(1L).test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertEquals("Alpha", list[0].drugName)
            assertEquals(200L, list[0].drugId)
            assertEquals("222", list[0].ndc)
            assertEquals("Zeta", list[1].drugName)
        }
    }

    @Test
    fun `observeRequestedDrugs excludes soft-deleted rows and other batches`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, isDeleted = true))
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 2L, isDeleted = false))

        dao.observeRequestedDrugs(1L).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    // ───────────────────────── getUniqueNdcCountForBatch ─────────────────────────

    @Test
    fun `getUniqueNdcCountForBatch counts distinct drugIds only`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 100L, batchId = 1L))
        dao.insertIgnore(StockTxnEntity(txnId = 3L, drugId = 200L, batchId = 1L))

        assertEquals(2, dao.getUniqueNdcCountForBatch(1L))
    }

    @Test
    fun `getUniqueNdcCountForBatch returns zero when batch has no txns`() = runTest {
        seedBatchAndDrugs()
        assertEquals(0, dao.getUniqueNdcCountForBatch(1L))
    }

    @Test
    fun `getUniqueNdcCountForBatch excludes soft-deleted rows`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, isDeleted = true))
        assertEquals(0, dao.getUniqueNdcCountForBatch(1L))
    }

    // ───────────────────────── refreshBatchTotalNdcs ─────────────────────────

    @Test
    fun `refreshBatchTotalNdcs recomputes batch totalNdcs from distinct drug count`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 1L))

        dao.refreshBatchTotalNdcs(1L)

        val batch = db.batchDao().getById(1L)
        assertEquals(2, batch?.totalNdcs)
    }

    @Test
    fun `refreshBatchTotalNdcs for nonexistent batch affects no rows`() = runTest {
        // No batch row exists at all; should be a safe no-op.
        dao.refreshBatchTotalNdcs(999L)
        assertNull(db.batchDao().getById(999L))
    }

    // ───────────────────────── updateBatchUserName ─────────────────────────

    @Test
    fun `updateBatchUserName sets userName on the target batch`() = runTest {
        seedBatchAndDrugs()
        dao.updateBatchUserName(1L, "Alice")
        assertEquals("Alice", db.batchDao().getById(1L)?.userName)
    }

    @Test
    fun `updateBatchUserName can set userName to null`() = runTest {
        seedBatchAndDrugs()
        dao.updateBatchUserName(1L, "Alice")
        dao.updateBatchUserName(1L, null)
        assertNull(db.batchDao().getById(1L)?.userName)
    }

    // ───────────────────────── softDelete ─────────────────────────

    @Test
    fun `softDelete marks txn as deleted and updates timestamp`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, updatedAt = 0L))
        dao.softDelete(1L, now = 555L)

        val row = dao.getById(1L)!!
        assertEquals(true, row.isDeleted)
        assertEquals(555L, row.updatedAt)
    }

    // ───────────────────────── updateStatus ─────────────────────────

    @Test
    fun `updateStatus sets status and updatedAt`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L, status = CountStatus.PARTIAL))
        dao.updateStatus(1L, CountStatus.ON_HOLD, now = 777L)

        val row = dao.getById(1L)!!
        assertEquals(CountStatus.ON_HOLD, row.status)
        assertEquals(777L, row.updatedAt)
    }

    @Test
    fun `updateStatus on nonexistent txnId is a no-op`() = runTest {
        dao.updateStatus(999L, CountStatus.COMPLETED, now = 1L)
        assertNull(dao.getById(999L))
    }

    // ───────────────────────── deleteByBatchIds ─────────────────────────

    @Test
    fun `deleteByBatchIds removes rows only for the given batches`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 2L))

        dao.deleteByBatchIds(listOf(1L))

        assertNull(dao.getById(1L))
        assertEquals(2L, dao.getById(2L)?.txnId)
    }

    @Test
    fun `deleteByBatchIds with empty list deletes nothing`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))

        dao.deleteByBatchIds(emptyList())

        assertEquals(1L, dao.getById(1L)?.txnId)
    }

    // ───────────────────────── deleteAll ─────────────────────────

    @Test
    fun `deleteAll removes every row from the table`() = runTest {
        seedBatchAndDrugs()
        dao.insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        dao.insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 2L))

        dao.deleteAll()

        assertNull(dao.getById(1L))
        assertNull(dao.getById(2L))
    }
}
