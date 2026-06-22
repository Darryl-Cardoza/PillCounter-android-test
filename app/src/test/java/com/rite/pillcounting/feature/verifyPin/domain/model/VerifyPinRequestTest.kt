package com.rite.pillcounting.feature.verifyPin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyPinRequestTest {

    @Test
    fun getters_returnValues() {
        val request = VerifyPinRequest(email = "user@example.com", otp = "1234")
        assertEquals("user@example.com", request.email)
        assertEquals("1234", request.otp)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = VerifyPinRequest("user@example.com", "1234")
        val b = VerifyPinRequest("user@example.com", "1234")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(
            VerifyPinRequest("user@example.com", "1234"),
            VerifyPinRequest("other@example.com", "9999")
        )
    }

    @Test
    fun toString_containsValues() {
        assertTrue(VerifyPinRequest("user@example.com", "1234").toString().contains("otp=1234"))
    }

    @Test
    fun copy_overridesValue() {
        val a = VerifyPinRequest("user@example.com", "1234")
        assertEquals("9999", a.copy(otp = "9999").otp)
    }

    @Test
    fun componentN_returnValues() {
        val a = VerifyPinRequest("user@example.com", "1234")
        assertEquals("user@example.com", a.component1())
        assertEquals("1234", a.component2())
    }
}
