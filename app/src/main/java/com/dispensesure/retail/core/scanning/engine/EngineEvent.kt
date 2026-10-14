package com.dispensesure.retail.core.scanning.engine

import androidx.annotation.StringRes

/** One-off reports from the engine. The flow view model decides what to save or show. */
sealed interface EngineEvent {
    data object GlovesConfirmed : EngineEvent
    data class HazardousTrayResult(val detected: Boolean) : EngineEvent
    // Tray warning text; the flow shows it.
    data class Warning(@StringRes val text: Int) : EngineEvent
}
