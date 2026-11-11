package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.collect.PmsLifecycle
import com.dispensesure.retail.core.analytics.collect.PmsLifecycleBlock
import com.dispensesure.retail.core.analytics.collect.PmsLifecycleQuery
import com.dispensesure.retail.core.analytics.store.AnalyticsDateBoundary
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

class PmsLifecycleQueryTest {

    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var stockBatchDao: StockBatchDao
    private lateinit var query: PmsLifecycleQuery

    // Use UTC for deterministic test ranges — no DST surprises
    private val utcBoundary = AnalyticsDateBoundary(ZoneId.of("UTC"))

    @Before
    fun setUp() {
        dispenseTxnDao = mockk(relaxed = true)
        stockBatchDao = mockk(relaxed = true)
        query = PmsLifecycleQuery(dispenseTxnDao, stockBatchDao, utcBoundary)
    }

    @Test
    fun `returns all zeros when dispense received is zero`() = runTest {
        coEvery { dispenseTxnDao.countPmsDispenseReceived(any(), any()) } returns 0L
        coEvery { stockBatchDao.countPmsInventoryReceived(any(), any()) } returns 0L

        val result = query.query("2025-01-15")

        assertEquals(PmsLifecycleBlock(0L, 0L, 0L, 0L), result.dispense)
        assertEquals(PmsLifecycleBlock(0L, 0L, 0L, 0L), result.inventory)
    }

    @Test
    fun `zero short-circuit skips completed and synced queries when received is zero`() = runTest {
        coEvery { dispenseTxnDao.countPmsDispenseReceived(any(), any()) } returns 0L
        coEvery { stockBatchDao.countPmsInventoryReceived(any(), any()) } returns 0L

        query.query("2025-01-15")

        coVerify(exactly = 0) { dispenseTxnDao.countPmsDispenseCompleted(any(), any()) }
        coVerify(exactly = 0) { dispenseTxnDao.countPmsDispenseSynced(any(), any()) }
        coVerify(exactly = 0) { stockBatchDao.countPmsInventoryCompleted(any(), any()) }
        coVerify(exactly = 0) { stockBatchDao.countPmsInventorySynced(any(), any()) }
    }

    @Test
    fun `returns correct counts when dispense orders exist`() = runTest {
        coEvery { dispenseTxnDao.countPmsDispenseReceived(any(), any()) } returns 10L
        coEvery { dispenseTxnDao.countPmsDispenseCompleted(any(), any()) } returns 8L
        coEvery { dispenseTxnDao.countPmsDispenseSynced(any(), any()) } returns 7L
        coEvery { stockBatchDao.countPmsInventoryReceived(any(), any()) } returns 0L

        val result = query.query("2025-01-15")

        assertEquals(
            PmsLifecycleBlock(received = 10L, completed = 8L, synced = 7L, pendingAtDayEnd = 2L),
            result.dispense,
        )
        assertEquals(PmsLifecycleBlock(0L, 0L, 0L, 0L), result.inventory)
    }

    @Test
    fun `pendingAtDayEnd equals received minus completed`() = runTest {
        coEvery { dispenseTxnDao.countPmsDispenseReceived(any(), any()) } returns 5L
        coEvery { dispenseTxnDao.countPmsDispenseCompleted(any(), any()) } returns 3L
        coEvery { dispenseTxnDao.countPmsDispenseSynced(any(), any()) } returns 3L
        coEvery { stockBatchDao.countPmsInventoryReceived(any(), any()) } returns 0L

        val result = query.query("2025-01-15")

        assertEquals(2L, result.dispense.pendingAtDayEnd)
    }

    @Test
    fun `returns correct inventory counts when inventory orders exist`() = runTest {
        coEvery { dispenseTxnDao.countPmsDispenseReceived(any(), any()) } returns 0L
        coEvery { stockBatchDao.countPmsInventoryReceived(any(), any()) } returns 4L
        coEvery { stockBatchDao.countPmsInventoryCompleted(any(), any()) } returns 4L
        coEvery { stockBatchDao.countPmsInventorySynced(any(), any()) } returns 4L

        val result = query.query("2025-01-15")

        assertEquals(
            PmsLifecycleBlock(received = 4L, completed = 4L, synced = 4L, pendingAtDayEnd = 0L),
            result.inventory,
        )
    }

    @Test
    fun `returns all zeros when business date is unparseable`() = runTest {
        val result = query.query("NOT_A_DATE")

        assertEquals(PmsLifecycle(
            PmsLifecycleBlock(0L, 0L, 0L, 0L),
            PmsLifecycleBlock(0L, 0L, 0L, 0L),
        ), result)
    }
}
