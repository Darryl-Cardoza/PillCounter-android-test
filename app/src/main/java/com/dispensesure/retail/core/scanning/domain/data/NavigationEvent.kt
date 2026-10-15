package com.dispensesure.retail.core.scanning.domain.data

sealed interface NavigationEvent {

    data object NavigateToDashboard : NavigationEvent
}
