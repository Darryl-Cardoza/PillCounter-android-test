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
    val isHazardous: Boolean = false,
    /** GS1 serial number (AI 21) of the scanned unit, if the label carried one. */
    val serialNo: String? = null,
) {
    val totalPills: Int get() = pillsPerBottle * bottles
}
