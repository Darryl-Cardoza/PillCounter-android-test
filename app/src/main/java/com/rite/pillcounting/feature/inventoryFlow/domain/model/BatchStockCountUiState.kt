package com.rite.pillcounting.feature.inventoryFlow.domain.model

/**
 * UI state for the redesigned Batch Stock Count side panel.
 *
 * Two visual states share the same top "Recent Counts" list:
 *  - Active NDC: a freshly scanned (or rescanned) NDC sits in the bottom card with a
 *    +/- counter, CLEAR and ADD actions.
 *  - Summary: when no NDC is active (initial state, or after ADD/CLEAR), the bottom
 *    card switches to totals and an END COUNT action.
 */
data class BatchStockCountUiState(
    val recentCounts: List<RecentBatchRow>,
    val activeNdc: ActiveNdc?,
    val totalNdcs: Int,
    val totalPills: Int,
)

/** One committed NDC row in the "Recent Counts" list. Newest first. */
data class RecentBatchRow(
    val ndc: String,
    val drugName: String,
    val pills: Int,
    val bottles: Int,
)

/**
 * The just-scanned NDC sitting in the bottom card. Count starts at 1 and never
 * goes below 1 — CLEAR is the only way to remove an active NDC. ADD commits it
 * into [BatchStockCountUiState.recentCounts] and clears the active slot.
 */
data class ActiveNdc(
    val ndc: String,
    val drugName: String,
    val bucket: String,
    val batchNo: String,
    val expiry: String,
    val pillsPerBottle: Int,
    val bottles: Int,
    /** Loose/open pills already committed for this NDC in the current batch. */
    val openPills: Int = 0,
    val isHazardous: Boolean = false,
    /** GS1 serial number (AI 21) of the scanned unit, if the label carried one. */
    val serialNo: String? = null,
    /**
     * True when [bottles] is a sum across MORE THAN ONE sealed transaction (e.g. a
     * serial/lot variant + a plain one). The counter then can't be attributed to a
     * single transaction, so the value must NOT be persisted as one — per-row edits
     * go through the Edit Details panel instead.
     */
    val aggregated: Boolean = false,
) {
    val totalPills: Int get() = pillsPerBottle * bottles + openPills
}

/**
 * Backing model for the "Edit Details" panel opened from the scanned-drug card's
 * edit icon. Aggregates every transaction of the active drug in the current batch
 * into two editable lists:
 *  - [sealedBottles]: one row per (lot, expiry) that carries a sealed bottle count.
 *  - [openPills]: one row per (lot, expiry) that carries a loose/open pill count.
 *
 * Each row is bound to its source [EditBatchRow.txnId] so edits and removals map
 * back to the exact transaction on SAVE. A single transaction may surface in both
 * lists when it holds both a bottle count and a loose count.
 */
data class EditDrugDetails(
    val ndc: String,
    val drugName: String,
    val bucket: String,
    val sealedBottles: List<EditBatchRow>,
    val openPills: List<EditBatchRow>,
) {
    val sealedTotal: Int get() = sealedBottles.sumOf { it.qty }
    val openTotal: Int get() = openPills.sumOf { it.qty }
}

/** One editable (lot, expiry) row inside [EditDrugDetails]. */
data class EditBatchRow(
    val txnId: Long,
    val batchNo: String,
    val expiry: String,
    val qty: Int,
)
