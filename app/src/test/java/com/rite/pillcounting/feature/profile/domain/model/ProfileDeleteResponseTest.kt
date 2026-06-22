package com.rite.pillcounting.feature.profile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDeleteResponseTest {

    @Test
    fun getters_returnValues() {
        val response = ProfileDeleteResponse(status = 200, message = "ok", isSuccess = true)
        assertEquals(200, response.status)
        assertEquals("ok", response.message)
        assertTrue(response.isSuccess)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = ProfileDeleteResponse(200, "ok", true)
        val b = ProfileDeleteResponse(200, "ok", true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(
            ProfileDeleteResponse(200, "ok", true),
            ProfileDeleteResponse(500, "error", false)
        )
    }

    @Test
    fun toString_containsValues() {
        assertTrue(ProfileDeleteResponse(200, "ok", true).toString().contains("status=200"))
    }

    @Test
    fun copy_overridesValues() {
        val a = ProfileDeleteResponse(200, "ok", true)
        val b = a.copy(status = 500)
        assertEquals(500, b.status)
        assertEquals("ok", b.message)
    }

    @Test
    fun componentN_returnValues() {
        val a = ProfileDeleteResponse(200, "ok", true)
        assertEquals(200, a.component1())
        assertEquals("ok", a.component2())
        assertEquals(true, a.component3())
    }
}
