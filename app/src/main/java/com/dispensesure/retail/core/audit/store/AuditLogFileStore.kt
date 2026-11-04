package com.dispensesure.retail.core.audit.store

import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Plain-text home of the encrypted audit lines: `audit-logs.log` plus rotated `audit-logs-1.log`…
 * Not thread-safe on purpose; only the single AuditLogger worker calls it.
 */
class AuditLogFileStore(
    private val directory: File,
    private val maxFileBytes: Long = AuditLogConfig.MAX_FILE_BYTES,
    private val maxFileCount: Int = AuditLogConfig.MAX_FILE_COUNT
) {
    private val logger = AppLogger.create<AuditLogFileStore>()

    private val currentFile: File
        get() = File(directory, "${AuditLogConfig.FILE_BASE_NAME}.${AuditLogConfig.FILE_EXTENSION}")

    private val highestRotatedIndex: Int
        get() = maxFileCount - 1

    fun append(line: String) {
        directory.mkdirs()
        val lineBytes = "$line\n".toByteArray(Charsets.UTF_8)
        if (currentFile.exists() && currentFile.length() + lineBytes.size > maxFileBytes) rotate()
        FileOutputStream(currentFile, true).use { it.write(lineBytes) }
    }

    /** A kill mid-write leaves a line with no newline; close it so the next record starts clean. */
    fun endIncompleteLine() {
        val file = currentFile
        if (!file.exists() || file.length() == 0L) return
        val lastByte = RandomAccessFile(file, "r").use { it.seek(file.length() - 1); it.read() }
        if (lastByte != '\n'.code) FileOutputStream(file, true).use { it.write('\n'.code) }
    }

    fun hasNoLines(): Boolean = !anyLine { true }

    /** Checks non-blank lines from the current file back to the oldest; stops at the first match. */
    fun anyLine(predicate: (String) -> Boolean): Boolean =
        readableFilesOldestFirst().asReversed().any { file ->
            file.useLines { lines -> lines.any { it.isNotBlank() && predicate(it) } }
        }

    /** Used when the key changed: old lines can't be read any more, so keep them aside. Only the newest set is kept. */
    fun markAllUnreadable() {
        unreadableFiles().forEach { it.delete() }
        val stamp = System.currentTimeMillis()
        readableFilesOldestFirst().forEach { file ->
            file.renameTo(File(directory, "${file.nameWithoutExtension}-$stamp.${AuditLogConfig.UNREADABLE_EXTENSION}"))
        }
    }

    fun readableFilesOldestFirst(): List<File> =
        ((highestRotatedIndex downTo 1).map(::rotatedFile) + currentFile).filter { it.exists() }

    fun unreadableFiles(): List<File> =
        directory.listFiles { file -> file.extension == AuditLogConfig.UNREADABLE_EXTENSION }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun rotate() {
        val oldestFile = rotatedFile(highestRotatedIndex)
        if (oldestFile.exists()) {
            logger.e("Audit log full; dropping ${oldestFile.name}", event = LogEvent.AUDIT_TRAIL_FILE_DROPPED)
            oldestFile.delete()
        }
        for (index in highestRotatedIndex - 1 downTo 1) {
            rotatedFile(index).takeIf { it.exists() }?.renameTo(rotatedFile(index + 1))
        }
        currentFile.renameTo(rotatedFile(1))
    }

    private fun rotatedFile(index: Int): File =
        File(directory, "${AuditLogConfig.FILE_BASE_NAME}-$index.${AuditLogConfig.FILE_EXTENSION}")
}
