package com.rite.pillcounting.feature.dispenseFlow.domain.data

sealed interface NavigationEvent {

    data object NavigateToDashboard : NavigationEvent

    data class NavigateToBatch(val batchId: Long) : NavigationEvent
}