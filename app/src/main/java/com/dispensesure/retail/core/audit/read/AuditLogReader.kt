package com.dispensesure.retail.core.audit.read

import com.dispensesure.retail.core.audit.crypto.AuditRecordCipher
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import javax.inject.Inject

/** Decrypts every audit file for the debug viewer. Call off the main thread. */
class AuditLogReader @Inject constructor(
    private val fileStore: AuditLogFileStore,
    private val cipher: AuditRecordCipher
) {
    /** Unreadable files first, then each line from the oldest rotated file to the current one. */
    fun readAllOldestFirst(): List<AuditLogEntry> {
        val unreadableFileEntries = fileStore.unreadableFiles().map { AuditLogEntry.UnreadableFile(it.name) }
        val lineEntries = fileStore.readableFilesOldestFirst().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.isBlank()) null else readLine(file.name, lineNumber = index + 1, line)
            }
        }
        return unreadableFileEntries + lineEntries
    }

    private fun readLine(fileName: String, lineNumber: Int, line: String): AuditLogEntry =
        try {
            AuditLogEntry.Record(AuditJson.decodeRecord(cipher.decrypt(line)))
        } catch (e: Exception) {
            AuditLogEntry.UnreadableLine(fileName, lineNumber)
        }
}
