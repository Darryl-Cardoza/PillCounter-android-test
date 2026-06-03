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
        if (BuildConfig.DEBUG) Log.i(tag, message, throwable)
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