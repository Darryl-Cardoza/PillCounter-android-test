package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.BottleInfoEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.StockTxnEntity
import kotlinx.coroutines.runBlocking
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
class BottleInfoDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BottleInfoDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.bottleInfoDao()

        // Bottle rows require a parent stock_txn row (FK), which itself FKs to a
        // drug_master row and a batch row — seed both so the insert doesn't hit a
        // foreign key constraint violation.
        runBlocking {
            // drugId is pinned to 100L (rather than left auto-generated) so that
            // seedJoinData()'s "stockTxnId=1 already inserted in setUp with drugId=100"
            // assumption — and the "Zeta" drug name it expects to join against — actually
            // matches the row backing stockTxnId=1 in the join-query tests below.
            db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 100L, drugName = "Zeta", ndc = "TEST-NDC"))
            db.batchDao().insert(BatchEntity(batchId = 1L))
            db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 1L, drugId = 100L, batchId = 1L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ───────────────────────── Insert ─────────────────────────

    @Test
    fun `insert returns generated row id for new bottle`() = runTest {
        val id = dao.insert(BottleInfoEntity(stockTxnId = 1L, lotNo = "L1", expNo = "12-2027"))
        assertTrue(id > 0L)
    }

    @Test
    fun `insert ignores conflicting bottleId and returns minus one`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 5L, stockTxnId = 1L, lotNo = "first"))
        val second = dao.insert(BottleInfoEntity(bottleId = 5L, stockTxnId = 1L, lotNo = "second"))

        assertEquals(-1L, second)
        assertEquals("first", dao.getById(5L)?.lotNo)
    }

    @Test
    fun `insertAll inserts multiple bottles and returns their ids`() = runTest {
        val ids = dao.insertAll(
            listOf(
                BottleInfoEntity(bottleId = 1L, stockTxnId = 1L),
                BottleInfoEntity(bottleId = 2L, stockTxnId = 1L),
                BottleInfoEntity(bottleId = 3L, stockTxnId = 1L)
            )
        )
        assertEquals(3, ids.size)
        assertTrue(ids.all { it > 0L })
    }

    @Test
    fun `insertAll with empty list inserts nothing`() = runTest {
        val ids = dao.insertAll(emptyList())
        assertTrue(ids.isEmpty())
    }

    // ───────────────────────── Read: getById ─────────────────────────

    @Test
    fun `getById returns null when bottle does not exist`() = runTest {
        assertNull(dao.getById(999L))
    }

    @Test
    fun `getById returns matching bottle`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 7L, stockTxnId = 1L, lotNo = "L7", bottleQty = 3))
        val result = dao.getById(7L)
        assertEquals(7L, result?.bottleId)
        assertEquals("L7", result?.lotNo)
        assertEquals(3, result?.bottleQty)
    }

    // ───────────────────────── findLine (null-tolerant matching) ─────────────────────────

    @Test
    fun `findLine matches exact lot and expiry`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, lotNo = "L1", expNo = "12-2027"))
        val found = dao.findLine(1L, "L1", "12-2027")
        assertEquals(1L, found?.bottleId)
    }

    @Test
    fun `findLine matches when both lotNo and expNo are null`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, lotNo = null, expNo = null))
        val found = dao.findLine(1L, null, null)
        assertEquals(1L, found?.bottleId)
    }

    @Test
    fun `findLine returns null when lot matches but expiry differs`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, lotNo = "L1", expNo = "12-2027"))
        val found = dao.findLine(1L, "L1", "01-2028")
        assertNull(found)
    }

    @Test
    fun `findLine returns null when stored lot is null but query lot is not`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, lotNo = null, expNo = null))
        val found = dao.findLine(1L, "L1", null)
        assertNull(found)
    }

    @Test
    fun `findLine returns null for different stockTxnId`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, lotNo = "L1", expNo = "12-2027"))
        val found = dao.findLine(2L, "L1", "12-2027")
        assertNull(found)
    }

    // ───────────────────────── incrementLooseQtyAndImages ─────────────────────────

    @Test
    fun `incrementLooseQtyAndImages adds to existing looseQty and updates timestamp`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, looseQty = 10, updatedAt = 0L))
        dao.incrementLooseQtyAndImages(1L, 5, paths = null, now = 12345L)

        val updated = dao.getById(1L)!!
        assertEquals(15, updated.looseQty)
        assertEquals(12345L, updated.updatedAt)
    }

    @Test
    fun `incrementLooseQtyAndImages treats null looseQty as zero`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, looseQty = null))
        dao.incrementLooseQtyAndImages(1L, 7, paths = null, now = 999L)

        assertEquals(7, dao.getById(1L)?.looseQty)
    }

    @Test
    fun `incrementLooseQtyAndImages on nonexistent bottle affects no rows`() = runTest {
        dao.incrementLooseQtyAndImages(999L, 5, paths = null, now = 1L)
        assertNull(dao.getById(999L))
    }

    @Test
    fun `incrementLooseQtyAndImages with paths overwrites controlledImagePaths`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, looseQty = 0, controlledImagePaths = null))
        dao.incrementLooseQtyAndImages(1L, 3, paths = listOf("/a", "/b"), now = 100L)

        val updated = dao.getById(1L)!!
        assertEquals(3, updated.looseQty)
        assertEquals(listOf("/a", "/b"), updated.controlledImagePaths)
    }

    @Test
    fun `incrementLooseQtyAndImages with null paths preserves existing controlledImagePaths`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, looseQty = 0, controlledImagePaths = listOf("/existing")))
        dao.incrementLooseQtyAndImages(1L, 2, paths = null, now = 200L)

        val updated = dao.getById(1L)!!
        assertEquals(2, updated.looseQty)
        assertEquals(listOf("/existing"), updated.controlledImagePaths)
    }

    // ───────────────────────── Delete ─────────────────────────

    @Test
    fun `delete removes only the targeted bottle`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L))
        dao.insert(BottleInfoEntity(bottleId = 2L, stockTxnId = 1L))

        dao.delete(1L)

        assertNull(dao.getById(1L))
        assertEquals(2L, dao.getById(2L)?.bottleId)
    }

    @Test
    fun `delete on nonexistent bottleId is a no-op`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L))
        dao.delete(404L)
        assertEquals(1L, dao.getById(1L)?.bottleId)
    }

    @Test
    fun `deleteAll removes every row from the table`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L))
        dao.insert(BottleInfoEntity(bottleId = 2L, stockTxnId = 1L))

        dao.deleteAll()

        assertNull(dao.getById(1L))
        assertNull(dao.getById(2L))
    }

    // ───────────────────────── Update ─────────────────────────

    @Test
    fun `update persists changed fields`() = runTest {
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, bottleQty = 1))
        dao.update(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, bottleQty = 9, lotNo = "changed"))

        val updated = dao.getById(1L)!!
        assertEquals(9, updated.bottleQty)
        assertEquals("changed", updated.lotNo)
    }

    // ───────────────────────── observeByBatchId / getByBatchId (join queries) ─────────────────────────

    private suspend fun seedJoinData() {
        db.batchDao().insert(BatchEntity(batchId = 1L))
        db.batchDao().insert(BatchEntity(batchId = 2L))
        db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 100L, drugName = "Zeta", ndc = "111", packageQty = 30))
        db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 200L, drugName = "Alpha", ndc = "222", packageQty = 60))

        // stockTxnId=1 already inserted in setUp with drugId=100, batchId=1
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 2L, drugId = 200L, batchId = 1L, isDeleted = false))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 3L, drugId = 100L, batchId = 1L, isDeleted = true))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 4L, drugId = 200L, batchId = 2L, isDeleted = false))
    }

    @Test
    fun `observeByBatchId projects joined fields and orders by drug name`() = runTest {
        seedJoinData()
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, batchId = 1L, lotNo = "L1", expNo = "E1", bottleQty = 2, looseQty = 3))
        dao.insert(BottleInfoEntity(bottleId = 2L, stockTxnId = 2L, batchId = 1L, lotNo = "L2", expNo = "E2", bottleQty = 4, looseQty = 5))

        dao.observeByBatchId(1L).test {
            val list = awaitItem()
            assertEquals(2, list.size)
            // "Alpha" (drugId 200) sorts before "Zeta" (drugId 100)
            assertEquals("Alpha", list[0].drugName)
            assertEquals(200L, list[0].drugId)
            assertEquals("222", list[0].ndc)
            assertEquals(60, list[0].packageQty)
            assertEquals("L2", list[0].lotNo)
            assertEquals("E2", list[0].expiry)
            assertEquals(4, list[0].bottleQty)
            assertEquals(5, list[0].looseQty)
            assertEquals(2L, list[0].txnId)

            assertEquals("Zeta", list[1].drugName)
        }
    }

    @Test
    fun `observeByBatchId excludes bottles whose stock_txn isDeleted`() = runTest {
        seedJoinData()
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 3L, batchId = 1L))

        dao.observeByBatchId(1L).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `observeByBatchId excludes bottles from other batches`() = runTest {
        seedJoinData()
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 4L, batchId = 2L))

        dao.observeByBatchId(1L).test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `observeByBatchId keeps drug fields null when drug is deleted from drug_master`() = runTest {
        db.batchDao().insert(BatchEntity(batchId = 1L))
        // stockTxnId=1 (seeded in setUp) references the drug row inserted there. Delete that
        // drug row now so the FK's ON DELETE SET NULL cascades stock_txn.drugId to null,
        // simulating "drug deleted from drug_master" for the LEFT JOIN under test.
        db.openHelper.writableDatabase.execSQL("DELETE FROM drug_master")
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, batchId = 1L, lotNo = "L1"))

        dao.observeByBatchId(1L).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertNull(list[0].drugName)
            assertNull(list[0].ndc)
        }
    }

    @Test
    fun `getByBatchId returns one-shot list equivalent to observeByBatchId`() = runTest {
        seedJoinData()
        dao.insert(BottleInfoEntity(bottleId = 1L, stockTxnId = 1L, batchId = 1L, lotNo = "L1"))
        dao.insert(BottleInfoEntity(bottleId = 2L, stockTxnId = 2L, batchId = 1L, lotNo = "L2"))

        val result = dao.getByBatchId(1L)
        assertEquals(2, result.size)
        assertEquals("Alpha", result[0].drugName)
        assertEquals("Zeta", result[1].drugName)
    }

    @Test
    fun `getByBatchId returns empty list for batch with no bottles`() = runTest {
        db.batchDao().insert(BatchEntity(batchId = 1L))
        val result = dao.getByBatchId(1L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getByBatchId populates imagePaths from controlledImagePaths column`() = runTest {
        seedJoinData()
        dao.insert(
            BottleInfoEntity(
                bottleId = 1L,
                stockTxnId = 1L,
                batchId = 1L,
                lotNo = "L1",
                controlledImagePaths = listOf("/a/img1.jpg", "/a/img2.jpg")
            )
        )

        val result = dao.getByBatchId(1L)
        assertEquals(1, result.size)
        assertEquals(listOf("/a/img1.jpg", "/a/img2.jpg"), result[0].imagePaths)
    }
}
