package com.dispensesure.retail.core.audit

/** Every setting of the audit log in one place: file layout and rotation. */
object AuditLogConfig {
    const val DIRECTORY_NAME = "audit"
    const val OUTBOX_DIRECTORY_NAME = "outbox"
    const val FAILED_DIRECTORY_NAME = "failed"
    const val FILE_EXTENSION = "log"
    const val META_FILE_SUFFIX = ".meta.json"
    const val TEMP_FILE_SUFFIX = ".tmp"
    const val EXPORT_FILE_NAME = "audit-logs.log"

    const val MAX_FILE_BYTES = 256L * 1024

    const val SEQUENCE_PREFS_NAME = "audit_log_prefs"

    // POST /audit/logs: app_name for this app, and how many files go up at once.
    const val APP_NAME = "dispensesure"
    const val MAX_PARALLEL_UPLOADS = 3

    // dek_key_id is this prefix plus the device_key, in the PCENC header, the upload and the key registration.
    const val DEK_KEY_ID_PREFIX = "dek-"

    // POST /mobile/devices/keys: the DEK's algorithm and size, and this platform.
    const val DEK_ALGORITHM = "AES-256-GCM"
    const val DEK_KEY_LENGTH_BITS = 256
    const val PLATFORM = "android"
}
