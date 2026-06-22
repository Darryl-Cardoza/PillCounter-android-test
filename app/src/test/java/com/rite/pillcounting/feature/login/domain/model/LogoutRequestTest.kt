package com.rite.pillcounting.feature.login.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutRequestTest {

    @Test
    fun getter_returnsRefreshToken() {
        val request = LogoutRequest(refreshToken = "token123")
        assertEquals("token123", request.refreshToken)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = LogoutRequest("token123")
        val b = LogoutRequest("token123")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(LogoutRequest("token123"), LogoutRequest("token456"))
    }

    @Test
    fun toString_containsToken() {
        assertTrue(LogoutRequest("token123").toString().contains("token123"))
    }

    @Test
    fun copy_overridesRefreshToken() {
        val a = LogoutRequest("token123")
        assertEquals("token456", a.copy(refreshToken = "token456").refreshToken)
    }

    @Test
    fun componentN_returnsValue() {
        assertEquals("token123", LogoutRequest("token123").component1())
    }
}
