package com.dispensesure.retail.core.utils.logger.destination

import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogFormatter
import com.dispensesure.retail.core.utils.logger.LogLevel

/**
 * ERROR-only feed intended for a remote log/crash aggregator (Datadog, Sentry, ...). Not wired
 * in yet — instantiating and registering this is commented out in `AppLogger.init()` until a
 * provider is chosen. When that happens, only the body of [write] needs to change (replace the
 * TODO with the provider's SDK call); the rest of the logging pipeline — [LogDestination],
 * `AppLogger`, [LogFormatter] — does not.
 *
 * Renders each entry as the single-line `File -> Class -> Method -> Error Message` via
 * [LogFormatter.formatSingleLine] so it stays greppable and cheap to ship over the wire, unlike
 * the multi-line block [FileLogDestination] writes to disk.
 */
class RemoteLogDestination : LogDestination {

    override fun write(entry: LogEntry) {
        if (entry.level != LogLevel.ERROR) return

        @Suppress("UNUSED_VARIABLE")
        val line = LogFormatter.formatSingleLine(entry)

        // TODO: replace with the chosen provider's SDK call once one is integrated, e.g.:
        // Sentry.captureMessage(line, SentryLevel.ERROR)
        // or
        // Datadog.logs.logger.error(line, entry.throwable)
    }
}
