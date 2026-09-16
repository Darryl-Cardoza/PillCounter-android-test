package com.rite.pillcounting.core.utils.logger

import android.util.Log
import com.rite.pillcounting.BuildConfig

/**
 * A standalone logger class to handle logging throughout the application.
 * This wrapper around Android's default Log class provides a consistent
 * logging tag and can be easily extended or replaced with a more advanced
 * logging library like Timber in the future.
 *
 * @param tag The logging tag to be used for all messages from this logger instance.
 */
class AppLogger(private val tag: String) {

    /**
     * Logs a debug message.
     * Use this for fine-grained information that is most useful during development.
     *
     * @param message The message to be logged.
     * @param throwable An optional throwable to log with the message.
     */
    fun d(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.d(tag, message, throwable)
    }

    /**
     * Logs an info message.
     * Use this for informational messages that highlight the progress of the application.
     *
     * @param message The message to be logged.
     * @param throwable An optional throwable to log with the message.
     */
    fun i(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) logLong(Log.INFO, message, throwable)
    }

    /**
     * Logs a warning message.
     * Use this for potentially harmful situations or events that are not critical errors.
     *
     * @param message The message to be logged.
     * @param throwable An optional throwable to log with the message.
     */
    fun w(message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
    }

    /**
     * Logs an error message.
     * Use this for errors that have occurred and should be investigated.
     *
     * @param message The message to be logged.
     * @param throwable An optional throwable to log with the message.
     */
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }

    /**
     * Logcat truncates any single log line around 4KB, which silently chops long HL7
     * messages (chunked inventory batches can run to tens of KB) so only their tail shows up.
     * Split into ~3500-char slices so the full payload is visible across multiple log lines.
     */
    private fun logLong(priority: Int, message: String, throwable: Throwable? = null) {
        val maxChunkSize = 3500
        if (message.length <= maxChunkSize) {
            Log.println(priority, tag, if (throwable != null) message + '\n' + Log.getStackTraceString(throwable) else message)
            return
        }
        var start = 0
        var part = 1
        val totalParts = (message.length + maxChunkSize - 1) / maxChunkSize
        while (start < message.length) {
            val end = minOf(start + maxChunkSize, message.length)
            Log.println(priority, tag, "[$part/$totalParts] ${message.substring(start, end)}")
            start = end
            part++
        }
        if (throwable != null) Log.println(priority, tag, Log.getStackTraceString(throwable))
    }

    companion object {
        /**
         * A factory method to create an [AppLogger] instance using the simple
         * name of the calling class as the tag.
         *
         * @param T The class to be used for the tag.
         * @return An instance of [AppLogger].
         */
        inline fun <reified T> create(): AppLogger {
            return AppLogger(T::class.java.simpleName)
        }
    }
}
