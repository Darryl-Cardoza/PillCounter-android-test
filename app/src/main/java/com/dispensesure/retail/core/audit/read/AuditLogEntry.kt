package com.dispensesure.retail.core.audit.read

import com.dispensesure.retail.core.audit.model.AuditRecord

/** One item of the decrypted audit log as the viewer shows it. */
sealed interface AuditLogEntry {
    data class Record(val record: AuditRecord) : AuditLogEntry
    data class UnreadableLine(val fileName: String, val lineNumber: Int) : AuditLogEntry
    data class UnreadableFile(val fileName: String) : AuditLogEntry
}
