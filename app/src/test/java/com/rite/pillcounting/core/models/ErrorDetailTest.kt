package com.rite.pillcounting.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorDetailTest {

    private fun sample(): ErrorDetail = ErrorDetail(
        loc = listOf("body", "username"),
        msg = "field required",
        type = "value_error"
    )

    @Test
    fun getters_returnConstructorValues() {
        val d = sample()
        assertEquals(listOf("body", "username"), d.loc)
        assertEquals("field required", d.msg)
        assertEquals("value_error", d.type)
    }

    @Test
    fun defaultParams_areNull() {
        val d = ErrorDetail()
        assertNull(d.loc)
        assertNull(d.msg)
        assertNull(d.type)
    }

    @Test
    fun nullableFields_explicitNull() {
        val d = ErrorDetail(loc = null, msg = null, type = null)
        assertNull(d.loc)
        assertNull(d.msg)
        assertNull(d.type)
    }

    @Test
    fun equals_and_hashCode_equal() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(sample(), sample().copy(loc = null))
        assertNotEquals(sample(), sample().copy(msg = "other"))
        assertNotEquals(sample(), sample().copy(type = null))
        assertNotEquals(sample(), null)
    }

    @Test
    fun toString_containsValues() {
        val s = sample().toString()
        assertTrue(s.contains("field required"))
        assertTrue(s.contains("value_error"))
    }

    @Test
    fun copy_changesField() {
        val copied = sample().copy(msg = "changed")
        assertEquals("changed", copied.msg)
        assertEquals("value_error", copied.type)
    }

    @Test
    fun componentN_destructuring() {
        val (loc, msg, type) = sample()
        assertEquals(listOf("body", "username"), loc)
        assertEquals("field required", msg)
        assertEquals("value_error", type)
    }
}
