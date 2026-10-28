package com.dispensesure.retail.feature.dispense.domain.model

sealed interface DispenseCountNavigation {

    data object NavigateToDashboard : DispenseCountNavigation
}
