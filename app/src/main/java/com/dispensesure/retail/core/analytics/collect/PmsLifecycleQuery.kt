package com.dispensesure.retail.core.analytics.collect

import com.dispensesure.retail.core.analytics.store.AnalyticsDateBoundary
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.StockBatchDao

data class PmsLifecycle(
    val dispense: PmsLifecycleBlock,
    val inventory: PmsLifecycleBlock,
)

data class PmsLifecycleBlock(
    val received: Long,
    val completed: Long,
    val synced: Long,
    val pendingAtDayEnd: Long,
)

/**
 * Counts PMS orders for one business date by querying the persisted rows at encode time.
 *
 * "Still pending when the day closed" cannot be accumulated — a counter cannot notice an order
 * that simply never completed. Querying the rows directly gives the correct answer.
 */
class PmsLifecycleQuery(
    private val dispenseTxnDao: DispenseTxnDao,
    private val stockBatchDao: StockBatchDao,
    private val dateBoundary: AnalyticsDateBoundary = AnalyticsDateBoundary(),
) {

    suspend fun query(businessDate: String): PmsLifecycle {
        val range = dateBoundary.dayRangeMillis(businessDate)
            ?: return PmsLifecycle(zeros(), zeros())
        val (startMs, endMs) = range

        val dispenseReceived = dispenseTxnDao.countPmsDispenseReceived(startMs, endMs)
        val dispense = if (dispenseReceived == 0L) zeros() else PmsLifecycleBlock(
            received = dispenseReceived,
            completed = dispenseTxnDao.countPmsDispenseCompleted(startMs, endMs),
            synced = dispenseTxnDao.countPmsDispenseSynced(startMs, endMs),
            pendingAtDayEnd = dispenseReceived - dispenseTxnDao.countPmsDispenseCompleted(startMs, endMs),
        )

        val inventoryReceived = stockBatchDao.countPmsInventoryReceived(startMs, endMs)
        val inventory = if (inventoryReceived == 0L) zeros() else PmsLifecycleBlock(
            received = inventoryReceived,
            completed = stockBatchDao.countPmsInventoryCompleted(startMs, endMs),
            synced = stockBatchDao.countPmsInventorySynced(startMs, endMs),
            pendingAtDayEnd = inventoryReceived - stockBatchDao.countPmsInventoryCompleted(startMs, endMs),
        )

        return PmsLifecycle(dispense, inventory)
    }

    private fun zeros() = PmsLifecycleBlock(0L, 0L, 0L, 0L)
}
