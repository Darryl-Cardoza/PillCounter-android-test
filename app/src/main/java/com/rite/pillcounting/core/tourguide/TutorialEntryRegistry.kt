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

    /** Snapshots and clears the tracked dispense txn ids — for the one-shot cleanup pass. */
    fun consumeTutorialTxnIds(): Set<Long> = synchronized(txnIds) {
        txnIds.toSet().also { txnIds.clear() }
    }

    /** Snapshots and clears the tracked stock-count batch ids — for the one-shot cleanup pass. */
    fun consumeTutorialBatchIds(): Set<Long> = synchronized(batchIds) {
        batchIds.toSet().also { batchIds.clear() }
    }
}
