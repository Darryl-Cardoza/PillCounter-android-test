package com.dispensesure.retail.core.audit.upload

import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.AuditTestData
import com.dispensesure.retail.core.audit.store.OutboxSegment
import com.dispensesure.retail.core.audit.store.SegmentId
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class AuditUploadFormTest {

    private val sealedFile = File.createTempFile("sealed", ".log").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
    private val segment = OutboxSegment(SegmentId(LocalDate.parse("2026-10-21"), 1), sealedFile, AuditTestData.sampleMeta)

    @Test
    fun `text parts match the API, numbers as text`() {
        assertEquals(
            mapOf(
                "device_key" to "dev-1", "log_date" to "2026-10-21", "sequence" to "1",
                "sha256" to "a".repeat(64), "app_name" to "dispensesure", "app_version" to AuditLogger.APP_VERSION,
                "chain_last_hash" to "c".repeat(64), "dek_key_id" to "1", "dek_version" to "1",
                "event_count" to "2", "first_event_at" to "2026-10-21T10:00:00Z", "last_event_at" to "2026-10-21T11:00:00Z"
            ),
            AuditUploadForm.build(segment, sequence = 1, deviceKey = "dev-1").textParts()
        )
    }

    @Test
    fun `file part is the sealed bytes named for the sequence sent`() {
        val form = AuditUploadForm.build(segment, sequence = 4, deviceKey = "dev-1")
        val filePart = form.filePart()
        assertTrue(filePart.headers!!["Content-Disposition"]!!.contains("filename=\"2026-10-21-0004.log\""))
        assertEquals("application/octet-stream", filePart.body.contentType().toString())
        assertArrayEquals(byteArrayOf(1, 2, 3), Buffer().also { filePart.body.writeTo(it) }.readByteArray())
        assertEquals("4", form.textParts()["sequence"])
    }

    @Test
    fun `missing times are left out`() {
        val noTimes = segment.copy(meta = AuditTestData.sampleMeta.copy(firstEventAt = null, lastEventAt = null))
        val keys = AuditUploadForm.build(noTimes, sequence = 1, deviceKey = "dev-1").textParts().keys
        assertTrue("first_event_at" !in keys && "last_event_at" !in keys)
    }

    @Test
    fun `body is multipart form data`() {
        val contentType = AuditUploadForm.build(segment, sequence = 1, deviceKey = "dev-1").contentType()
        assertEquals("multipart/form-data", "${contentType.type}/${contentType.subtype}")
    }
}
