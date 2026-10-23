package com.dispensesure.retail.core.audit.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeyReplyTest {

    @Test
    fun `reads code, fingerprint and next free version from data`() {
        val reply = DeviceKeyReply.parse(
            409, """{"data":{"code":"version_conflict","fingerprint":"ab12","next_free_version":3}}"""
        )
        assertEquals(DeviceKeyReply(409, "version_conflict", "ab12", 3), reply)
        assertTrue(reply.isVersionConflict())
    }

    @Test
    fun `an unreadable body gives just the status`() {
        assertEquals(DeviceKeyReply(503), DeviceKeyReply.parse(503, "<html>busy</html>"))
    }

    @Test
    fun `201 and 200 duplicate_replay are stored, other 200s are not`() {
        assertTrue(DeviceKeyReply(201, "stored").isStored())
        assertTrue(DeviceKeyReply(200, "duplicate_replay").isStored())
        assertFalse(DeviceKeyReply(200, "something_else").isStored())
    }

    @Test
    fun `a version conflict without a next free version is not resendable`() {
        assertFalse(DeviceKeyReply(409, "version_conflict").isVersionConflict())
    }
}
