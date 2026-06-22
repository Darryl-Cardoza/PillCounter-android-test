package com.rite.pillcounting.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorResponseTest {

    private fun sample(): ErrorResponse = ErrorResponse(
        status = 400,
        isSuccess = false,
        message = "Bad Request",
        token = "tok",
        data = mapOf("field" to "error")
    )

    @Test
    fun getters_returnConstructorValues() {
        val r = sample()
        assertEquals(400, r.status)
        assertEquals(false, r.isSuccess)
        assertEquals("Bad Request", r.message)
        assertEquals("tok", r.token)
        assertEquals(mapOf("field" to "error"), r.data)
    }

    @Test
    fun nullableToken_null() {
        val r = sample().copy(token = null)
        assertNull(r.token)
    }

    @Test
    fun equals_and_hashCode_equal() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(sample(), sample().copy(status = 500))
        assertNotEquals(sample(), sample().copy(isSuccess = true))
        assertNotEquals(sample(), sample().copy(message = "x"))
        assertNotEquals(sample(), sample().copy(token = null))
        assertNotEquals(sample(), sample().copy(data = emptyMap()))
        assertNotEquals(sample(), null)
    }

    @Test
    fun toString_containsValues() {
        val s = sample().toString()
        assertTrue(s.contains("400"))
        assertTrue(s.contains("Bad Request"))
    }

    @Test
    fun copy_changesField() {
        val copied = sample().copy(status = 401)
        assertEquals(401, copied.status)
        assertEquals("Bad Request", copied.message)
    }

    @Test
    fun componentN_destructuring() {
        val (status, isSuccess, message, token, data) = sample()
        assertEquals(400, status)
        assertEquals(false, isSuccess)
        assertEquals("Bad Request", message)
        assertEquals("tok", token)
        assertEquals(mapOf("field" to "error"), data)
    }
}
