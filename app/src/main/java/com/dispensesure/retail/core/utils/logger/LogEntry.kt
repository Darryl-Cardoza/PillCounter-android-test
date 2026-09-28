package com.dispensesure.retail.core.utils.logger

/**
 * A single, fully-resolved log record. Built by [AppLogger] and handed to whichever
 * [LogDestination] is currently configured — the destination decides how (or whether) to render it.
 */
data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val fileName: String,
    val className: String,
    val methodName: String,
    val message: String,
    val humanReadableError: String? = null,
    val throwable: Throwable? = null,
    /** Whoever was operating the device when this was logged, or null when unknown — see [LoggerConfig.operatorName]. */
    val operatorName: String? = null
)
