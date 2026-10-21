package com.dispensesure.retail.core.audit.read

import com.dispensesure.retail.core.audit.model.AuditRecord
import com.dispensesure.retail.core.audit.model.AuditResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditRecordFormatterTest {

    @Test
    fun `record renders as a decorated block`() {
        val record = AuditRecord(
            eventId = "e-1", seq = 3, timestamp = "2026-10-21T14:03:22.481Z", sessionId = "s-1",
            userId = "1042", email = null, operatorId = 7, deviceKey = "dev-1", terminalId = "T-03",
            appVersion = "2.4.1 (241)", tag = "DEVICE_INTEGRITY_CHECK_FAILED", result = AuditResult.FAILURE,
            data = buildJsonObject {
                put("failed_checks", JsonArray(listOf(JsonPrimitive("EMULATOR"))))
                put("installer", "unknown")
            }
        )
        val expected = """
            ┌ 2026-10-21 14:03:22.481 UTC   #3   DEVICE_INTEGRITY_CHECK_FAILED   ✖ FAILURE
            │ session   s-1
            │ user      1042   operator  7
            │ device    dev-1   terminal  T-03   app  2.4.1 (241)
            │ data      failed_checks=[EMULATOR]  installer=unknown
            └──
        """.trimIndent()
        assertEquals(expected, AuditRecordFormatter.format(AuditLogEntry.Record(record)))
    }

    @Test
    fun `missing values render as a dash`() {
        val record = AuditRecord(
            eventId = "e-2", seq = 1, timestamp = "2026-10-21T00:00:00Z", sessionId = "s-1",
            userId = null, email = null, operatorId = null, deviceKey = null, terminalId = null,
            appVersion = "2.4.1 (241)", tag = "X", result = AuditResult.SUCCESS, data = buildJsonObject { }
        )
        val text = AuditRecordFormatter.format(AuditLogEntry.Record(record))
        assertTrue(text.contains("✔ SUCCESS"))
        assertTrue(text.contains("│ user      —   operator  —"))
        assertTrue(text.contains("│ data      —"))
    }

    @Test
    fun `unreadable entries render as warnings`() {
        assertEquals("⚠ unreadable line 4 in audit-logs.log", AuditRecordFormatter.format(AuditLogEntry.UnreadableLine("audit-logs.log", 4)))
        assertEquals("⚠ unreadable file audit-logs-1.unreadable (older key)", AuditRecordFormatter.format(AuditLogEntry.UnreadableFile("audit-logs-1.unreadable")))
    }
}
