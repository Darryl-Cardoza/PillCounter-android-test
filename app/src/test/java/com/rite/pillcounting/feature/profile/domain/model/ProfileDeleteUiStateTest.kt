package com.rite.pillcounting.feature.profile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDeleteUiStateTest {

    @Test
    fun idle_isProfileDeleteUiState() {
        assertTrue(ProfileDeleteUiState.Idle is ProfileDeleteUiState)
    }

    @Test
    fun loading_isProfileDeleteUiState() {
        assertTrue(ProfileDeleteUiState.Loading is ProfileDeleteUiState)
    }

    @Test
    fun success_isProfileDeleteUiState() {
        assertTrue(ProfileDeleteUiState.Success is ProfileDeleteUiState)
    }

    @Test
    fun error_holdsMessage() {
        val error = ProfileDeleteUiState.Error("failed")
        assertEquals("failed", error.message)
    }

    @Test
    fun error_equals_and_hashCode() {
        val a = ProfileDeleteUiState.Error("failed")
        val b = ProfileDeleteUiState.Error("failed")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, ProfileDeleteUiState.Error("other"))
    }

    @Test
    fun error_toString_and_copy_and_component() {
        val a = ProfileDeleteUiState.Error("failed")
        assertTrue(a.toString().contains("failed"))
        assertEquals("other", a.copy(message = "other").message)
        assertEquals("failed", a.component1())
    }
}
