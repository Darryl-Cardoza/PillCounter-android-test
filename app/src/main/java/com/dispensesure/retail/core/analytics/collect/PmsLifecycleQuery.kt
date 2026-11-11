package com.dispensesure.retail.core.analytics.collect

import com.dispensesure.retail.core.analytics.store.AnalyticsDateBoundary
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.StockBatchDao

data class PmsLifecycle(
    val dispense: PmsLifecycleBlock,
    val inventory: PmsLifecycleBlock,
) {
    companion object {
        /** Sent on every record for a date except the one carrying that date's real block. */
        val ZERO = PmsLifecycle(PmsLifecycleBlock.ZERO, PmsLifecycleBlock.ZERO)
    }
}

/**
 * @property pendingAtDayEnd Orders received that day with no completion recorded. Named for the
 *   wire field `pending_at_day_end`, which the strict upload contract fixes. It is measured when
 *   the day is encoded for upload rather than at midnight, so an order completed after the day
 *   closed but before the upload ran is already out of this number. "Still pending at upload" is
 *   the accurate reading.
 */
data class PmsLifecycleBlock(
    val received: Long,
    val completed: Long,
    val synced: Long,
    val pendingAtDayEnd: Long,
) {
    companion object {
        val ZERO = PmsLifecycleBlock(0L, 0L, 0L, 0L)
    }
}

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
        val range = dateBoundary.dayRangeMillis(businessDate) ?: return PmsLifecycle.ZERO
        val (startMs, endMs) = range

        val dispenseReceived = dispenseTxnDao.countPmsDispenseReceived(startMs, endMs)
        val dispense = if (dispenseReceived == 0L) PmsLifecycleBlock.ZERO else {
            val completed = dispenseTxnDao.countPmsDispenseCompleted(startMs, endMs)
            PmsLifecycleBlock(
                received = dispenseReceived,
                completed = completed,
                synced = dispenseTxnDao.countPmsDispenseSynced(startMs, endMs),
                pendingAtDayEnd = dispenseReceived - completed,
            )
        }

        val inventoryReceived = stockBatchDao.countPmsInventoryReceived(startMs, endMs)
        val inventory = if (inventoryReceived == 0L) PmsLifecycleBlock.ZERO else {
            val completed = stockBatchDao.countPmsInventoryCompleted(startMs, endMs)
            PmsLifecycleBlock(
                received = inventoryReceived,
                completed = completed,
                synced = stockBatchDao.countPmsInventorySynced(startMs, endMs),
                pendingAtDayEnd = inventoryReceived - completed,
            )
        }

        return PmsLifecycle(dispense, inventory)
    }
}
