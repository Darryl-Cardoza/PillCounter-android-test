package com.dispensesure.retail.core.audit.read

import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.model.AuditRecord
import com.dispensesure.retail.core.audit.model.AuditResult
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditOutbox
import com.dispensesure.retail.core.audit.store.InMemorySegmentSlot
import com.dispensesure.retail.core.audit.store.MutableTestClock
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

class AuditLogReaderTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_reader_test_${System.nanoTime()}")

    private fun newFileStore(maxFileBytes: Long = AuditLogConfig.MAX_FILE_BYTES) = AuditLogFileStore(
        directory, InMemorySegmentSlot(), AuditOutbox(directory),
        MutableTestClock(Instant.parse("2026-10-21T10:00:00Z")), maxFileBytes
    )

    private fun record(seq: Long) = AuditRecord(
        eventId = "e-$seq", seq = seq, timestamp = "2026-10-21T00:00:00Z", sessionId = "s-1",
        userId = null, email = null, operatorProfileId = null, operatorName = null, deviceKey = null, terminalId = null,
        appVersion = "2.4.1 (241)", tag = "X", result = AuditResult.SUCCESS, data = buildJsonObject { }
    )

    @Test
    fun `reads records oldest first and skips a line cut by a kill`() {
        val fileStore = newFileStore()
        fileStore.append(AuditJson.encodeRecord(record(1)))
        fileStore.append("{cut by a kill")
        fileStore.append(AuditJson.encodeRecord(record(2)))

        assertEquals(listOf(record(1), record(2)), AuditLogReader(fileStore).readAllOldestFirst())
    }

    @Test
    fun `rotated files are read before the current one`() {
        // A 1-byte cap rotates on every append after the first.
        val fileStore = newFileStore(maxFileBytes = 1)
        fileStore.append(AuditJson.encodeRecord(record(1)))
        fileStore.append(AuditJson.encodeRecord(record(2)))

        assertEquals(listOf(record(1), record(2)), AuditLogReader(fileStore).readAllOldestFirst())
    }
}
