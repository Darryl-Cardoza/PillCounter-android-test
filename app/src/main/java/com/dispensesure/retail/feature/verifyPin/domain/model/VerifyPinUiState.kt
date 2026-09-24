package com.dispensesure.retail.feature.verifyPin.domain.model

/**
 * Defines the possible states for the OTP Verification screen UI.
 *
 * This sealed interfaceDetail acts as a state machine, ensuring the UI is always in a
 * predictable and well-defined state.
 */
sealed interface VerifyPinUiState {
    /** The initial state before any verification attempt. */
    data object Idle : VerifyPinUiState

    /** Represents the in-progress state of the OTP verification network call. */
    data object Loading : VerifyPinUiState

    /** Indicates that the OTP was successfully verified. */
    data object Success : VerifyPinUiState

    /** Represents an error state, containing a message for the user. */
    data class Error(val message: String) : VerifyPinUiState
}
