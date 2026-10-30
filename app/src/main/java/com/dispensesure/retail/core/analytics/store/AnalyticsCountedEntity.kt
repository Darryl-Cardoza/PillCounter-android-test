package com.dispensesure.retail.core.analytics.store

import androidx.room.Entity
import androidx.room.Index

/**
 * Persisted deduplification table — one row per (kind, entityId) pair that has already been
 * counted in today's analytics row.
 *
 * Three [kind] values replace three in-memory sets:
 * - `DISPENSE` — [entityId] is a `DispenseTxnEntity.txnId`
 * - `STOCK_TXN` — [entityId] is a `StockTxnEntity.txnId`
 * - `BOTTLE`   — [entityId] is a `StockBottleInfoEntity.bottleId`
 *
 * The id sequences are independent across the three kinds, so `kind` prevents a `txnId` from
 * colliding with a `bottleId` that happens to share the same numeric value.
 *
 * Pruned on date rollover: `DELETE WHERE businessDate < :today`. Bounded to one day of work.
 */
@Entity(
    tableName = "analytics_counted",
    primaryKeys = ["kind", "entityId"],
    indices = [Index(value = ["businessDate"])],
)
data class AnalyticsCountedEntity(
    val kind: String,
    val entityId: Long,
    val businessDate: String,
) {
    companion object {
        const val KIND_DISPENSE = "DISPENSE"
        const val KIND_STOCK_TXN = "STOCK_TXN"
        const val KIND_BOTTLE = "BOTTLE"
    }
}
