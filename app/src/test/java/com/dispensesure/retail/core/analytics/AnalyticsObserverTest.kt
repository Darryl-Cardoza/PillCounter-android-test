package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.collect.AnalyticsObserver
import com.dispensesure.retail.core.analytics.model.AnalyticsDuration
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedDao
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedEntity
import com.dispensesure.retail.core.events.DomainEvent
import com.dispensesure.retail.core.events.DomainEventBus
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.StockBottleInfoEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsObserverTest {

    private lateinit var bus: DomainEventBus
    private lateinit var countedDao: AnalyticsCountedDao
    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var dispenseCountDao: DispenseCountDao
    private lateinit var drugDao: DrugMasterDao
    private lateinit var stockTxnDao: StockTxnDao
    private lateinit var stockBottleDao: StockBottleInfoDao
    private lateinit var stockBatchDao: StockBatchDao

    private var applyCalls = 0
    private var lastIncrements: List<AnalyticsIncrement> = emptyList()
    private var lastDuration: AnalyticsDuration? = null

    @Before
    fun setUp() {
        bus = DomainEventBus()
        countedDao = mockk(relaxed = true)
        dispenseTxnDao = mockk(relaxed = true)
        dispenseCountDao = mockk(relaxed = true)
        drugDao = mockk(relaxed = true)
        stockTxnDao = mockk(relaxed = true)
        stockBottleDao = mockk(relaxed = true)
        stockBatchDao = mockk(relaxed = true)
        applyCalls = 0
        lastIncrements = emptyList()
        lastDuration = null
    }

    private fun makeObserver() = AnalyticsObserver(
        bus = bus,
        countedDao = countedDao,
        dispenseTxnDao = dispenseTxnDao,
        dispenseCountDao = dispenseCountDao,
        drugDao = drugDao,
        stockTxnDao = stockTxnDao,
        stockBottleInfoDao = stockBottleDao,
        stockBatchDao = stockBatchDao,
        isHazardousDrugEnabled = { false },
        applyAnalytics = { increments, durations, _ ->
            applyCalls++
            lastIncrements = increments
            lastDuration = durations.firstOrNull()
        },
    )

    @Test
    fun `DispenseCompleted increments dispense counter`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, 1L) } returns false
        coEvery { dispenseTxnDao.getById(1L) } returns DispenseTxnEntity(
            txnId = 1L, status = CountStatus.COMPLETED, createdAt = 1_000_000L, updatedAt = 1_060_000L,
        )
        coEvery { drugDao.getDrugById(null) } returns DrugMasterEntity(drugId = 0L, ndc = "", drugType = null, isHazardous = false)
        coEvery { dispenseCountDao.sumPillCountByStep(1L, any()) } returns 0
        coEvery { dispenseCountDao.countRowsByStep(1L, any()) } returns 0
        coEvery { dispenseCountDao.countManualRows(1L) } returns 0
        coEvery { dispenseCountDao.sumPillCountForUntypedRows(1L) } returns 0
        coEvery { countedDao.insertIgnore(any()) } returns 1L

        bus.tryPost(DomainEvent.DispenseCompleted(1L))
        advanceUntilIdle()

        assertEquals(1, applyCalls)
        assert(lastIncrements.any { it.field == AnalyticsField.DISPENSE_NORMAL_COUNT && it.delta == 1L })
    }

    @Test
    fun `DispenseCompleted second call for same txnId is no-op`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, 2L) } returns true

        bus.tryPost(DomainEvent.DispenseCompleted(2L))
        advanceUntilIdle()

        assertEquals(0, applyCalls)
    }

    @Test
    fun `DispenseCompleted with non-COMPLETED status is no-op`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, 3L) } returns false
        coEvery { dispenseTxnDao.getById(3L) } returns DispenseTxnEntity(
            txnId = 3L, status = CountStatus.PARTIAL,
        )

        bus.tryPost(DomainEvent.DispenseCompleted(3L))
        advanceUntilIdle()

        assertEquals(0, applyCalls)
    }

    @Test
    fun `DispenseCompleted missing txn row is swallowed and collector keeps running`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, 99L) } returns false
        coEvery { dispenseTxnDao.getById(99L) } returns null

        bus.tryPost(DomainEvent.DispenseCompleted(99L))
        bus.tryPost(DomainEvent.RxLabelProceeded)
        advanceUntilIdle()

        // RxLabelProceeded must still land even though the dispense had no entity
        assert(applyCalls >= 1)
        assert(lastIncrements.any { it.field == AnalyticsField.ORIGIN_RX_LABEL_PROCEEDED })
    }

    @Test
    fun `StockBottleRecorded first bottle of txn increments stock count`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_STOCK_TXN, 10L) } returns false
        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_BOTTLE, 20L) } returns false
        coEvery { stockTxnDao.getById(10L) } returns StockTxnEntity(txnId = 10L, batchId = 5L, drugId = 77L)
        coEvery { stockBatchDao.getById(5L) } returns StockBatchEntity(batchId = 5L, requestIdFromPMS = null)
        coEvery { stockBottleDao.getById(20L) } returns StockBottleInfoEntity(bottleId = 20L, stockTxnId = 10L, bottleQty = 2, looseQty = 0)
        coEvery { drugDao.getDrugById(77L) } returns DrugMasterEntity(drugId = 77L, ndc = "12345678901")
        coEvery { countedDao.insertIgnore(any()) } returns 1L

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 10L, bottleId = 20L))
        advanceUntilIdle()

        assertEquals(1, applyCalls)
        assert(lastIncrements.any { it.field == AnalyticsField.INVENTORY_STOCK_COUNT && it.delta == 1L })
    }

    @Test
    fun `StockBottleRecorded second bottle of same txn does not re-increment stock count`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_STOCK_TXN, 10L) } returns true
        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_BOTTLE, 21L) } returns false
        coEvery { stockTxnDao.getById(10L) } returns StockTxnEntity(txnId = 10L, batchId = 5L, drugId = 77L)
        coEvery { stockBatchDao.getById(5L) } returns StockBatchEntity(batchId = 5L, requestIdFromPMS = null)
        coEvery { stockBottleDao.getById(21L) } returns StockBottleInfoEntity(bottleId = 21L, stockTxnId = 10L, bottleQty = 2, looseQty = 0)
        coEvery { drugDao.getDrugById(77L) } returns DrugMasterEntity(drugId = 77L, ndc = "12345678901")
        coEvery { countedDao.insertIgnore(any()) } returns 1L

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 10L, bottleId = 21L))
        advanceUntilIdle()

        assert(lastIncrements.none { it.field == AnalyticsField.INVENTORY_STOCK_COUNT })
    }

    @Test
    fun `StockBottleRecorded sealed bottle increments sealed count`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_STOCK_TXN, 11L) } returns false
        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_BOTTLE, 22L) } returns false
        coEvery { stockTxnDao.getById(11L) } returns StockTxnEntity(txnId = 11L, batchId = 5L, drugId = 77L)
        coEvery { stockBatchDao.getById(5L) } returns StockBatchEntity(batchId = 5L, requestIdFromPMS = null)
        coEvery { stockBottleDao.getById(22L) } returns StockBottleInfoEntity(bottleId = 22L, stockTxnId = 11L, bottleQty = 3, looseQty = 0)
        coEvery { drugDao.getDrugById(77L) } returns DrugMasterEntity(drugId = 77L, ndc = "12345678901")
        coEvery { countedDao.insertIgnore(any()) } returns 1L

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 11L, bottleId = 22L))
        advanceUntilIdle()

        assert(lastIncrements.any { it.field == AnalyticsField.INVENTORY_STOCK_SEALED_BOTTLES_SCANNED })
    }

    @Test
    fun `StockBottleRecorded blank NDC does not increment NDC count`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_STOCK_TXN, 12L) } returns false
        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_BOTTLE, 23L) } returns false
        coEvery { stockTxnDao.getById(12L) } returns StockTxnEntity(txnId = 12L, batchId = 5L, drugId = 77L)
        coEvery { stockBatchDao.getById(5L) } returns StockBatchEntity(batchId = 5L, requestIdFromPMS = null)
        coEvery { stockBottleDao.getById(23L) } returns StockBottleInfoEntity(bottleId = 23L, stockTxnId = 12L, bottleQty = 1, looseQty = 0)
        coEvery { drugDao.getDrugById(77L) } returns DrugMasterEntity(drugId = 77L, ndc = "")
        coEvery { countedDao.insertIgnore(any()) } returns 1L

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 12L, bottleId = 23L))
        advanceUntilIdle()

        assert(lastIncrements.none { it.field == AnalyticsField.INVENTORY_STOCK_NDC_COUNT })
    }

    @Test
    fun `CountBlocked WRONG_DRUG increments safety_wrong_drug_blocked`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        bus.tryPost(DomainEvent.CountBlocked(DomainEvent.BlockReason.WRONG_DRUG))
        advanceUntilIdle()

        assert(lastIncrements.any { it.field == AnalyticsField.SAFETY_WRONG_DRUG_BLOCKED && it.delta == 1L })
    }

    @Test
    fun `CountBlocked EXPIRED increments safety_expired_blocked`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        bus.tryPost(DomainEvent.CountBlocked(DomainEvent.BlockReason.EXPIRED))
        advanceUntilIdle()

        assert(lastIncrements.any { it.field == AnalyticsField.SAFETY_EXPIRED_BLOCKED && it.delta == 1L })
    }

    @Test
    fun `RxLabelProceeded increments origin_rx_label_proceeded`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        bus.tryPost(DomainEvent.RxLabelProceeded)
        advanceUntilIdle()

        assert(lastIncrements.any { it.field == AnalyticsField.ORIGIN_RX_LABEL_PROCEEDED && it.delta == 1L })
    }

    @Test
    fun `ExpiryWarningResolved ALREADY_EXPIRED increments expiry_expired_shown`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        bus.tryPost(DomainEvent.ExpiryWarningResolved(DomainEvent.ExpiryReason.ALREADY_EXPIRED, DomainEvent.ExpiryOutcome.PROCEEDED))
        advanceUntilIdle()

        assert(lastIncrements.any { it.field == AnalyticsField.EXPIRY_EXPIRED_SHOWN && it.delta == 1L })
    }

    @Test
    fun `ExpiryWarningResolved BEFORE_DOSAGE_END increments expiry_before_dosage_end`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        bus.tryPost(DomainEvent.ExpiryWarningResolved(DomainEvent.ExpiryReason.BEFORE_DOSAGE_END, DomainEvent.ExpiryOutcome.PROCEEDED))
        advanceUntilIdle()

        assert(lastIncrements.any { it.field == AnalyticsField.EXPIRY_BEFORE_DOSAGE_END && it.delta == 1L })
    }

    @Test
    fun `ExpiryWarningResolved NEAR_EXPIRY increments expiry_near_shown`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        bus.tryPost(DomainEvent.ExpiryWarningResolved(DomainEvent.ExpiryReason.NEAR_EXPIRY, DomainEvent.ExpiryOutcome.PROCEEDED))
        advanceUntilIdle()

        assert(lastIncrements.any { it.field == AnalyticsField.EXPIRY_NEAR_SHOWN && it.delta == 1L })
    }
}
