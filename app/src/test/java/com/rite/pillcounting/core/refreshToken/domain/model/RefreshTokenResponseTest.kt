package com.rite.pillcounting.core.refreshToken.domain.model

import com.rite.pillcounting.core.models.ErrorDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshTokenResponseTest {

    private fun full() = RefreshTokenResponse(
        status = 200,
        isSuccess = true,
        message = "ok",
        accessToken = "at",
        refreshToken = "rt",
        expiresIn = 3600,
        token = "tok",
        data = "payload",
        detail = listOf(ErrorDetail(msg = "bad"))
    )

    @Test
    fun defaults_areNull() {
        val r = RefreshTokenResponse()
        assertNull(r.status)
        assertNull(r.isSuccess)
        assertNull(r.message)
        assertNull(r.accessToken)
        assertNull(r.refreshToken)
        assertNull(r.expiresIn)
        assertNull(r.token)
        assertNull(r.data)
        assertNull(r.detail)
    }

    @Test
    fun getters_returnValues() {
        val r = full()
        assertEquals(200, r.status)
        assertEquals(true, r.isSuccess)
        assertEquals("ok", r.message)
        assertEquals("at", r.accessToken)
        assertEquals("rt", r.refreshToken)
        assertEquals(3600, r.expiresIn)
        assertEquals("tok", r.token)
        assertEquals("payload", r.data)
        assertEquals(listOf(ErrorDetail(msg = "bad")), r.detail)
    }

    @Test
    fun equalsHashCode_equal() {
        assertEquals(full(), full())
        assertEquals(full().hashCode(), full().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(full(), full().copy(status = 500))
    }

    @Test
    fun toString_containsField() {
        assertTrue(full().toString().contains("ok"))
    }

    @Test
    fun copy_overrides() {
        assertEquals(500, full().copy(status = 500).status)
    }

    @Test
    fun componentN_returnValues() {
        val r = full()
        assertEquals(200, r.component1())
        assertEquals(true, r.component2())
        assertEquals("ok", r.component3())
        assertEquals("at", r.component4())
        assertEquals("rt", r.component5())
        assertEquals(3600, r.component6())
        assertEquals("tok", r.component7())
        assertEquals("payload", r.component8())
        assertEquals(listOf(ErrorDetail(msg = "bad")), r.component9())
    }
}
