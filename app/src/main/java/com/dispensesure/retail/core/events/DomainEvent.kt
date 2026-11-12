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
     * Posted whenever a line is written, rewritten or removed — a transaction keeps accumulating
     * after its first line, so each event announces that its totals may have moved. The consumer
     * re-reads them and reconciles against what it already counted.
     *
     * @property stockTxnId [com.dispensesure.retail.core.room.models.StockTxnEntity.txnId] the line
     *   belongs to.
     * @property bottleId [com.dispensesure.retail.core.room.models.StockBottleInfoEntity.bottleId]
     *   of the line just written.
     */
    data class StockBottleRecorded(val stockTxnId: Long, val bottleId: Long) : DomainEvent

    /**
     * An inventory batch was closed by the operator — END COUNT, with at least one counted drug.
     *
     * Separate from [StockBottleRecorded] because it is the only point at which the time a count
     * took is known. A stock transaction is created already COMPLETED alongside its first bottle
     * line, so no transaction has an open interval of its own; the batch does, and it ends here.
     *
     * @property batchId [com.dispensesure.retail.core.room.models.StockBatchEntity.batchId] closed.
     */
    data class StockBatchCompleted(val batchId: Long) : DomainEvent

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
