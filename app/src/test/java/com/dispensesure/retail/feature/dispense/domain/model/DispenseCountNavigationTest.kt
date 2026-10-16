package com.dispensesure.retail.feature.dispense.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEventTest {

    @Test
    fun navigateToDashboard_isDispenseCountNavigation() {
        val e: DispenseCountNavigation = DispenseCountNavigation.NavigateToDashboard
        assertTrue(e is DispenseCountNavigation.NavigateToDashboard)
        // data object singleton
        assertEquals(DispenseCountNavigation.NavigateToDashboard, DispenseCountNavigation.NavigateToDashboard)
    }
}
