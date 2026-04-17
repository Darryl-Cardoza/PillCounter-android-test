package com.rite.pillcounting.feature.countResume.domain.model

import com.rite.pillcounting.feature.countResume.presentation.PartialBatchItem

/**
 * Represents the UI state for the Partial Counts screen.
 *
 * @property batches List of partial batch items from the database.
 * @property isLoading Indicates if data is being loaded.
 * @property error Error message if any operation fails.
 */
data class PartialCountsUiState(
    val batches: List<PartialBatchItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

