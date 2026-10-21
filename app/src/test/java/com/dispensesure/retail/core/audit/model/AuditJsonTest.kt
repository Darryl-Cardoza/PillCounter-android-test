package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuditJsonTest {

    private val integrityEvent = DeviceIntegrityCheckFailed(
        failedChecks = listOf("EMULATOR"),
        notChecked = listOf("ROOTED"),
        installer = "unknown",
        overlayPackages = emptyList()
    )

    @Test
    fun `tag is the serial name and data holds only the event fields`() {
        val (tag, data) = AuditJson.tagAndData(integrityEvent)
        assertEquals("DEVICE_INTEGRITY_CHECK_FAILED", tag)
        assertEquals(JsonArray(listOf(JsonPrimitive("EMULATOR"))), data["failed_checks"])
        assertEquals(JsonPrimitive("unknown"), data["installer"])
        assertFalse(data.containsKey("tag"))
        assertFalse(data.containsKey("result"))
    }

    @Test
    fun `record survives an encode and decode round trip`() {
        val record = AuditRecord(
            eventId = "e-1", seq = 3, timestamp = "2026-10-21T14:03:22.481Z", sessionId = "s-1",
            userId = null, email = "a@b.c", operatorId = null, deviceKey = "dev-1", terminalId = null,
            appVersion = "2.4.1 (241)", tag = "DEVICE_INTEGRITY_CHECK_FAILED", result = AuditResult.FAILURE,
            data = buildJsonObject { put("installer", "unknown") }
        )
        assertEquals(record, AuditJson.decodeRecord(AuditJson.encodeRecord(record)))
    }
}
