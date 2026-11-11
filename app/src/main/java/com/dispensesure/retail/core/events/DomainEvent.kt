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
     * Why a count was refused — see [CountBlocked].
     *
     * Expiry is not here. The app has no expiry check that can refuse a count, so an `EXPIRED`
     * reason would be a value nothing could ever post, and the counter it fed is not on the upload
     * contract either. Add it alongside the flow that raises it.
     */
    enum class BlockReason {
        /** The scanned container is a different drug than the order expects. */
        WRONG_DRUG,
    }
}
