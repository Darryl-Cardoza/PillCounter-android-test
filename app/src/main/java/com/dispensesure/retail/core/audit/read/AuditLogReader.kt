package com.dispensesure.retail.core.audit.read

import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.model.AuditRecord
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import javax.inject.Inject

/** Reads every audit file for the debug viewer. Call off the main thread. */
class AuditLogReader @Inject constructor(
    private val fileStore: AuditLogFileStore
) {
    /** Each record from the oldest rotated file to the current one. A line cut by a kill is skipped. */
    fun readAllOldestFirst(): List<AuditRecord> =
        fileStore.readableFilesOldestFirst().flatMap { file ->
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) null else runCatching { AuditJson.decodeRecord(line) }.getOrNull()
            }
        }
}
