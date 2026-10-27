package com.dispensesure.retail.core.scanning.engine

import androidx.annotation.StringRes

/** One-off reports from the engine. The flow view model decides what to save or show. */
sealed interface EngineEvent {
    data object GlovesConfirmed : EngineEvent
    data class HazardousTrayResult(val detected: Boolean) : EngineEvent
    // Tray warning string; the flow shows it as a toast.
    data class Warning(@StringRes val messageRes: Int) : EngineEvent
}
