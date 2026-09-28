package com.dispensesure.retail.core.utils.logger

/**
 * Where a [LogEntry] ends up. [FileLogDestination] is the only implementation today, but nothing
 * upstream of this interface (AppLogger, other call sites) knows or cares which
 * destination is wired up — swapping in an API/Crashlytics/Datadog destination later only means
 * providing a new [LogDestination] in [com.dispensesure.retail.core.utils.logger.di.LoggerModule].
 */
interface LogDestination {
    fun write(entry: LogEntry)
}
