package com.rite.pillcounting.feature.profile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUpdateResponseTest {

    @Test
    fun getters_returnValues() {
        val response = ProfileUpdateResponse(status = 200, message = "ok", isSuccess = true)
        assertEquals(200, response.status)
        assertEquals("ok", response.message)
        assertTrue(response.isSuccess)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = ProfileUpdateResponse(200, "ok", true)
        val b = ProfileUpdateResponse(200, "ok", true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(
            ProfileUpdateResponse(200, "ok", true),
            ProfileUpdateResponse(500, "error", false)
        )
    }

    @Test
    fun toString_containsValues() {
        assertTrue(ProfileUpdateResponse(200, "ok", true).toString().contains("status=200"))
    }

    @Test
    fun copy_overridesValues() {
        val a = ProfileUpdateResponse(200, "ok", true)
        assertEquals(500, a.copy(status = 500).status)
    }

    @Test
    fun componentN_returnValues() {
        val a = ProfileUpdateResponse(200, "ok", true)
        assertEquals(200, a.component1())
        assertEquals("ok", a.component2())
        assertEquals(true, a.component3())
    }
}
