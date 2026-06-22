package com.rite.pillcounting.feature.profile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUpdateUiStateTest {

    @Test
    fun idle_isProfileUpdateUiState() {
        assertTrue(ProfileUpdateUiState.Idle is ProfileUpdateUiState)
    }

    @Test
    fun loading_isProfileUpdateUiState() {
        assertTrue(ProfileUpdateUiState.Loading is ProfileUpdateUiState)
    }

    @Test
    fun success_isProfileUpdateUiState() {
        assertTrue(ProfileUpdateUiState.Success is ProfileUpdateUiState)
    }

    @Test
    fun error_holdsMessage() {
        assertEquals("failed", ProfileUpdateUiState.Error("failed").message)
    }

    @Test
    fun error_equals_and_hashCode() {
        val a = ProfileUpdateUiState.Error("failed")
        val b = ProfileUpdateUiState.Error("failed")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, ProfileUpdateUiState.Error("other"))
    }

    @Test
    fun error_toString_and_copy_and_component() {
        val a = ProfileUpdateUiState.Error("failed")
        assertTrue(a.toString().contains("failed"))
        assertEquals("other", a.copy(message = "other").message)
        assertEquals("failed", a.component1())
    }
}
