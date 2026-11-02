package com.dispensesure.retail.core.tourguide

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TutorialDataCleanerTest {

    private lateinit var db: AppDatabase
    private lateinit var cleaner: TutorialDataCleaner

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        cleaner = TutorialDataCleaner(
            ApplicationProvider.getApplicationContext(), db, db.dispenseTxnDao(), db.stockBatchDao(), db.stockTxnDao(), db.stockBottleInfoDao(), db.drugMasterDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `purge removes tutorial rows and keeps real ones`() = runTest {
        val demoDrugId = db.drugMasterDao().insertIgnore(
            DrugMasterEntity(ndc = TutorialData.DEMO_NDC, drugName = "Tutorial Demo Drug"),
        )
        val realDrugId = db.drugMasterDao().insertIgnore(DrugMasterEntity(ndc = "1234", drugName = "Aspirin"))

        val demoTxn = db.dispenseTxnDao().insertIgnore(DispenseTxnEntity(drugId = demoDrugId, rxNo = "${TutorialData.RX_PREFIX}1", status = CountStatus.PARTIAL))
        val realTxn = db.dispenseTxnDao().insertIgnore(DispenseTxnEntity(drugId = realDrugId, rxNo = "RX1", status = CountStatus.PARTIAL))

        db.stockBatchDao().insert(StockBatchEntity(batchId = 10L, bucketId = TutorialData.BUCKET))
        db.stockTxnDao().insertIgnore(StockTxnEntity(batchId = 10L, drugId = demoDrugId))
        db.stockBatchDao().insert(StockBatchEntity(batchId = 11L, bucketId = "Normal"))
        db.stockTxnDao().insertIgnore(StockTxnEntity(batchId = 11L, drugId = realDrugId))

        cleaner.purge()

        assertNull(db.dispenseTxnDao().getById(demoTxn))
        assertNotNull(db.dispenseTxnDao().getById(realTxn))
        assertNull(db.stockBatchDao().getById(10L))
        assertNotNull(db.stockBatchDao().getById(11L))
        assertNull(db.drugMasterDao().getDrugByNdc(TutorialData.DEMO_NDC))
        assertNotNull(db.drugMasterDao().getDrugByNdc("1234"))
        assertNotNull(db.stockTxnDao().findByDrugInBatch(11L, realDrugId))
    }

    @Test
    fun `purge removes a tutorial txn whose demo drug is already gone and an empty tutorial batch`() = runTest {
        val txn = db.dispenseTxnDao().insertIgnore(DispenseTxnEntity(drugId = null, rxNo = "${TutorialData.RX_PREFIX}2", status = CountStatus.PARTIAL))
        db.stockBatchDao().insert(StockBatchEntity(batchId = 20L, bucketId = TutorialData.BUCKET))

        cleaner.purge()

        assertNull(db.dispenseTxnDao().getById(txn))
        assertNull(db.stockBatchDao().getById(20L))
    }

    @Test
    fun `purge is idempotent`() = runTest {
        db.stockBatchDao().insert(StockBatchEntity(batchId = 30L, bucketId = TutorialData.BUCKET))
        cleaner.purge()
        cleaner.purge()
        assertNull(db.stockBatchDao().getById(30L))
    }

    @Test
    fun `purge deletes the image files of tutorial rows`() = runTest {
        val file = File.createTempFile("tutorial_img", ".jpg")
        val txn = db.dispenseTxnDao().insertIgnore(DispenseTxnEntity(drugId = null, rxNo = "${TutorialData.RX_PREFIX}3", status = CountStatus.PARTIAL))
        db.dispenseCountDao().insert(DispenseCountEntity(txnId = txn, pillCount = 1, imagePath = file.absolutePath, step = "VIAL"))

        cleaner.purge()

        assertNull(db.dispenseTxnDao().getById(txn))
        assertFalse(file.exists())
    }

    @Test
    fun `a purge interrupted after the commit has its files deleted by the next purge`() = runTest {
        val file = File.createTempFile("tutorial_img", ".jpg")
        val pending = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "tutorial_pending_delete.txt")
        pending.writeText(file.absolutePath)

        cleaner.purge()

        assertFalse(file.exists())
        assertFalse(pending.exists())
    }

    @Test
    fun `concurrent purges run one at a time and leave no pending file`() = runTest {
        val file = File.createTempFile("tutorial_img", ".jpg")
        val txn = db.dispenseTxnDao().insertIgnore(DispenseTxnEntity(drugId = null, rxNo = "${TutorialData.RX_PREFIX}4", status = CountStatus.PARTIAL))
        db.dispenseCountDao().insert(DispenseCountEntity(txnId = txn, pillCount = 1, imagePath = file.absolutePath, step = "VIAL"))

        listOf(async { cleaner.purge() }, async { cleaner.purge() }).awaitAll()

        assertNull(db.dispenseTxnDao().getById(txn))
        assertFalse(file.exists())
        assertFalse(File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "tutorial_pending_delete.txt").exists())
    }
}
