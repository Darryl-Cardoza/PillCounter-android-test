package com.dispensesure.retail.core.utils.logger.destination

import android.util.Log
import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.LogEntry

/**
 * Fans a single [LogEntry] out to several [LogDestination]s — e.g. the always-on
 * [FileLogDestination] plus a future [RemoteLogDestination]. Each destination is written to
 * independently: one destination throwing (a remote SDK not yet initialized, a network hiccup)
 * never stops the others, most importantly the file destination, from receiving the entry.
 */
class CompositeLogDestination(private val destinations: List<LogDestination>) : LogDestination {

    constructor(vararg destinations: LogDestination) : this(destinations.toList())

    override fun write(entry: LogEntry) {
        destinations.forEach { destination ->
            try {
                destination.write(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Log destination ${destination::class.java.simpleName} failed to write", e)
            }
        }
    }

    companion object {
        private const val TAG = "CompositeLogDestination"
    }
}
