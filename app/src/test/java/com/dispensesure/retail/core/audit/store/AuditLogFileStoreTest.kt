package com.dispensesure.retail.core.audit.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuditLogFileStoreTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "audit_store_test_${System.nanoTime()}")

    // 20-byte cap: two 9-byte lines (8 chars + newline) fit, a third rotates.
    private val fileStore = AuditLogFileStore(directory, maxFileBytes = 20, maxFileCount = 3)

    private fun File.lines(): List<String> = readLines().filter { it.isNotEmpty() }

    @Test
    fun `append writes one line per call`() {
        fileStore.append("v1:aaaaa")
        fileStore.append("v1:bbbbb")
        assertEquals(listOf("v1:aaaaa", "v1:bbbbb"), File(directory, "audit-logs.log").lines())
    }

    @Test
    fun `line that would pass the cap rotates the file first`() {
        repeat(3) { fileStore.append("v1:line$it") }
        assertEquals(listOf("v1:line0", "v1:line1"), File(directory, "audit-logs-1.log").lines())
        assertEquals(listOf("v1:line2"), File(directory, "audit-logs.log").lines())
    }

    @Test
    fun `oldest file is dropped once the file count is reached`() {
        repeat(7) { fileStore.append("v1:line$it") }
        // 3 files max: current + 2 rotated. line0 and line1 were in the dropped file.
        assertFalse(File(directory, "audit-logs-3.log").exists())
        assertEquals(listOf("v1:line2", "v1:line3"), File(directory, "audit-logs-2.log").lines())
        assertEquals(listOf("v1:line6"), File(directory, "audit-logs.log").lines())
    }

    @Test
    fun `readable files are listed oldest first`() {
        repeat(5) { fileStore.append("v1:line$it") }
        assertEquals(
            listOf("audit-logs-2.log", "audit-logs-1.log", "audit-logs.log"),
            fileStore.readableFilesOldestFirst().map { it.name }
        )
    }

    @Test
    fun `incomplete last line is closed before the next append`() {
        directory.mkdirs()
        File(directory, "audit-logs.log").writeText("v1:cut")
        fileStore.endIncompleteLine()
        fileStore.append("v1:next")
        assertEquals(listOf("v1:cut", "v1:next"), File(directory, "audit-logs.log").lines())
    }

    @Test
    fun `hasNoLines is true with no files and false after an append`() {
        assertTrue(fileStore.hasNoLines())
        fileStore.append("v1:aaaaa")
        assertFalse(fileStore.hasNoLines())
    }

    @Test
    fun `anyLine checks rotated files when the current file has no match`() {
        // line0 and line1 end up in the rotated file, line2 in the current one.
        repeat(3) { fileStore.append("v1:line$it") }
        assertTrue(fileStore.anyLine { it == "v1:line0" })
        assertFalse(fileStore.anyLine { it == "v1:none" })
    }

    @Test
    fun `mark all unreadable moves every log file aside`() {
        repeat(3) { fileStore.append("v1:line$it") }
        fileStore.markAllUnreadable()
        assertTrue(fileStore.readableFilesOldestFirst().isEmpty())
        assertEquals(2, fileStore.unreadableFiles().size)
    }

    @Test
    fun `mark all unreadable keeps only the newest set`() {
        repeat(3) { fileStore.append("v1:line$it") }
        fileStore.markAllUnreadable() // 2 files aside
        fileStore.append("v1:next")
        fileStore.markAllUnreadable() // 1 file aside, the older 2 deleted
        assertEquals(1, fileStore.unreadableFiles().size)
    }
}
