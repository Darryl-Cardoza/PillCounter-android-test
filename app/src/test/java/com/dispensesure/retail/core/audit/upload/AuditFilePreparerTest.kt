package com.dispensesure.retail.core.audit.upload

import com.dispensesure.retail.core.audit.AuditTestData
import com.dispensesure.retail.core.audit.crypto.AuditHashChain
import com.dispensesure.retail.core.audit.crypto.PcencTestReader
import com.dispensesure.retail.core.audit.crypto.Sha256
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditOutbox
import com.dispensesure.retail.core.audit.store.InMemorySegmentSlot
import com.dispensesure.retail.core.audit.store.MutableTestClock
import com.dispensesure.retail.core.audit.store.SegmentId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class AuditFilePreparerTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_preparer_test_${System.nanoTime()}")
    private val dek = ByteArray(32) { 5 }
    private val outbox = AuditOutbox(directory)
    private val fileStore = AuditLogFileStore(
        directory, InMemorySegmentSlot(), outbox, MutableTestClock(Instant.parse("2026-10-21T12:00:00Z"))
    )
    private val preparer = AuditFilePreparer(fileStore, outbox)
    private val firstSegment = SegmentId(LocalDate.parse("2026-10-21"), 1)

    private val line1 = AuditJson.encodeRecord(AuditTestData.record(1, "2026-10-21T10:00:00Z"))
    private val line2 = AuditJson.encodeRecord(AuditTestData.record(2, "2026-10-21T11:00:00Z"))

    /** Writes [lines], closes the file, and returns its exact bytes before preparing. */
    private fun closedFile(vararg lines: String): ByteArray {
        lines.forEach(fileStore::append)
        val bytes = fileStore.readableFilesOldestFirst().single().readBytes()
        fileStore.closeActiveFile(onlyIfDayEnded = false)
        return bytes
    }

    @Test
    fun `the prepared file is the local file sealed with the dek`() {
        val localBytes = closedFile(line1, line2)
        preparer.prepareClosedFiles(dek, "dek-dev-1", 3)

        val sealed = outbox.segmentsOldestFirst().single().sealedFile.readBytes()
        assertArrayEquals("PCENC".toByteArray(Charsets.US_ASCII), sealed.copyOfRange(0, 5))
        // Header key id "dek-dev-1" (9 bytes), then version 3.
        assertArrayEquals(
            byteArrayOf(9) + "dek-dev-1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 3),
            sealed.copyOfRange(6, 20)
        )
        assertArrayEquals(localBytes, PcencTestReader.open(sealed, dek))
    }

    @Test
    fun `meta holds the sealed hash, the chain tail and the dek id and version`() {
        closedFile(line1, line2)
        preparer.prepareClosedFiles(dek, "dek-dev-1", 3)

        val segment = outbox.segmentsOldestFirst().single()
        assertEquals(firstSegment, segment.id)
        assertEquals(Sha256.hexOf(segment.sealedFile.readBytes()), segment.meta.sha256)
        assertEquals(AuditHashChain.lastHash(listOf(line1, line2)), segment.meta.chainLastHash)
        assertEquals("dek-dev-1", segment.meta.dekKeyId)
        assertEquals(3, segment.meta.dekVersion)
        assertEquals(2, segment.meta.eventCount)
        assertEquals("2026-10-21T10:00:00Z", segment.meta.firstEventAt)
        assertEquals("2026-10-21T11:00:00Z", segment.meta.lastEventAt)
    }

    @Test
    fun `every hashed line is counted, an unreadable one too`() {
        closedFile(line1, "{cut by a kill")
        preparer.prepareClosedFiles(dek, "dek-dev-1", 3)

        val meta = outbox.segmentsOldestFirst().single().meta
        assertEquals(AuditHashChain.lastHash(listOf(line1, "{cut by a kill")), meta.chainLastHash)
        assertEquals(2, meta.eventCount)
        assertEquals("2026-10-21T10:00:00Z", meta.lastEventAt)
    }

    @Test
    fun `the local file goes once it is prepared`() {
        closedFile(line1)
        preparer.prepareClosedFiles(dek, "dek-dev-1", 3)
        assertTrue(fileStore.closedFilesOldestFirst().isEmpty())
    }

    @Test
    fun `a slot already in the outbox is never prepared again`() {
        closedFile(line1)
        outbox.put(firstSegment, byteArrayOf(9), AuditTestData.sampleMeta)

        preparer.prepareClosedFiles(dek, "dek-dev-1", 3)

        assertArrayEquals(byteArrayOf(9), outbox.segmentsOldestFirst().single().sealedFile.readBytes())
        assertTrue(fileStore.closedFilesOldestFirst().isEmpty())
    }

    @Test
    fun `the file being written is not prepared`() {
        fileStore.append(line1)
        preparer.prepareClosedFiles(dek, "dek-dev-1", 3)
        assertTrue(outbox.segmentsOldestFirst().isEmpty())
    }
}
