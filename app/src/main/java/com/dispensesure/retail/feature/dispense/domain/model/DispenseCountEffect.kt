package com.dispensesure.retail.feature.dispense.domain.model

import androidx.annotation.StringRes

/** One-off things the screen does for the count view model. */
sealed interface DispenseCountEffect {
    data class ShowToast(@StringRes val text: Int) : DispenseCountEffect

    // The dispense is complete: back to the queue, or the Dashboard when it is empty.
    data object Finished : DispenseCountEffect
}
