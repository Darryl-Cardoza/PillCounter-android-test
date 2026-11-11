package com.dispensesure.retail.core.analytics.store

import androidx.room.Entity
import androidx.room.Index

/**
 * Persisted ledger of what has already been counted into today's analytics row — one row per
 * (kind, entityId) pair.
 *
 * Two [kind] values replace two in-memory sets:
 * - `DISPENSE` — [entityId] is a `DispenseTxnEntity.txnId`
 * - `STOCK_TXN` — [entityId] is a `StockTxnEntity.txnId`
 *
 * The id sequences are independent across the kinds, so `kind` prevents a dispense `txnId` from
 * colliding with a stock `txnId` that happens to share the same numeric value.
 *
 * **Why a ledger and not a boolean marker.** A dispense is counted once and never revised, so for
 * `DISPENSE` the row's presence is the whole answer and the quantity columns stay 0. Inventory is
 * different: one `stock_txn` is one *drug in a batch*, and it keeps accumulating after the first
 * bottle line — a second lot of that drug, a later loose count, or a `+/-` edit that rewrites an
 * existing line's `bottleQty`. Treating the first event as final under-reported every one of those.
 *
 * So the stock path re-reads the transaction's totals on **every** event and applies the difference
 * against [countedSealedQty] / [countedLooseLines]. Increments stay additive, the row stays the
 * record of what was already sent, and a replayed event contributes a delta of zero. Deltas may be
 * negative when a line is deleted or revised downward.
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
    /** Sealed bottles already added to the daily row for this transaction. Always 0 for DISPENSE. */
    val countedSealedQty: Long = 0L,
    /** Loose-pill lines already added to the daily row for this transaction. 0 for DISPENSE. */
    val countedLooseLines: Long = 0L,
) {
    companion object {
        const val KIND_DISPENSE = "DISPENSE"
        const val KIND_STOCK_TXN = "STOCK_TXN"
    }
}
