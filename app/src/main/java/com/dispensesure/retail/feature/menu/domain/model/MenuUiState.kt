package com.dispensesure.retail.feature.menu.domain.model

/**
 * Represents aggregated counts for the Menu screen.
 *
 * Holds the number of completed dispenses and batches not yet synced.
 */
data class MenuUiState(
    val unsyncedTransactionCount: Int = 0
)
