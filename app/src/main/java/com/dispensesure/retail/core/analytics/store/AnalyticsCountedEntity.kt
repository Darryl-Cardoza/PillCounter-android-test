package com.dispensesure.retail.core.analytics.store

import androidx.room.Entity
import androidx.room.Index

/**
 * Persisted deduplification table — one row per (kind, entityId) pair that has already been
 * counted in today's analytics row.
 *
 * Two [kind] values replace two in-memory sets:
 * - `DISPENSE` — [entityId] is a `DispenseTxnEntity.txnId`
 * - `STOCK_TXN` — [entityId] is a `StockTxnEntity.txnId`
 *
 * The id sequences are independent across the kinds, so `kind` prevents a dispense `txnId` from
 * colliding with a stock `txnId` that happens to share the same numeric value.
 *
 * There is no per-bottle kind: inventory is counted once per stock transaction, reading the final
 * bottle quantities at that point, so individual bottle lines never need their own marker.
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
    }
}
