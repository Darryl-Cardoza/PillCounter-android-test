package com.dispensesure.retail.util

import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogLevel
import java.util.Collections

/** Runs [block] and returns the messages of the ERROR entries it would send to /mobile/logs. */
internal inline fun shippedErrors(block: () -> Unit): List<String> {
    val errors = Collections.synchronizedList(mutableListOf<String>())
    AppLogger.setDestinationForTest(object : LogDestination {
        override fun write(entry: LogEntry) {
            if (entry.level == LogLevel.ERROR) errors += entry.message
        }
    })
    try {
        block()
    } finally {
        AppLogger.setDestinationForTest(null)
    }
    return errors.toList()
}
