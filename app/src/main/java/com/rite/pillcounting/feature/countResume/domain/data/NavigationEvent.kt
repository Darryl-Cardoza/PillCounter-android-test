package com.rite.pillcounting.feature.countResume.domain.data

import com.rite.pillcounting.core.room.models.enums.CountType

/**
 * Defines one-time navigation events sent from the ViewModel to the UI.
 */
sealed interface NavigationEvent {
    data class NavigateToPillCount(val countType: CountType) : NavigationEvent

    data class NavigateToDispenseScan(val countType: CountType, val fromResume: Boolean = false) : NavigationEvent
    data object NavigateBack : NavigationEvent
}