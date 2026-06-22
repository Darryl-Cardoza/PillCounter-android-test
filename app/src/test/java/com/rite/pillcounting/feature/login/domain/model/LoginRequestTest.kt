package com.rite.pillcounting.feature.login.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRequestTest {

    @Test
    fun getter_returnsEmail() {
        val request = LoginRequest(email = "user@example.com")
        assertEquals("user@example.com", request.email)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = LoginRequest("user@example.com")
        val b = LoginRequest("user@example.com")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        val a = LoginRequest("user@example.com")
        val b = LoginRequest("other@example.com")
        assertNotEquals(a, b)
    }

    @Test
    fun toString_containsEmail() {
        assertTrue(LoginRequest("user@example.com").toString().contains("user@example.com"))
    }

    @Test
    fun copy_overridesEmail() {
        val a = LoginRequest("user@example.com")
        val b = a.copy(email = "new@example.com")
        assertEquals("new@example.com", b.email)
    }

    @Test
    fun componentN_returnsValue() {
        val a = LoginRequest("user@example.com")
        assertEquals("user@example.com", a.component1())
    }
}
