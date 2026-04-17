package com.rite.pillcounting.feature.barcodeScan.domain.data

/**
 * Defines one-time navigation events sent from the ViewModel to the UI.
 */
sealed interface NavigationEvent {
    data class NavigateToPillCount(val ndc: String, val type: String) : NavigationEvent
    data class NavigateToResumePillCount(val ndc: String, val type: String): NavigationEvent
    data object NavigateBack : NavigationEvent
    data class NavigateToBatch(val batchId: Long) : NavigationEvent
}