package com.rite.pillcounting.feature.dashboard.domain.model

import com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto
import com.rite.pillcounting.core.room.models.dtos.PillCountWithDrugAndTotal

/**
 * A single row in the dashboard's "Today's Queue" list.
 *
 * The queue is a merge of two underlying sources:
 *  - Dispense (FIXED) partial transactions   → [Dispense]
 *  - Inventory (REGULAR) in-progress batches → [Inventory]
 *
 * The variants intentionally keep the original DTOs as fields so the UI layer
 * has full access to drug metadata / batch metadata without re-querying.
 *
 * Sorting is performed in the ViewModel via [createdAt].
 */
sealed class QueueItem {
    abstract val createdAt: Long
    abstract val bucketId: String?

    /** Whether this row should be tagged "340B" in the UI. */
    val is340B: Boolean get() = !bucketId.isNullOrBlank()

    data class Dispense(
        val txn: PillCountWithDrugAndTotal,
        val isHazardous: Boolean,
        val isHighPriority: Boolean,
        val isControlled: Boolean,
    ) : QueueItem() {
        override val createdAt: Long get() = txn.createdAt
        override val bucketId: String? get() = txn.bucketId
    }

    data class Inventory(
        val batch: BatchSummaryDto,
    ) : QueueItem() {
        override val createdAt: Long get() = batch.createdAt
        override val bucketId: String? get() = batch.bucketId
    }
}
