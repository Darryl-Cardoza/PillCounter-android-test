package com.dispensesure.retail.core.audit.store

import com.dispensesure.retail.core.audit.AuditTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class AuditLogFileStoreTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_store_test_${System.nanoTime()}")
    private val clock = MutableTestClock(Instant.parse("2026-10-21T10:00:00Z"))
    private val segmentSlot = InMemorySegmentSlot()
    private val outbox = AuditOutbox(directory)

    // 20-byte cap: two 9-byte lines (8 chars + newline) fit, a third starts a new file.
    private val fileStore = AuditLogFileStore(directory, segmentSlot, outbox, clock, maxFileBytes = 20)

    private fun file(name: String) = File(directory, name)
    private fun File.lines(): List<String> = readLines().filter { it.isNotEmpty() }
    private fun day21(sequence: Int) = SegmentId(LocalDate.parse("2026-10-21"), sequence)

    @Test
    fun `the first line goes to a new file for today`() {
        assertFalse(fileStore.append("v1:aaaaa"))
        assertEquals(listOf("v1:aaaaa"), file("2026-10-21-0001.log").lines())
    }

    @Test
    fun `a line that would pass the cap starts the next sequence`() {
        val closedPrevious = (0 until 3).map { fileStore.append("v1:line$it") }
        assertEquals(listOf(false, false, true), closedPrevious)
        assertEquals(listOf("v1:line0", "v1:line1"), file("2026-10-21-0001.log").lines())
        assertEquals(listOf("v1:line2"), file("2026-10-21-0002.log").lines())
    }

    @Test
    fun `a new UTC day starts again at sequence 1`() {
        repeat(3) { fileStore.append("v1:line$it") }
        clock.instant = Instant.parse("2026-10-22T00:00:01Z")
        assertTrue(fileStore.append("v1:nextday"))
        assertEquals(listOf("v1:nextday"), file("2026-10-22-0001.log").lines())
    }

    @Test
    fun `nothing is ever dropped`() {
        repeat(10) { fileStore.append("v1:line$it") }
        assertEquals(10, fileStore.readableFilesOldestFirst().flatMap { it.lines() }.size)
    }

    @Test
    fun `the slot survives a restart`() {
        repeat(3) { fileStore.append("v1:line$it") }
        AuditLogFileStore(directory, segmentSlot, outbox, clock, maxFileBytes = 20).append("v1:after")
        assertEquals(listOf("v1:line2", "v1:after"), file("2026-10-21-0002.log").lines())
    }

    @Test
    fun `closed files leave out the one being written`() {
        repeat(3) { fileStore.append("v1:line$it") }
        assertEquals(listOf(day21(1)), fileStore.closedFilesOldestFirst().map { it.first })
    }

    @Test
    fun `closing for an ended day waits for the UTC day to end`() {
        fileStore.append("v1:line0")
        assertFalse(fileStore.closeActiveFile(onlyIfDayEnded = true))
        clock.instant = Instant.parse("2026-10-22T00:00:01Z")
        assertTrue(fileStore.closeActiveFile(onlyIfDayEnded = true))
        assertEquals(1, fileStore.closedFilesOldestFirst().size)
    }

    @Test
    fun `closing now moves the writer to a new file`() {
        fileStore.append("v1:line0")
        assertTrue(fileStore.closeActiveFile(onlyIfDayEnded = false))
        // The new slot has no file yet, so there is nothing more to close.
        assertFalse(fileStore.closeActiveFile(onlyIfDayEnded = false))
        fileStore.append("v1:line1")
        assertEquals(listOf("v1:line1"), file("2026-10-21-0002.log").lines())
    }

    @Test
    fun `slots still in the outbox or failed are skipped`() {
        outbox.put(day21(1), byteArrayOf(1), AuditTestData.sampleMeta)
        outbox.put(day21(2), byteArrayOf(2), AuditTestData.sampleMeta)
        outbox.moveToFailed(outbox.segmentsOldestFirst().last())
        fileStore.append("v1:line0")
        assertEquals(listOf("v1:line0"), file("2026-10-21-0003.log").lines())
    }

    @Test
    fun `files with the old names are ignored`() {
        directory.mkdirs()
        file("audit-logs.log").writeText("v1:old\n")
        file("audit-logs-1.log").writeText("v1:old\n")
        assertTrue(fileStore.readableFilesOldestFirst().isEmpty())
    }

    @Test
    fun `incomplete last line is closed before the next append`() {
        fileStore.append("v1:cut")
        // Rewrite without the trailing newline, as a kill mid-write would leave it.
        file("2026-10-21-0001.log").writeText("v1:cut")
        fileStore.endIncompleteLine()
        fileStore.append("v1:next")
        assertEquals(listOf("v1:cut", "v1:next"), file("2026-10-21-0001.log").lines())
    }
}
