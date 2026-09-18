package com.rite.pillcounting.feature.login.domain.model

/**
 * Defines the possible states for the Login screen UI, serving as a state machine.
 * This sealed interfaceDetail ensures that the UI can only be in one of these well-defined states at a time.
 */
sealed interface LoginUiState {
    /** The initial or default state. */
    data object Idle : LoginUiState
    /** Indicates an ongoing operation, like a network request. */
    data object Loading : LoginUiState
    /** Indicates that the login operation completed successfully. */
    data object Success : LoginUiState
    /** Represents an error state, containing a message to be displayed to the user. */
    data class Error(val message: String) : LoginUiState
}
