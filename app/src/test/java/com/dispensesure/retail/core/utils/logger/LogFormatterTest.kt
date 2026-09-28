package com.dispensesure.retail.core.utils.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [LogFormatter] — the structured text block described in the logging spec. */
class LogFormatterTest {

    @Test
    fun `non-exception entry uses Message section, not Error text or Actual Error`() {
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.WARN,
            fileName = "BluetoothManager.kt",
            className = "BluetoothManager",
            methodName = "connectToDevice",
            message = "Bluetooth device was not available for connection."
        )

        val formatted = LogFormatter.format(entry)

        assertTrue(formatted.contains("Level: WARN"))
        assertTrue(formatted.contains("File: BluetoothManager.kt"))
        assertTrue(formatted.contains("Class: BluetoothManager"))
        assertTrue(formatted.contains("Method: connectToDevice"))
        assertTrue(formatted.contains("Message:"))
        assertTrue(formatted.contains("Bluetooth device was not available for connection."))
        assertFalse(formatted.contains("Error text:"))
        assertFalse(formatted.contains("Actual Error:"))
        assertFalse(formatted.contains("Stack Trace:"))
        assertFalse(formatted.contains("Operator:"))
    }

    @Test
    fun `includes an Operator line when the entry has one`() {
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.ERROR,
            fileName = "Foo.kt",
            className = "Foo",
            methodName = "bar",
            message = "something happened",
            operatorName = "Jane Doe"
        )

        assertTrue(LogFormatter.format(entry).contains("Operator: Jane Doe"))
    }

    @Test
    fun `omits the Operator line entirely when the entry has none`() {
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.ERROR,
            fileName = "Foo.kt",
            className = "Foo",
            methodName = "bar",
            message = "something happened",
            operatorName = null
        )

        assertFalse(LogFormatter.format(entry).contains("Operator"))
    }

    @Test
    fun `exception entry contains context, human readable error, actual error and stack trace`() {
        val throwable = java.net.SocketTimeoutException("timeout")
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.ERROR,
            fileName = "UserRepository.kt",
            className = "UserRepository",
            methodName = "getUserProfile",
            message = "Failed while retrieving the user profile from the backend.",
            humanReadableError = ExceptionTranslator.translate(throwable),
            throwable = throwable
        )

        val formatted = LogFormatter.format(entry)

        assertTrue(formatted.contains("Level: ERROR"))
        assertTrue(formatted.contains("Context:"))
        assertTrue(formatted.contains("Failed while retrieving the user profile from the backend."))
        assertTrue(formatted.contains("Error text:"))
        assertTrue(formatted.contains("The request timed out while communicating with the server."))
        assertTrue(formatted.contains("Actual Error:"))
        assertTrue(formatted.contains("java.net.SocketTimeoutException: timeout"))
        assertTrue(formatted.contains("Stack Trace:"))
        assertTrue(formatted.contains("at "))
    }

    @Test
    fun `falls back to ExceptionTranslator when humanReadableError not supplied`() {
        val throwable = IllegalStateException("bad state")
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.ERROR,
            fileName = "Foo.kt",
            className = "Foo",
            methodName = "bar",
            message = "context message",
            humanReadableError = null,
            throwable = throwable
        )

        val formatted = LogFormatter.format(entry)

        assertTrue(formatted.contains("The application encountered an unexpected state."))
    }

    @Test
    fun `output is wrapped between separator lines`() {
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.INFO,
            fileName = "F.kt",
            className = "F",
            methodName = "m",
            message = "hello"
        )

        val lines = LogFormatter.format(entry).lines()
        val separator = "============================================================"
        assertEquals(separator, lines.first())
        assertEquals(separator, lines.last())
    }

    // -------------------------------------------------------------------------
    // formatSingleLine — used by RemoteLogDestination (Datadog/Sentry, currently disabled).
    // -------------------------------------------------------------------------

    @Test
    fun `formatSingleLine uses translated error for an exception entry`() {
        val throwable = java.net.SocketTimeoutException("timeout")
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.ERROR,
            fileName = "UserRepository.kt",
            className = "UserRepository",
            methodName = "getUserProfile",
            message = "Failed while retrieving the user profile from the backend.",
            humanReadableError = ExceptionTranslator.translate(throwable),
            throwable = throwable
        )

        assertEquals(
            "UserRepository.kt -> UserRepository -> getUserProfile -> " +
                "The request timed out while communicating with the server.",
            LogFormatter.formatSingleLine(entry)
        )
    }

    @Test
    fun `formatSingleLine falls back to the plain message when there is no throwable`() {
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.ERROR,
            fileName = "BluetoothManager.kt",
            className = "BluetoothManager",
            methodName = "connectToDevice",
            message = "Bluetooth device was not available for connection."
        )

        assertEquals(
            "BluetoothManager.kt -> BluetoothManager -> connectToDevice -> " +
                "Bluetooth device was not available for connection.",
            LogFormatter.formatSingleLine(entry)
        )
    }

    @Test
    fun `formatSingleLine is a single line with no embedded newlines`() {
        val throwable = IllegalStateException("bad state")
        val entry = LogEntry(
            timestampMillis = 0L,
            level = LogLevel.ERROR,
            fileName = "Foo.kt",
            className = "Foo",
            methodName = "bar",
            message = "context message",
            throwable = throwable
        )

        assertFalse(LogFormatter.formatSingleLine(entry).contains("\n"))
    }
}
