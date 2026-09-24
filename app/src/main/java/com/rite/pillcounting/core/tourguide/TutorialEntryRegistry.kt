package com.rite.pillcounting.core.tourguide

import java.util.Collections

/**
 * Txn/batch ids created by a real dispense/stock count driven by the guided tour (a practice
 * run) — must never reach the PMS backend as a real entry. In-memory only; no need to survive
 * a process restart, so it's kept out of the Room schema entirely.
 */
object TutorialEntryRegistry {
    private val txnIds = Collections.synchronizedSet(mutableSetOf<Long>())
    private val batchIds = Collections.synchronizedSet(mutableSetOf<Long>())

    fun markTutorialEntry(id: Long) {
        txnIds.add(id)
    }

    fun isTutorialEntry(id: Long): Boolean = id in txnIds

    fun markTutorialBatch(id: Long) {
        batchIds.add(id)
    }

    fun isTutorialBatch(id: Long): Boolean = id in batchIds

    fun unmarkTutorialEntry(id: Long) {
        txnIds.remove(id)
    }

    fun unmarkTutorialBatch(id: Long) {
        batchIds.remove(id)
    }

    /**
     * Snapshot only — does NOT clear. A marker must stay in place (still hiding the row from
     * Recent Activity and blocking HL7 sends) until the caller confirms the row is actually
     * deleted; see [unmarkTutorialEntry]/[unmarkTutorialBatch].
     */
    fun snapshotTutorialTxnIds(): Set<Long> = synchronized(txnIds) { txnIds.toSet() }

    /** Snapshot only — see [snapshotTutorialTxnIds]. */
    fun snapshotTutorialBatchIds(): Set<Long> = synchronized(batchIds) { batchIds.toSet() }
}
