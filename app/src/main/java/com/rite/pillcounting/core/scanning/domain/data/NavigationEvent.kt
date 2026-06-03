package com.rite.pillcounting.core.scanning.domain.data

sealed interface NavigationEvent {

    data object NavigateToDashboard : NavigationEvent

    data class NavigateToBatch(val batchId: Long) : NavigationEvent
}