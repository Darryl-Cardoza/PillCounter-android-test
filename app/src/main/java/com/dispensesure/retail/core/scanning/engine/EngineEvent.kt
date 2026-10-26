package com.dispensesure.retail.core.scanning.engine

/** One-off reports from the engine. The flow view model decides what to save. */
sealed interface EngineEvent {
    data object GlovesConfirmed : EngineEvent
    data class HazardousTrayResult(val detected: Boolean) : EngineEvent
}
