package com.dispensesure.retail.feature.login.domain.model

/**
 * Defines the possible states for the Logout operation UI.
 */
sealed interface LogoutUiState {
    /** The initial or default state. */
    data object Idle : LogoutUiState
    /** Indicates an ongoing logout operation. */
    data object Loading : LogoutUiState
    /** Indicates that the logout operation completed successfully. */
    data object Success : LogoutUiState
    /** Represents an error state, containing a message to be displayed to the user. */
    data class Error(val message: String) : LogoutUiState
}
