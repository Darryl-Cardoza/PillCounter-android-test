package com.dispensesure.retail.core.utils.logger

/**
 * Severity of a log entry, ordered from least to most severe. [LoggerConfig.minimumLogLevel]
 * filters by this ordinal, so declaration order here matters.
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
