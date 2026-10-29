package com.dispensesure.retail.core.scanning.domain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEventTest {

    @Test
    fun navigateToDashboard_isNavigationEvent() {
        val e: NavigationEvent = NavigationEvent.NavigateToDashboard
        assertTrue(e is NavigationEvent.NavigateToDashboard)
        // data object singleton
        assertEquals(NavigationEvent.NavigateToDashboard, NavigationEvent.NavigateToDashboard)
    }
}
