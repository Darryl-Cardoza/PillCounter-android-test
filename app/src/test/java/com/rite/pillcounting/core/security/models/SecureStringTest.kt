package com.rite.pillcounting.core.security.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureStringTest {

    @Test
    fun getter_returnsValue() {
        assertEquals("secret", SecureString("secret").value)
    }

    @Test
    fun getter_nullValue() {
        assertNull(SecureString(null).value)
    }

    @Test
    fun equals_hashCode_equal() {
        val a = SecureString("x")
        val b = SecureString("x")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a)
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(SecureString("x"), SecureString("y"))
        assertNotEquals(SecureString("x"), SecureString(null))
    }

    @Test
    fun toString_containsValue() {
        assertTrue(SecureString("x").toString().contains("x"))
    }

    @Test
    fun copy_overrides() {
        assertEquals(SecureString("y"), SecureString("x").copy(value = "y"))
    }

    @Test
    fun componentN() {
        assertEquals("x", SecureString("x").component1())
    }
}
