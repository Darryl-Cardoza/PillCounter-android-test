package com.dispensesure.retail.core.utils.logger

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a [LogEntry] into a structured, human-readable block. Kept separate from any
 * destination so the format can be unit-tested without touching the filesystem or network.
 */
object LogFormatter {

    private const val SEPARATOR = "============================================================"

    private val timestampFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }

    fun format(entry: LogEntry): String = buildString {
        appendLine(SEPARATOR)
        appendLine("Timestamp: ${timestampFormat.get()!!.format(Date(entry.timestampMillis))}")
        appendLine("Level: ${entry.level}")
        appendLine()
        appendLine("File: ${entry.fileName}")
        appendLine("Class: ${entry.className}")
        appendLine("Method: ${entry.methodName}")
        entry.operatorName?.let { appendLine("Operator: $it") }
        appendLine()

        val throwable = entry.throwable
        if (throwable != null) {
            appendLine("Context:")
            appendLine(entry.message)
            appendLine()
            appendLine("Error text:")
            appendLine(entry.humanReadableError ?: ExceptionTranslator.translate(throwable))
            appendLine()
            appendLine("Actual Error:")
            appendLine(throwable.toString())
            appendLine()
            appendLine("Stack Trace:")
            appendLine(stackTraceOf(throwable))
        } else {
            appendLine("Message:")
            appendLine(entry.message)
        }

        append(SEPARATOR)
    }

    /**
     * Single-line rendering for a remote log/crash aggregator (Datadog, Sentry, ...) — see
     * `RemoteLogDestination`. Deliberately terse (no timestamp/stack trace: the provider attaches
     * those itself), so it stays greppable as one line per error: `File -> Class -> Method ->
     * Message`. Always the caller's message: the translated error travels separately in the payload.
     */
    fun formatSingleLine(entry: LogEntry): String =
        "${entry.fileName} -> ${entry.className} -> ${entry.methodName} -> ${entry.message.replace(Regex("[\r\n]+"), " ")}"

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }
}
