package com.dispensesure.retail.core.audit

/** Every setting of the audit log in one place: file layout, rotation and crypto. */
object AuditLogConfig {
    const val DIRECTORY_NAME = "audit"
    const val FILE_BASE_NAME = "audit-logs"
    const val FILE_EXTENSION = "log"
    const val UNREADABLE_EXTENSION = "unreadable"
    const val EXPORT_FILE_NAME = "audit-logs-decrypted.log"

    const val MAX_FILE_BYTES = 5L * 1024 * 1024
    // Current file plus rotated ones.
    const val MAX_FILE_COUNT = 5

    const val LINE_PREFIX = "v1:"
    const val HKDF_INFO = "audit-log-v1"
    const val KEY_LENGTH_BYTES = 32
    const val IV_LENGTH_BYTES = 12
    const val TAG_LENGTH_BITS = 128

    const val SEQUENCE_PREFS_NAME = "audit_log_prefs"
}
