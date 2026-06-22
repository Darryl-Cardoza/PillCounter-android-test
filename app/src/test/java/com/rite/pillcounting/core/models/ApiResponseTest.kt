package com.rite.pillcounting.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResponseTest {

    private fun sample(): ApiResponse<String> = ApiResponse(
        status = 200,
        isSuccess = true,
        message = "OK",
        token = "tok",
        data = "payload"
    )

    @Test
    fun getters_returnConstructorValues() {
        val r = sample()
        assertEquals(200, r.status)
        assertTrue(r.isSuccess)
        assertEquals("OK", r.message)
        assertEquals("tok", r.token)
        assertEquals("payload", r.data)
    }

    @Test
    fun nullableFields_null() {
        val r = ApiResponse<String>(
            status = 500,
            isSuccess = false,
            message = "Error",
            token = null,
            data = null
        )
        assertNull(r.token)
        assertNull(r.data)
    }

    @Test
    fun equals_and_hashCode_equal() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(sample(), sample().copy(status = 404))
        assertNotEquals(sample(), sample().copy(isSuccess = false))
        assertNotEquals(sample(), sample().copy(message = "x"))
        assertNotEquals(sample(), sample().copy(token = null))
        assertNotEquals(sample(), sample().copy(data = "other"))
        assertNotEquals(sample(), null)
    }

    @Test
    fun toString_containsValues() {
        val s = sample().toString()
        assertTrue(s.contains("200"))
        assertTrue(s.contains("OK"))
        assertTrue(s.contains("payload"))
    }

    @Test
    fun copy_changesField() {
        val copied = sample().copy(message = "changed")
        assertEquals("changed", copied.message)
        assertEquals(200, copied.status)
    }

    @Test
    fun componentN_destructuring() {
        val (status, isSuccess, message, token, data) = sample()
        assertEquals(200, status)
        assertTrue(isSuccess)
        assertEquals("OK", message)
        assertEquals("tok", token)
        assertEquals("payload", data)
    }
}
