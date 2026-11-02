package com.dispensesure.retail.feature.dispense.domain.model

import androidx.annotation.StringRes

/** One-off things the screen does for the flow view model. */
sealed interface DispenseFlowEffect {
    // A scan was turned away. Toast if given; then read again at once, or after the 1.5 s pause.
    data class ScanRejected(@StringRes val toastText: Int?, val pauseScanning: Boolean) : DispenseFlowEffect

    // A failed scan, save or resume: the generic error text.
    data object GenericError : DispenseFlowEffect

    // The vial label's Rx matched the txn: the count view model captures the still.
    data object VialLabelMatched : DispenseFlowEffect

    data object LeaveToDashboard : DispenseFlowEffect
}
