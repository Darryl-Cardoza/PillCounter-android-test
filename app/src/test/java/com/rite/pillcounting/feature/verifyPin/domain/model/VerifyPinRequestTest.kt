package com.rite.pillcounting.feature.verifyPin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyPinRequestTest {

    /**
     * The request carries install identity (fcm token, device key, platform, app version)
     * alongside the credentials, so every case here builds a complete payload via this helper
     * rather than repeating six arguments.
     */
    private fun request(
        email: String = "user@example.com",
        otp: String = "1234",
        fcmToken: String = "fcm-token",
        deviceKey: String = "device-key",
        platform: String = "android",
        appVersion: String = "1.0.0"
    ) = VerifyPinRequest(
        email = email,
        otp = otp,
        fcmToken = fcmToken,
        deviceKey = deviceKey,
        platform = platform,
        appVersion = appVersion
    )

    @Test
    fun getters_returnValues() {
        val subject = request()
        assertEquals("user@example.com", subject.email)
        assertEquals("1234", subject.otp)
        assertEquals("fcm-token", subject.fcmToken)
        assertEquals("device-key", subject.deviceKey)
        assertEquals("android", subject.platform)
        assertEquals("1.0.0", subject.appVersion)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = request()
        val b = request()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(request(), request(email = "other@example.com", otp = "9999"))
    }

    /** Two installs verifying the same credentials must not compare equal. */
    @Test
    fun equals_notEqualForDifferentDeviceKey() {
        assertNotEquals(request(), request(deviceKey = "another-device"))
    }

    @Test
    fun toString_containsValues() {
        assertTrue(request().toString().contains("otp=1234"))
    }

    @Test
    fun copy_overridesValue() {
        assertEquals("9999", request().copy(otp = "9999").otp)
    }

    @Test
    fun componentN_returnValues() {
        val a = request()
        assertEquals("user@example.com", a.component1())
        assertEquals("1234", a.component2())
        assertEquals("fcm-token", a.component3())
        assertEquals("device-key", a.component4())
        assertEquals("android", a.component5())
        assertEquals("1.0.0", a.component6())
    }
}
