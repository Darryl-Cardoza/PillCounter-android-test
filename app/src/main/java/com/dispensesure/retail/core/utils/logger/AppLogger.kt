package com.dispensesure.retail.core.utils.logger

import android.content.Context
import android.util.Log
import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.utils.logger.destination.FileLogDestination
// import com.dispensesure.retail.core.utils.logger.destination.CompositeLogDestination
// import com.dispensesure.retail.core.utils.logger.destination.RemoteLogDestination

/**
 * A standalone logger class to handle logging throughout the application, and the single hub
 * every log call in the app eventually flows through.
 *
 * Each instance is a lightweight, tag-scoped wrapper around Android's Logcat (unchanged
 * behaviour from before this class also drove the centralized logger below). In addition to
 * Logcat, every call is forwarded to the companion object's centralized machinery, which:
 * applies [LoggerConfig]'s level filter, resolves where in the app the call actually came from
 * (without misattributing it to AppLogger itself), builds a [LogEntry], and hands it to whichever
 * [LogDestination] is configured (currently a rotating file — see [FileLogDestination]).
 *
 * The centralized side is a plain singleton on the companion object — not Hilt-injected —
 * because `AppLogger` instances are created via [create]/`AppLogger(tag)` all over the codebase,
 * including from classes that are never touched by DI (companion objects, plain utilities).
 * [init] must be called once, as early as possible, from
 * [com.dispensesure.retail.PillCountingApplication.onCreate]; any log call made before that is a
 * safe no-op for the file destination (it still reaches Logcat as before).
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
        log(LogLevel.DEBUG, tag, message, throwable)
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
        log(LogLevel.INFO, tag, message, throwable)
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
        log(LogLevel.WARN, tag, message, throwable)
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
        log(LogLevel.ERROR, tag, message, throwable)
    }

    /**
     * Logs a titled block as one logcat entry, so a multi-line trace stays together instead of
     * interleaving with other tags. Emitted at warning level like [w], not [i]: the flows this
     * traces also run in release builds, where [i] is compiled out.
     */
    fun block(title: String, vararg lines: Pair<String, Any?>) {
        val rule = "─".repeat(58)
        Log.w(tag, buildString {
            append('\n').append('┌').append(rule).append('\n')
            append("│ ").append(title).append('\n')
            append('├').append(rule)
            lines.forEach { (label, value) -> append('\n').append("│ ").append(label).append(": ").append(value) }
            append('\n').append('└').append(rule)
        })
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

        // Only this class itself (instance and companion) — not the whole `logger` package,
        // which also contains real callers such as PerformanceLogger that must still be
        // correctly attributed as the log's origin.
        private const val INTERNAL_CLASS_NAME = "com.dispensesure.retail.core.utils.logger.AppLogger"

        @Volatile
        private var destination: LogDestination? = null

        /**
         * Wires up the file-based [LogDestination]. Call once, as early as possible, from
         * [com.dispensesure.retail.PillCountingApplication.onCreate].
         */
        @Synchronized
        fun init(context: Context) {
            if (destination != null) return
            destination = FileLogDestination(context.applicationContext)

            // Single-line ERROR-only feed for a remote log/crash aggregator (Datadog, Sentry,
            // ...). Uncomment once a provider is chosen and its SDK call is filled in inside
            // RemoteLogDestination.write() — no other change is needed here or at any call site.
            // destination = CompositeLogDestination(destination!!, RemoteLogDestination())
        }

        /**
         * Lets [com.dispensesure.retail.core.utils.logger.di.LoggerModule] hand out the same
         * [LogDestination] instance to Hilt-managed classes instead of standing up a second one.
         */
        fun currentDestination(): LogDestination? = destination

        /** Test-only: injects a fake destination directly, bypassing [init]'s Context requirement. */
        internal fun setDestinationForTest(destination: LogDestination?) {
            this.destination = destination
        }

        private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            if (level < LoggerConfig.minimumLogLevel) return
            val dest = destination ?: return

            val callSite = resolveCallSite()
            dest.write(
                LogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    level = level,
                    fileName = callSite.fileName,
                    className = tag,
                    methodName = callSite.methodName,
                    message = message,
                    humanReadableError = throwable?.let(ExceptionTranslator::translate),
                    throwable = throwable,
                    operatorName = LoggerConfig.operatorName?.takeIf { it.isNotBlank() }
                )
            )
        }

        private data class CallSite(val fileName: String, val methodName: String)

        /**
         * Walks the current stack to find the first frame outside this class, so the log entry
         * reports the real caller (e.g. LoginRepository.loginUser) rather than AppLogger itself.
         */
        private fun resolveCallSite(): CallSite {
            val frame = Thread.currentThread().stackTrace.firstOrNull { element ->
                element.className != "java.lang.Thread" && !isInternalClass(element.className)
            }
            return CallSite(
                fileName = frame?.fileName ?: "Unknown",
                methodName = frame?.methodName ?: "unknown"
            )
        }

        /**
         * True for AppLogger itself and its nested/companion classes (e.g. `AppLogger$Companion`)
         * — but not for unrelated classes that merely share the string prefix, such as a
         * hypothetical `AppLoggerHelper`.
         */
        private fun isInternalClass(className: String): Boolean =
            className == INTERNAL_CLASS_NAME || className.startsWith("$INTERNAL_CLASS_NAME$")
    }
}
