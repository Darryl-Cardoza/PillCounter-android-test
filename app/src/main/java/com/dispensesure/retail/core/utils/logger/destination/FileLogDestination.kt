package com.dispensesure.retail.core.utils.logger.destination

import android.content.Context
import android.util.Log
import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogFormatter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Writes every log entry to a single text file, [LOG_FILE_NAME], under the app's private files
 * directory (`filesDir`, not external storage), so no storage permission is needed and no other
 * app can read the log. This is the current, and only, [LogDestination] — see that interface for
 * how a future destination (remote API, Crashlytics, ...) would replace it without touching call
 * sites.
 *
 * Writes are serialized onto a single background thread so concurrent callers never interleave
 * or corrupt a write, and so logging never blocks the caller (including the main thread, since
 * `AppLogger.e/w` are routinely called from catch blocks in UI code).
 */
class FileLogDestination(context: Context) : LogDestination {

    private val logDir: File = File(context.applicationContext.filesDir, "logs")
    private val activeLogFile: File = File(logDir, LOG_FILE_NAME)

    // Single thread: writes are naturally serialized, so rotation and appends never race.
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FileLogDestination").apply { isDaemon = true }
    }

    override fun write(entry: LogEntry) {
        try {
            writeExecutor.execute { writeInternal(entry) }
        } catch (e: RejectedExecutionException) {
            // Executor was shut down (should not normally happen) — fail silently rather than
            // crash the caller or recurse back through AppLogger.
            Log.w(TAG, "Log write rejected, executor is shut down", e)
        }
    }

    fun getLogFile(): File = activeLogFile

    /** Test-only: blocks until every [write] queued so far has finished. */
    internal fun awaitIdleForTest() {
        writeExecutor.submit {}.get()
    }

    private fun writeInternal(entry: LogEntry) {
        try {
            if (!logDir.exists()) logDir.mkdirs()
            activeLogFile.appendText(LogFormatter.format(entry) + System.lineSeparator())
        } catch (e: Exception) {
            // Never let a logging failure crash the app or recurse back into AppLogger itself.
            Log.e(TAG, "Failed to write log entry to file", e)
        }
    }

    companion object {
        private const val TAG = "FileLogDestination"
        const val LOG_FILE_NAME = "dispensesure_logs.txt"
    }
}
