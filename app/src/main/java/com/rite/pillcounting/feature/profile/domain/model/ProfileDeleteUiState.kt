package com.rite.pillcounting.feature.profile.domain.model

/**
 * UI state for profile delete operations.
 */
sealed class ProfileDeleteUiState {
    object Idle : ProfileDeleteUiState()
    object Loading : ProfileDeleteUiState()
    object Success : ProfileDeleteUiState()
    data class Error(val message: String) : ProfileDeleteUiState()
}
