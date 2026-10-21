package com.dispensesure.retail.core.audit.read

import com.dispensesure.retail.core.audit.crypto.AuditRecordCipher
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.model.AuditRecord
import com.dispensesure.retail.core.audit.model.AuditResult
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

class AuditLogReaderTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_reader_test_${System.nanoTime()}")
    private val cipher = AuditRecordCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private val fileStore = AuditLogFileStore(directory)
    private val reader = AuditLogReader(fileStore, cipher)

    private fun record(seq: Long) = AuditRecord(
        eventId = "e-$seq", seq = seq, timestamp = "2026-10-21T00:00:00Z", sessionId = "s-1",
        userId = null, email = null, operatorId = null, deviceKey = null, terminalId = null,
        appVersion = "2.4.1 (241)", tag = "X", result = AuditResult.SUCCESS, data = buildJsonObject { }
    )

    @Test
    fun `reads records oldest first and flags a bad line`() {
        fileStore.append(cipher.encrypt(AuditJson.encodeRecord(record(1))))
        fileStore.append("v1:not-valid")
        fileStore.append(cipher.encrypt(AuditJson.encodeRecord(record(2))))

        val entries = reader.readAllOldestFirst()

        assertEquals(
            listOf(
                AuditLogEntry.Record(record(1)),
                AuditLogEntry.UnreadableLine("audit-logs.log", 2),
                AuditLogEntry.Record(record(2))
            ),
            entries
        )
    }

    @Test
    fun `unreadable files are listed first`() {
        fileStore.append(cipher.encrypt(AuditJson.encodeRecord(record(1))))
        fileStore.markAllUnreadable()
        fileStore.append(cipher.encrypt(AuditJson.encodeRecord(record(2))))

        val entries = reader.readAllOldestFirst()

        assertEquals(2, entries.size)
        assertTrue(entries.first() is AuditLogEntry.UnreadableFile)
        assertEquals(AuditLogEntry.Record(record(2)), entries.last())
    }
}
