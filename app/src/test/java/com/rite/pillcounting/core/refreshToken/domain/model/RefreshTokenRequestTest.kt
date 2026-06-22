package com.rite.pillcounting.core.refreshToken.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshTokenRequestTest {

    private fun model() = RefreshTokenRequest(refreshToken = "rt1")

    @Test
    fun getter_returnsValue() {
        assertEquals("rt1", model().refreshToken)
    }

    @Test
    fun equalsHashCode_equal() {
        assertEquals(model(), model())
        assertEquals(model().hashCode(), model().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(model(), model().copy(refreshToken = "x"))
    }

    @Test
    fun toString_containsField() {
        assertTrue(model().toString().contains("rt1"))
    }

    @Test
    fun copy_overrides() {
        assertEquals("x", model().copy(refreshToken = "x").refreshToken)
    }

    @Test
    fun componentN_returnValue() {
        assertEquals("rt1", model().component1())
    }
}
