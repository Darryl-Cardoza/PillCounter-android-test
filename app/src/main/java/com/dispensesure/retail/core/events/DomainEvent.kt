package com.dispensesure.retail.core.events

/**
 * A fact about pharmacy work that just happened, announced by the flow that performed it.
 *
 * Description:
 * These are domain facts, not instructions. A producer posts one because the thing happened, not
 * because it wants a particular consumer to react; whether anything is listening is none of its
 * concern. That is what keeps every consumer — today only the analytics log — removable without
 * touching the flows that emit.
 *
 * Each event carries only the ids needed to find the rows it refers to. Consumers read whatever
 * detail they need from the database themselves, so adding a consumer never widens an event.
 */
sealed interface DomainEvent {

    /**
     * A dispense transaction reached a completed status and its rows are written.
     *
     * @property txnId [com.dispensesure.retail.core.room.models.DispenseTxnEntity.txnId] of the
     *   completed transaction.
     */
    data class DispenseCompleted(val txnId: Long) : DomainEvent

    /**
     * A bottle line was recorded against a stock (inventory) transaction.
     *
     * Posted once per physical bottle line written. The stock transaction itself is created in the
     * same operation, so the first event for a given [stockTxnId] is also that transaction's
     * beginning.
     *
     * @property stockTxnId [com.dispensesure.retail.core.room.models.StockTxnEntity.txnId] the line
     *   belongs to.
     * @property bottleId [com.dispensesure.retail.core.room.models.StockBottleInfoEntity.bottleId]
     *   of the line just written.
     */
    data class StockBottleRecorded(val stockTxnId: Long, val bottleId: Long) : DomainEvent

    /**
     * A count was refused before it could start, because the scanned container failed a check.
     *
     * @property reason Which check refused it.
     */
    data class CountBlocked(val reason: BlockReason) : DomainEvent

    /** The operator scanned an Rx label and chose to go ahead with the count it describes. */
    data object RxLabelProceeded : DomainEvent

    /**
     * An expiry warning was shown to the operator and they answered it.
     *
     * No Android flow posts this yet — the app has no expiry warning step. The event exists so the
     * consumer side is complete, and so adding that step later is one `tryPost` call.
     *
     * @property reason Why the warning was raised.
     * @property outcome What the operator chose.
     */
    data class ExpiryWarningResolved(
        val reason: ExpiryReason,
        val outcome: ExpiryOutcome,
    ) : DomainEvent

    /** Why a count was refused — see [CountBlocked]. */
    enum class BlockReason {
        /** The scanned container is a different drug than the order expects. */
        WRONG_DRUG,

        /** The scanned container is past its expiry date. */
        EXPIRED,
    }

    /** Why an expiry warning was raised — see [ExpiryWarningResolved]. */
    enum class ExpiryReason {
        /** The expiry date has already passed. */
        ALREADY_EXPIRED,

        /** The stock expires before the patient would finish the prescribed course. */
        BEFORE_DOSAGE_END,

        /** The expiry date is close, but not yet passed. */
        NEAR_EXPIRY,
    }

    /** What the operator chose when answering an expiry warning — see [ExpiryWarningResolved]. */
    enum class ExpiryOutcome {
        PROCEEDED,
        CANCELLED,
    }
}
