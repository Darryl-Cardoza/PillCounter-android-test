package com.dispensesure.retail.core.analytics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedDao
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedEntity
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Prune rules for the counted ledger.
 *
 * The stock half is retention-by-liveness rather than by date: a batch left open overnight is
 * resumed the next day, and dropping its ledger rows would re-count every transaction in it.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsCountedDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AnalyticsCountedDao
    private lateinit var batchDao: StockBatchDao
    private lateinit var txnDao: StockTxnDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.analyticsCountedDao()
        batchDao = db.stockBatchDao()
        txnDao = db.stockTxnDao()
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedStockRow(
        txnId: Long,
        batchId: Long,
        batchStatus: BatchStatus,
        batchDeleted: Boolean = false,
        txnDeleted: Boolean = false,
        businessDate: String = YESTERDAY,
    ) {
        batchDao.insert(
            StockBatchEntity(
                batchId = batchId,
                status = batchStatus,
                isDeleted = batchDeleted,
                region = com.dispensesure.retail.core.region.Region.US,
            )
        )
        txnDao.upsertPreservingId(
            StockTxnEntity(txnId = txnId, batchId = batchId, isDeleted = txnDeleted)
        )
        dao.upsert(
            AnalyticsCountedEntity(
                kind = AnalyticsCountedEntity.KIND_STOCK_TXN,
                entityId = txnId,
                businessDate = businessDate,
                countedSealedQty = 4L,
            )
        )
    }

    private suspend fun stockRow(txnId: Long) =
        dao.find(AnalyticsCountedEntity.KIND_STOCK_TXN, txnId)

    /** The whole point: a batch resumed the next day must not re-count its transactions. */
    @Test
    fun `a stock row for an in-progress batch survives the date rollover`() = runTest {
        seedStockRow(txnId = 1L, batchId = 100L, batchStatus = BatchStatus.INPROGRESS)

        dao.pruneOlderThan(TODAY)

        assertNotNull("open batch must keep its ledger", stockRow(1L))
    }

    @Test
    fun `a stock row for a completed batch is pruned`() = runTest {
        seedStockRow(txnId = 2L, batchId = 101L, batchStatus = BatchStatus.COMPLETED)

        dao.pruneOlderThan(TODAY)

        assertNull(stockRow(2L))
    }

    @Test
    fun `a stock row for a deleted batch is pruned`() = runTest {
        seedStockRow(
            txnId = 3L,
            batchId = 102L,
            batchStatus = BatchStatus.INPROGRESS,
            batchDeleted = true,
        )

        dao.pruneOlderThan(TODAY)

        assertNull(stockRow(3L))
    }

    @Test
    fun `a stock row whose transaction was deleted is pruned`() = runTest {
        seedStockRow(
            txnId = 4L,
            batchId = 103L,
            batchStatus = BatchStatus.INPROGRESS,
            txnDeleted = true,
        )

        dao.pruneOlderThan(TODAY)

        assertNull(stockRow(4L))
    }

    /** No transaction row at all — nothing can grow, so the ledger row is dead weight. */
    @Test
    fun `an orphaned stock row is pruned`() = runTest {
        dao.upsert(
            AnalyticsCountedEntity(
                kind = AnalyticsCountedEntity.KIND_STOCK_TXN,
                entityId = 5L,
                businessDate = YESTERDAY,
            )
        )

        dao.pruneOlderThan(TODAY)

        assertNull(stockRow(5L))
    }

    /** Today's rows are never touched, whatever their batch is doing. */
    @Test
    fun `today's stock row is kept even when its batch is completed`() = runTest {
        seedStockRow(
            txnId = 6L,
            batchId = 104L,
            batchStatus = BatchStatus.COMPLETED,
            businessDate = TODAY,
        )

        dao.pruneOlderThan(TODAY)

        assertNotNull(stockRow(6L))
    }

    /** A dispense is counted once and never revised, so date alone decides. */
    @Test
    fun `an old dispense row is pruned regardless of any batch`() = runTest {
        dao.upsert(
            AnalyticsCountedEntity(
                kind = AnalyticsCountedEntity.KIND_DISPENSE,
                entityId = 7L,
                businessDate = YESTERDAY,
            )
        )

        dao.pruneOlderThan(TODAY)

        assertNull(dao.find(AnalyticsCountedEntity.KIND_DISPENSE, 7L))
    }

    @Test
    fun `today's dispense row is kept`() = runTest {
        dao.upsert(
            AnalyticsCountedEntity(
                kind = AnalyticsCountedEntity.KIND_DISPENSE,
                entityId = 8L,
                businessDate = TODAY,
            )
        )

        dao.pruneOlderThan(TODAY)

        assertNotNull(dao.find(AnalyticsCountedEntity.KIND_DISPENSE, 8L))
    }

    private companion object {
        const val TODAY = "2025-01-15"
        const val YESTERDAY = "2025-01-14"
    }
}
