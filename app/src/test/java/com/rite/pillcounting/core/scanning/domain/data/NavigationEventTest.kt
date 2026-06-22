package com.rite.pillcounting.core.scanning.domain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun navigateToBatch_getter() {
        val e = NavigationEvent.NavigateToBatch(batchId = 42L)
        assertEquals(42L, e.batchId)
        assertTrue(e is NavigationEvent)
    }

    @Test
    fun navigateToBatch_equalsHashCodeToStringCopyComponent() {
        val a = NavigationEvent.NavigateToBatch(1L)
        val b = NavigationEvent.NavigateToBatch(1L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, NavigationEvent.NavigateToBatch(2L))
        assertTrue(a.toString().contains("1"))
        assertEquals(2L, a.copy(batchId = 2L).batchId)
        assertEquals(1L, a.component1())
    }
}
