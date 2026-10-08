package com.dispensesure.retail.feature.verifyPin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedUserTest {

    private fun sample() = VerifiedUser(
        userId = "u1",
        email = "user@example.com",
        isVerified = true,
        role = "admin",
        authIsLocked = false,
        isHl7Enabled = true
    )

    @Test
    fun getters_returnValues() {
        val u = sample()
        assertEquals("u1", u.userId)
        assertEquals("user@example.com", u.email)
        assertEquals(true, u.isVerified)
        assertEquals("admin", u.role)
        assertEquals(false, u.authIsLocked)
        assertEquals(true, u.isHl7Enabled)
    }

    @Test
    fun defaults_areNull() {
        val u = VerifiedUser()
        assertNull(u.userId)
        assertNull(u.email)
        assertNull(u.isVerified)
        assertNull(u.role)
        assertNull(u.authIsLocked)
        assertNull(u.isHl7Enabled)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(sample(), sample().copy(userId = "u2"))
    }

    @Test
    fun toString_containsValues() {
        assertTrue(sample().toString().contains("userId=u1"))
    }

    @Test
    fun parsesBackendOtpResponse_withStringRole() {
        val json = """{"status":200,"is_success":true,"message":"OTP verified successfully.",
            "data":{"access_token":"a","refresh_token":"r","expires_in":86400,
            "user":{"user_id":"u1","email":"heth@rite.com","country":"US","state":"NY",
            "is_verified":true,"is_profile_completed":true,"role":"admin"}}}"""
        val response = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
            .adapter(VerifyPinResponse::class.java).fromJson(json)
        assertEquals("admin", response?.data?.user?.role)
        assertEquals("a", response?.data?.accessToken)
    }

    @Test
    fun copy_overridesValue() {
        assertEquals("u2", sample().copy(userId = "u2").userId)
    }

    @Test
    fun componentN_returnValues() {
        val u = sample()
        assertEquals("u1", u.component1())
        assertEquals("user@example.com", u.component2())
        assertEquals(true, u.component3())
        assertEquals("admin", u.component4())
        assertEquals(false, u.component5())
        assertEquals(true, u.component6())
    }
}
