package com.dispensesure.retail.feature.stockCount.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.faceAuth.data.OperatorName
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.StockBottleInfoEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.room.models.enums.CountStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StockCountRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: StockCountRepository
    private val operatorNameProvider: OperatorNameProvider = mockk()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        coEvery { operatorNameProvider() } returns OperatorName("Jane", "Doe")
        repository = StockCountRepository(
            appDatabase = db,
            stockBatchDao = db.stockBatchDao(),
            stockTxnDao = db.stockTxnDao(),
            stockBottleInfoDao = db.stockBottleInfoDao(),
            operatorNameProvider = operatorNameProvider,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedDrugs() {
        db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 100L, drugName = "Aspirin", ndc = "111", packageQty = 30))
        db.drugMasterDao().insertIgnore(DrugMasterEntity(drugId = 200L, drugName = "Ibuprofen", ndc = "222", packageQty = 60))
    }

    private suspend fun commit(
        batchId: Long = 0L,
        bucketId: String? = "B2",
        drugId: Long = 100L,
        loosePills: Int = 12,
        controlledImagePaths: List<String>? = null,
    ) = repository.commitLooseCount(
        batchId = batchId,
        bucketId = bucketId,
        drugId = drugId,
        lotNumber = "L1",
        expirationDate = "12-31-2027",
        serialNumber = "S1",
        loosePills = loosePills,
        controlledImagePaths = controlledImagePaths,
    )

    // ───────────────────────── commitLooseCount ─────────────────────────

    @Test
    fun `a count with no batch creates one in the picked bucket`() = runTest {
        seedDrugs()

        val savedBatchId = commit(batchId = 0L, bucketId = "B2")

        val batch = db.stockBatchDao().getById(savedBatchId)!!
        assertEquals("B2", batch.bucketId)
        assertEquals(BatchStatus.INPROGRESS, batch.status)
        assertEquals(1, batch.totalNdcs)
        assertEquals("Jane Doe", batch.userName)
        val header = db.stockTxnDao().findByDrugInBatch(savedBatchId, 100L)!!
        assertEquals("B2", header.bucketId)
        assertEquals(CountStatus.COMPLETED, header.status)
    }

    @Test
    fun `the saved line holds only loose pills and the scanned bottle`() = runTest {
        seedDrugs()

        val savedBatchId = commit(loosePills = 12)

        val header = db.stockTxnDao().findByDrugInBatch(savedBatchId, 100L)!!
        val savedLine = db.stockBottleInfoDao().getByBatchId(savedBatchId).single()
        val lineEntity = db.stockBottleInfoDao().getById(savedLine.bottleId)!!
        assertEquals(header.txnId, lineEntity.stockTxnId)
        assertEquals(0, lineEntity.bottleQty)
        assertEquals(12, lineEntity.looseQty)
        assertEquals("L1", lineEntity.lotNo)
        assertEquals("12-31-2027", lineEntity.expNo)
        assertEquals("S1", lineEntity.serialNo)
        assertNull(lineEntity.controlledImagePaths)
    }

    @Test
    fun `a count for a known batch is saved into it`() = runTest {
        seedDrugs()
        db.stockBatchDao().insert(StockBatchEntity(batchId = 5L, bucketId = "B5"))

        val savedBatchId = commit(batchId = 5L)

        assertEquals(5L, savedBatchId)
        assertEquals(1L, db.stockBatchDao().getActiveCount())
    }

    @Test
    fun `a drug already in the batch gets a new line on its header`() = runTest {
        seedDrugs()
        db.stockBatchDao().insert(StockBatchEntity(batchId = 5L))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 3L, drugId = 100L, batchId = 5L, status = CountStatus.PARTIAL))
        db.stockBottleInfoDao().insert(StockBottleInfoEntity(stockTxnId = 3L, batchId = 5L, bottleQty = 2))

        commit(batchId = 5L, drugId = 100L)

        assertEquals(1, db.stockTxnDao().getByBatchId(5L).size)
        val savedLines = db.stockBottleInfoDao().getByBatchId(5L)
        assertEquals(2, savedLines.size)
        val looseLine = db.stockBottleInfoDao().getById(savedLines.first { line -> line.looseQty == 12 }.bottleId)!!
        assertEquals(3L, looseLine.stockTxnId)
        assertEquals(CountStatus.COMPLETED, db.stockTxnDao().getById(3L)?.status)
    }

    @Test
    fun `a drug new to the batch gets its own header`() = runTest {
        seedDrugs()
        db.stockBatchDao().insert(StockBatchEntity(batchId = 5L))
        db.stockTxnDao().insertIgnore(StockTxnEntity(txnId = 3L, drugId = 200L, batchId = 5L))

        commit(batchId = 5L, drugId = 100L, bucketId = "B2")

        assertEquals(2, db.stockTxnDao().getByBatchId(5L).size)
        val newHeader = db.stockTxnDao().findByDrugInBatch(5L, 100L)!!
        assertEquals("B2", newHeader.bucketId)
        assertEquals(CountStatus.COMPLETED, newHeader.status)
        assertEquals(2, db.stockBatchDao().getById(5L)?.totalNdcs)
    }

    @Test
    fun `controlled photo paths are saved on the line`() = runTest {
        seedDrugs()

        val savedBatchId = commit(controlledImagePaths = listOf("/photos/1.jpg", "/photos/2.jpg"))

        val savedLine = db.stockBottleInfoDao().getByBatchId(savedBatchId).single()
        assertEquals(
            listOf("/photos/1.jpg", "/photos/2.jpg"),
            db.stockBottleInfoDao().getById(savedLine.bottleId)?.controlledImagePaths,
        )
    }

    @Test
    fun `nothing is kept when a write fails`() = runTest {
        seedDrugs()

        // Drug 999 is not in drug_master, so the header insert breaks its foreign key.
        val failure = runCatching { commit(batchId = 0L, drugId = 999L) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(0L, db.stockBatchDao().getActiveCount())
    }

    // ───────────────────────── observeCanReset ─────────────────────────

    @Test
    fun `reset is open for a count with no batch`() = runTest {
        assertTrue(repository.observeCanReset(0L).first())
    }

    @Test
    fun `reset is open while the batch is unsynced`() = runTest {
        db.stockBatchDao().insert(StockBatchEntity(batchId = 9L, isSynced = false, lastAckedChunkIndex = 0))

        assertTrue(repository.observeCanReset(9L).first())
    }

    @Test
    fun `reset closes once the batch has synced`() = runTest {
        db.stockBatchDao().insert(StockBatchEntity(batchId = 9L, isSynced = true))

        assertFalse(repository.observeCanReset(9L).first())
    }

    @Test
    fun `reset closes once the batch has acked a chunk`() = runTest {
        db.stockBatchDao().insert(StockBatchEntity(batchId = 9L, isSynced = false, lastAckedChunkIndex = 2))

        assertFalse(repository.observeCanReset(9L).first())
    }

    // ───────────────────────── deletePhotos ─────────────────────────

    @Test
    fun `deletePhotos removes the files and skips missing ones`() {
        val countPhoto = temporaryFolder.newFile("count.jpg")

        repository.deletePhotos(listOf("${temporaryFolder.root.absolutePath}/missing.jpg", countPhoto.absolutePath))

        assertFalse(countPhoto.exists())
    }
}
