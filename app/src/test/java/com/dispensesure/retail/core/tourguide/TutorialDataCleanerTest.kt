package com.dispensesure.retail.core.tourguide

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.BatchEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.PillCountTxnEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
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
            db, db.pillCountTxnDao(), db.batchDao(), db.stockTxnDao(), db.bottleInfoDao(), db.drugMasterDao(),
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

        val demoTxn = db.pillCountTxnDao().insertIgnore(PillCountTxnEntity(drugId = demoDrugId, rxNo = "TUTORIALDEMO1", isDispense = true, status = CountStatus.PARTIAL))
        val realTxn = db.pillCountTxnDao().insertIgnore(PillCountTxnEntity(drugId = realDrugId, rxNo = "RX1", isDispense = true, status = CountStatus.PARTIAL))

        db.batchDao().insert(BatchEntity(batchId = 10L, bucketId = TutorialData.BUCKET))
        db.stockTxnDao().insertIgnore(StockTxnEntity(batchId = 10L, drugId = demoDrugId))
        db.batchDao().insert(BatchEntity(batchId = 11L, bucketId = "Normal"))
        db.stockTxnDao().insertIgnore(StockTxnEntity(batchId = 11L, drugId = realDrugId))

        cleaner.purge()

        assertNull(db.pillCountTxnDao().getById(demoTxn))
        assertNotNull(db.pillCountTxnDao().getById(realTxn))
        assertNull(db.batchDao().getById(10L))
        assertNotNull(db.batchDao().getById(11L))
        assertNull(db.drugMasterDao().getDrugByNdc(TutorialData.DEMO_NDC))
        assertNotNull(db.drugMasterDao().getDrugByNdc("1234"))
        assertNotNull(db.stockTxnDao().findByDrugInBatch(11L, realDrugId))
    }

    @Test
    fun `purge removes a tutorial txn whose demo drug is already gone and an empty tutorial batch`() = runTest {
        val txn = db.pillCountTxnDao().insertIgnore(PillCountTxnEntity(drugId = null, rxNo = "TUTORIALDEMO2", isDispense = true, status = CountStatus.PARTIAL))
        db.batchDao().insert(BatchEntity(batchId = 20L, bucketId = TutorialData.BUCKET))

        cleaner.purge()

        assertNull(db.pillCountTxnDao().getById(txn))
        assertNull(db.batchDao().getById(20L))
    }

    @Test
    fun `purge is idempotent`() = runTest {
        db.batchDao().insert(BatchEntity(batchId = 30L, bucketId = TutorialData.BUCKET))
        cleaner.purge()
        cleaner.purge()
        assertNull(db.batchDao().getById(30L))
    }
}
