package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
        installer = "unknown"
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
    fun `one event from each area serializes with its tag`() {
        val eventsByTag = mapOf(
            "LOGIN_SUCCEEDED" to LoginSucceeded("9", "a@b.c", "admin", "dev", "2.4.1", rememberMe = true),
            "SCREEN_LOCKED" to ScreenLocked(ScreenLockReason.MENU),
            "PROFILE_UPDATED" to ProfileUpdated(listOf(FieldChange("phone_number", "1", "2"))),
            "HISTORY_BULK_DELETED" to HistoryBulkDeleted(0L, 1L, "ALL", 1, listOf(1L), emptyList()),
            "LOCAL_DATA_CLEARED" to LocalDataCleared(mapOf("dispense_txn" to 2), listOf(1L), emptyList()),
            "OFFLINE_MODE_EXITED" to OfflineModeExited(OfflineEndedBy.BACK_ONLINE, "2026-10-21T00:00:00Z", 30L),
            "BATCH_SYNCED" to BatchSynced(5L, SendTrigger.FIRST_SEND, BatchSyncOutcome.COMPLETE, null, null),
            "HL7_DISPENSE_SENT" to Hl7DispenseSent(1L, "1", SendTrigger.AUTO_RESEND, Hl7SendOutcome.REJECTED),
        )
        eventsByTag.forEach { (expectedTag, event) ->
            assertEquals(expectedTag, AuditJson.tagAndData(event).first)
        }
    }

    @Test
    fun `setting events write setting, old and new as top-level fields`() {
        val (_, data) = AuditJson.tagAndData(ServerConfigChanged(ServerSetting.PMS_PORT, "0", "6661"))
        assertEquals(
            buildJsonObject { put("setting", "PMS_PORT"); put("old", "0"); put("new", "6661") },
            data
        )
    }

    @Test
    fun `a standalone RX_SCANNED writes txn_id as JSON null`() {
        val (_, data) = AuditJson.tagAndData(RxScanned(txnId = null, labelNdc = "NDC123", qty = 10))
        assertEquals(JsonNull, data["txn_id"])
    }

    @Test
    fun `events default to SUCCESS and failure events override it`() {
        assertEquals(AuditResult.SUCCESS, ScreenLocked(ScreenLockReason.MENU).result)
        assertEquals(AuditResult.FAILURE, integrityEvent.result)
        assertEquals(AuditResult.FAILURE, FaceVerifyFailed(bestScore = 0.4).result)
        assertEquals(AuditResult.FAILURE, DispenseSyncAbandoned(txnId = 1L, controlId = null).result)
    }

    @Test
    fun `a send outcome decides the record result`() {
        assertEquals(AuditResult.SUCCESS, Hl7DispenseSent(1L, null, SendTrigger.FIRST_SEND, Hl7SendOutcome.ACCEPTED).result)
        assertEquals(AuditResult.FAILURE, BatchSynced(1L, SendTrigger.FIRST_SEND, BatchSyncOutcome.STOPPED, 1, 3).result)
    }

    @Test
    fun `record survives an encode and decode round trip`() {
        val record = AuditRecord(
            eventId = "e-1", seq = 3, timestamp = "2026-10-21T14:03:22.481Z", sessionId = "s-1",
            userId = null, email = "a@b.c", operatorProfileId = null, operatorName = null, deviceKey = "dev-1", terminalId = null,
            appVersion = "2.4.1 (241)", tag = "DEVICE_INTEGRITY_CHECK_FAILED", result = AuditResult.FAILURE,
            data = buildJsonObject { put("installer", "unknown") }
        )
        assertEquals(record, AuditJson.decodeRecord(AuditJson.encodeRecord(record)))
    }
}
