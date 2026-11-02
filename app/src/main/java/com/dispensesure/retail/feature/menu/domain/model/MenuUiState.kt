package com.dispensesure.retail.feature.menu.domain.model

/**
 * Represents aggregated counts for the Menu screen.
 *
 * Contains the unsynced transaction count shown on the sync row.
 */
data class MenuUiState(
    val unsyncedTransactionCount: Int = 0
)
