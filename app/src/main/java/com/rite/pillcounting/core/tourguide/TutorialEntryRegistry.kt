package com.rite.pillcounting.core.tourguide

import java.util.Collections

/**
 * Txn/batch ids created while [FtueController]'s guided tour was driving a real dispense or a
 * real stock count (so its ACTION_TAP/ACTION_WAIT gating could run end-to-end against real
 * buttons and a real scan, not a fake screen) — a practice run, not real work. The backend
 * (the pharmacy's PMS, reached via HL7) must never see it as an official entry, and once the
 * tour finishes or is skipped none of it should linger as real local history either.
 *
 * In-memory only, scoped to this app process — a one-time onboarding tour never needs to
 * survive a process restart, and this keeps the exclusion out of the Room schema entirely.
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
