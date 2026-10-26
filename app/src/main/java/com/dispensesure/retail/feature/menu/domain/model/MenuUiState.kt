package com.dispensesure.retail.feature.menu.domain.model

/**
 * Represents aggregated counts for the Menu screen.
 *
 * Contains completed & partial counts for both Dispense and Regular count types.
 */
data class MenuUiState(
    val dispenseCompleted: Int = 0,
    val dispensePartial: Int = 0,
    val regularCompleted: Int = 0,
    val regularPartial: Int = 0,
    val unsyncedTransactionCount: Int = 0
)
