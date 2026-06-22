package com.rite.pillcounting.feature.dashboard.domain.model

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDetailResponseTest {

    private val data: UserDetail = mockk(relaxed = true)

    private fun sample() = UserDetailResponse(
        status = 200,
        isSuccess = true,
        message = "ok",
        token = "abc-token",
        data = data,
    )

    @Test
    fun getters_returnConstructorValues() {
        val r = sample()
        assertEquals(200, r.status)
        assertEquals(true, r.isSuccess)
        assertEquals("ok", r.message)
        assertEquals("abc-token", r.token)
        assertSame(data, r.data)
    }

    @Test
    fun nullableFields_acceptNulls() {
        val r = UserDetailResponse(null, null, null, null, null)
        assertNull(r.status)
        assertNull(r.isSuccess)
        assertNull(r.message)
        assertNull(r.token)
        assertNull(r.data)
    }

    @Test
    fun equalsAndHashCode() {
        val a = sample()
        val b = sample()
        val c = a.copy(status = 500)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun toString_containsClassName() {
        assertTrue(sample().toString().contains("UserDetailResponse"))
    }

    @Test
    fun copy_overridesField() {
        val copy = sample().copy(message = "changed", token = null)
        assertEquals("changed", copy.message)
        assertNull(copy.token)
        assertEquals(200, copy.status)
    }

    @Test
    fun componentFunctions() {
        val r = sample()
        assertEquals(200, r.component1())
        assertEquals(true, r.component2())
        assertEquals("ok", r.component3())
        assertEquals("abc-token", r.component4())
        assertSame(data, r.component5())
    }
}
