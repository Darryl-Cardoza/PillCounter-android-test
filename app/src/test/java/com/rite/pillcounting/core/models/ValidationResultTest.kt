package com.rite.pillcounting.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationResultTest {

    @Test
    fun getters_returnConstructorValues() {
        val r = ValidationResult(isSuccess = false, errorMessageResId = 42)
        assertEquals(false, r.isSuccess)
        assertEquals(42, r.errorMessageResId)
    }

    @Test
    fun defaultParam_errorMessageResId_isNull() {
        val r = ValidationResult(isSuccess = true)
        assertTrue(r.isSuccess)
        assertNull(r.errorMessageResId)
    }

    @Test
    fun nullableField_explicitNull() {
        val r = ValidationResult(isSuccess = true, errorMessageResId = null)
        assertNull(r.errorMessageResId)
    }

    @Test
    fun equals_and_hashCode_equal() {
        val a = ValidationResult(false, 42)
        val b = ValidationResult(false, 42)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqual() {
        val base = ValidationResult(false, 42)
        assertNotEquals(base, base.copy(isSuccess = true))
        assertNotEquals(base, base.copy(errorMessageResId = 99))
        assertNotEquals(base, base.copy(errorMessageResId = null))
        assertNotEquals(base, null)
    }

    @Test
    fun toString_containsValues() {
        val s = ValidationResult(false, 42).toString()
        assertTrue(s.contains("false"))
        assertTrue(s.contains("42"))
    }

    @Test
    fun copy_changesField() {
        val copied = ValidationResult(false, 42).copy(isSuccess = true)
        assertTrue(copied.isSuccess)
        assertEquals(42, copied.errorMessageResId)
    }

    @Test
    fun componentN_destructuring() {
        val (isSuccess, errorMessageResId) = ValidationResult(false, 42)
        assertEquals(false, isSuccess)
        assertEquals(42, errorMessageResId)
    }
}
