package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.collect.AnalyticsObserver
import com.dispensesure.retail.core.analytics.collect.CountDurationTracker
import com.dispensesure.retail.core.analytics.model.AnalyticsDuration
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.model.AnalyticsSetInsertion
import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import com.dispensesure.retail.core.analytics.store.AnalyticsClock
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedDao
import com.dispensesure.retail.core.analytics.store.AnalyticsCountedEntity
import com.dispensesure.retail.core.analytics.store.AnalyticsDateBoundary
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
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

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
    private lateinit var durationTracker: CountDurationTracker

    private var applyCalls = 0
    private var applySucceeds = true
    private var lastIncrements: List<AnalyticsIncrement> = emptyList()
    private var lastDuration: AnalyticsDuration? = null
    private var lastInsertions: List<AnalyticsSetInsertion> = emptyList()

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
        durationTracker = CountDurationTracker { NOW_MS }
        applyCalls = 0
        applySucceeds = true
        lastIncrements = emptyList()
        lastDuration = null
        lastInsertions = emptyList()
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
        durationTracker = durationTracker,
        clock = AnalyticsClock { NOW_MS },
        isHazardousDrugEnabled = { false },
        applyAnalytics = { increments, durations, insertions ->
            applyCalls++
            lastIncrements = increments
            lastDuration = durations.firstOrNull()
            lastInsertions = insertions
            applySucceeds
        },
        dateBoundary = AnalyticsDateBoundary(ZONE),
    )

    // ── Dispense ──────────────────────────────────────────────────────────────

    @Test
    fun `DispenseCompleted increments dispense counter`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        stubCompletedDispense(txnId = 1L)

        bus.tryPost(DomainEvent.DispenseCompleted(1L))
        advanceUntilIdle()

        assertEquals(1, applyCalls)
        assertTrue(lastIncrements.any { it.field == AnalyticsField.DISPENSE_NORMAL_COUNT && it.delta == 1L })
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

    /**
     * PARTIAL counts are deliberately outside analytics volume, and FORCE_COMPLETED never posts an
     * event, so COMPLETED is the only status that counts.
     */
    @Test
    fun `DispenseCompleted with non-COMPLETED status is no-op`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, 3L) } returns false
        coEvery { dispenseTxnDao.getById(3L) } returns DispenseTxnEntity(txnId = 3L, status = CountStatus.PARTIAL)

        bus.tryPost(DomainEvent.DispenseCompleted(3L))
        advanceUntilIdle()

        assertEquals(0, applyCalls)
    }

    @Test
    fun `FORCE_COMPLETED is not counted`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, 4L) } returns false
        coEvery { dispenseTxnDao.getById(4L) } returns DispenseTxnEntity(
            txnId = 4L, status = CountStatus.FORCE_COMPLETED, createdAt = NOW_MS - 60_000L, updatedAt = NOW_MS,
        )

        bus.tryPost(DomainEvent.DispenseCompleted(4L))
        advanceUntilIdle()

        assertEquals(0, applyCalls)
    }

    /**
     * An HL7 resend sweep can re-save a transaction completed on an earlier day. Counting it today
     * misdates the work, and the day it belongs to may already have been uploaded and deleted.
     */
    @Test
    fun `dispense completed on an earlier business date is not counted`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        val yesterday = NOW_MS - 36L * 60 * 60 * 1000
        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, 5L) } returns false
        coEvery { dispenseTxnDao.getById(5L) } returns DispenseTxnEntity(
            txnId = 5L, status = CountStatus.COMPLETED, createdAt = yesterday - 60_000L, updatedAt = yesterday,
        )

        bus.tryPost(DomainEvent.DispenseCompleted(5L))
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
        assertTrue(applyCalls >= 1)
        assertTrue(lastIncrements.any { it.field == AnalyticsField.ORIGIN_RX_LABEL_PROCEEDED })
    }

    // ── De-duplication marker ordering ────────────────────────────────────────

    @Test
    fun `counted marker is written after a successful apply`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        stubCompletedDispense(txnId = 6L)

        bus.tryPost(DomainEvent.DispenseCompleted(6L))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            countedDao.insertIgnore(match { it.kind == AnalyticsCountedEntity.KIND_DISPENSE && it.entityId == 6L })
        }
    }

    /**
     * The marker survives restarts, so one written for work that was never applied would exclude
     * that transaction permanently.
     */
    @Test
    fun `counted marker is not written when apply fails`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        applySucceeds = false
        stubCompletedDispense(txnId = 7L)

        bus.tryPost(DomainEvent.DispenseCompleted(7L))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            countedDao.insertIgnore(match { it.kind == AnalyticsCountedEntity.KIND_DISPENSE && it.entityId == 7L })
        }
    }

    // ── Inventory ─────────────────────────────────────────────────────────────

    @Test
    fun `StockBottleRecorded counts the transaction once`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        stubStockTxn(stockTxnId = 10L, sealedQty = 2L)

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 10L, bottleId = 20L))
        advanceUntilIdle()

        assertEquals(1, applyCalls)
        assertTrue(lastIncrements.any { it.field == AnalyticsField.INVENTORY_STOCK_COUNT && it.delta == 1L })
    }

    @Test
    fun `StockBottleRecorded for an already counted txn is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_STOCK_TXN, 10L) } returns true

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 10L, bottleId = 21L))
        advanceUntilIdle()

        assertEquals(0, applyCalls)
    }

    /** Sealed bottles are counted by quantity, read once at completion — not one per line. */
    @Test
    fun `sealed bottles are counted by quantity`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        stubStockTxn(stockTxnId = 11L, sealedQty = 12L)

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 11L, bottleId = 22L))
        advanceUntilIdle()

        val sealed = lastIncrements.first { it.field == AnalyticsField.INVENTORY_STOCK_SEALED_BOTTLES_SCANNED }
        assertEquals(12L, sealed.delta)
    }

    @Test
    fun `drug code is recorded as a set insertion`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        stubStockTxn(stockTxnId = 12L, sealedQty = 1L, ndc = "12345678901")

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 12L, bottleId = 23L))
        advanceUntilIdle()

        assertEquals(1, lastInsertions.size)
        assertEquals("12345678901", lastInsertions.first().value)
        assertEquals(AnalyticsWorkType.INVENTORY_STOCK_COUNT, lastInsertions.first().workType)
    }

    @Test
    fun `blank drug code produces no set insertion`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        stubStockTxn(stockTxnId = 13L, sealedQty = 1L, ndc = "")

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 13L, bottleId = 24L))
        advanceUntilIdle()

        assertTrue(lastInsertions.isEmpty())
    }

    /**
     * The row's createdAt is stamped in the same operation as the first bottle, so the duration
     * has to come from the tracker started when the count began.
     */
    @Test
    fun `inventory duration comes from the tracker, not row timestamps`() = runTest(UnconfinedTestDispatcher()) {
        var now = NOW_MS
        durationTracker = CountDurationTracker { now }
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        durationTracker.start(CountDurationTracker.stockKey(14L))
        now += 90_000L
        stubStockTxn(stockTxnId = 14L, sealedQty = 1L)

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 14L, bottleId = 25L))
        advanceUntilIdle()

        assertEquals(90L, lastDuration?.seconds)
        assertEquals(AnalyticsWorkType.INVENTORY_STOCK_COUNT, lastDuration?.workType)
    }

    @Test
    fun `inventory with no started timer reports no duration`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        stubStockTxn(stockTxnId = 15L, sealedQty = 1L)

        bus.tryPost(DomainEvent.StockBottleRecorded(stockTxnId = 15L, bottleId = 26L))
        advanceUntilIdle()

        assertEquals(null, lastDuration)
        assertTrue(lastIncrements.any { it.field == AnalyticsField.INVENTORY_STOCK_COUNT })
    }

    // ── Standalone events ─────────────────────────────────────────────────────

    @Test
    fun `CountBlocked WRONG_DRUG increments safety_wrong_drug_blocked`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        bus.tryPost(DomainEvent.CountBlocked(DomainEvent.BlockReason.WRONG_DRUG))
        advanceUntilIdle()

        assertTrue(lastIncrements.any { it.field == AnalyticsField.SAFETY_WRONG_DRUG_BLOCKED && it.delta == 1L })
    }

    @Test
    fun `RxLabelProceeded increments origin_rx_label_proceeded`() = runTest(UnconfinedTestDispatcher()) {
        val observer = makeObserver()
        observer.startObserving(backgroundScope)

        bus.tryPost(DomainEvent.RxLabelProceeded)
        advanceUntilIdle()

        assertTrue(lastIncrements.any { it.field == AnalyticsField.ORIGIN_RX_LABEL_PROCEEDED && it.delta == 1L })
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun stubCompletedDispense(txnId: Long) {
        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_DISPENSE, txnId) } returns false
        coEvery { dispenseTxnDao.getById(txnId) } returns DispenseTxnEntity(
            txnId = txnId,
            status = CountStatus.COMPLETED,
            createdAt = NOW_MS - 60_000L,
            updatedAt = NOW_MS,
        )
        coEvery { drugDao.getDrugById(null) } returns
            DrugMasterEntity(drugId = 0L, ndc = "", drugType = null, isHazardous = false)
        coEvery { dispenseCountDao.sumPillCountByStep(txnId, any()) } returns 0
        coEvery { dispenseCountDao.countRowsByStep(txnId, any()) } returns 0
        coEvery { dispenseCountDao.countManualRows(txnId) } returns 0
        coEvery { dispenseCountDao.sumPillCountForUntypedRows(txnId) } returns 0
        coEvery { countedDao.insertIgnore(any()) } returns 1L
    }

    private fun stubStockTxn(
        stockTxnId: Long,
        sealedQty: Long = 0L,
        looseLines: Long = 0L,
        ndc: String = "12345678901",
    ) {
        coEvery { countedDao.exists(AnalyticsCountedEntity.KIND_STOCK_TXN, stockTxnId) } returns false
        coEvery { stockTxnDao.getById(stockTxnId) } returns
            StockTxnEntity(txnId = stockTxnId, batchId = 5L, drugId = 77L)
        coEvery { stockBatchDao.getById(5L) } returns StockBatchEntity(batchId = 5L, requestIdFromPMS = null)
        coEvery { stockBottleDao.sumSealedBottleQty(stockTxnId) } returns sealedQty
        coEvery { stockBottleDao.countLooseLines(stockTxnId) } returns looseLines
        coEvery { drugDao.getDrugById(77L) } returns DrugMasterEntity(drugId = 77L, ndc = ndc)
        coEvery { countedDao.insertIgnore(any()) } returns 1L
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Vancouver")

        /** 2025-01-15 12:00 local in [ZONE] — mid-day, so ±36 h lands on a different date. */
        const val NOW_MS = 1_736_971_200_000L
    }
}
