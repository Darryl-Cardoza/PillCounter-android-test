package com.dispensesure.retail.core.audit.store

import com.dispensesure.retail.core.audit.AuditTestData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class AuditOutboxTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_outbox_test_${System.nanoTime()}")
    private val outbox = AuditOutbox(directory)
    private fun segmentId(day: String, sequence: Int) = SegmentId(LocalDate.parse(day), sequence)

    @Test
    fun `segments come back oldest first with their meta`() {
        outbox.put(segmentId("2026-10-21", 1), byteArrayOf(2), AuditTestData.sampleMeta)
        outbox.put(segmentId("2026-10-20", 2), byteArrayOf(1), AuditTestData.sampleMeta)
        val segments = outbox.segmentsOldestFirst()
        assertEquals(listOf(segmentId("2026-10-20", 2), segmentId("2026-10-21", 1)), segments.map { it.id })
        assertArrayEquals(byteArrayOf(1), segments.first().sealedFile.readBytes())
        assertEquals(AuditTestData.sampleMeta, segments.first().meta)
    }

    @Test
    fun `delete removes the file and its meta`() {
        outbox.put(segmentId("2026-10-21", 1), byteArrayOf(1), AuditTestData.sampleMeta)
        outbox.delete(outbox.segmentsOldestFirst().single())
        assertFalse(outbox.contains(segmentId("2026-10-21", 1)))
        assertTrue(File(directory, "outbox").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a failed segment leaves the outbox, is counted and keeps its slot taken`() {
        outbox.put(segmentId("2026-10-21", 1), byteArrayOf(1), AuditTestData.sampleMeta)
        outbox.moveToFailed(outbox.segmentsOldestFirst().single())
        assertTrue(outbox.segmentsOldestFirst().isEmpty())
        assertEquals(1, outbox.failedCount())
        assertTrue(outbox.isTaken(segmentId("2026-10-21", 1)))
    }

    @Test
    fun `a saved upload sequence is read back, and none reads as null`() {
        outbox.put(segmentId("2026-10-21", 1), byteArrayOf(1), AuditTestData.sampleMeta)
        assertEquals(null, outbox.segmentsOldestFirst().single().meta.uploadSequence)
        outbox.saveUploadSequence(outbox.segmentsOldestFirst().single(), 5)
        assertEquals(5, outbox.segmentsOldestFirst().single().meta.uploadSequence)
    }

    @Test
    fun `a new app version moves failed files back with their meta`() {
        outbox.put(segmentId("2026-10-21", 1), byteArrayOf(1), AuditTestData.sampleMeta)
        outbox.moveToFailed(outbox.segmentsOldestFirst().single())
        outbox.retryFailedOnce("2.5.0 (250)")
        assertEquals(0, outbox.failedCount())
        assertEquals(AuditTestData.sampleMeta, outbox.segmentsOldestFirst().single().meta)
    }

    @Test
    fun `the same app version moves nothing a second time`() {
        outbox.retryFailedOnce("2.5.0 (250)")
        outbox.put(segmentId("2026-10-21", 1), byteArrayOf(1), AuditTestData.sampleMeta)
        outbox.moveToFailed(outbox.segmentsOldestFirst().single())
        outbox.retryFailedOnce("2.5.0 (250)")
        assertEquals(1, outbox.failedCount())
    }

    @Test
    fun `no temp files are left behind`() {
        outbox.put(segmentId("2026-10-21", 1), byteArrayOf(1), AuditTestData.sampleMeta)
        assertTrue(File(directory, "outbox").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
