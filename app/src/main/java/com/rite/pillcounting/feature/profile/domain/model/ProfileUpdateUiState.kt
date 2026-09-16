package com.rite.pillcounting.feature.profile.domain.model

/**
 * UI state for profile update operations.
 */
sealed class ProfileUpdateUiState {
    object Idle : ProfileUpdateUiState()
    object Loading : ProfileUpdateUiState()
    object Success : ProfileUpdateUiState()
    data class Error(val message: String) : ProfileUpdateUiState()
}
