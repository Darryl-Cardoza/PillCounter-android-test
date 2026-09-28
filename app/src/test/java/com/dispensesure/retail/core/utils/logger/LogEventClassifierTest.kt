package com.dispensesure.retail.core.utils.logger

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LogEventClassifierTest {

    private fun entry(
        className: String = "Foo",
        message: String = "something happened",
        throwable: Throwable? = null,
        event: LogEvent? = null
    ) = LogEntry(
        timestampMillis = 0L,
        level = LogLevel.ERROR,
        fileName = "Foo.kt",
        className = className,
        methodName = "bar",
        message = message,
        throwable = throwable,
        event = event
    )

    @Test
    fun `an explicit event on the entry is never overridden`() {
        val result = LogEventClassifier.classify(
            entry(className = "LoginViewModel", throwable = SocketTimeoutException(), event = LogEvent.DISPENSE_COUNT)
        )
        assertEquals(LogEvent.DISPENSE_COUNT, result)
    }

    @Test
    fun `exception type wins over the originating class`() {
        val result = LogEventClassifier.classify(entry(className = "LoginViewModel", throwable = SocketTimeoutException()))
        assertEquals(LogEvent.NETWORK_TIMEOUT, result)
    }

    @Test
    fun `unknown host and connect exceptions map to network error`() {
        assertEquals(LogEvent.NETWORK_ERROR, LogEventClassifier.classify(entry(throwable = UnknownHostException())))
        assertEquals(LogEvent.NETWORK_ERROR, LogEventClassifier.classify(entry(throwable = ConnectException())))
    }

    @Test
    fun `file not found maps to file write error`() {
        assertEquals(LogEvent.FILE_WRITE_ERROR, LogEventClassifier.classify(entry(throwable = FileNotFoundException())))
    }

    @Test
    fun `security exception maps to permission denied`() {
        assertEquals(LogEvent.PERMISSION_DENIED, LogEventClassifier.classify(entry(throwable = SecurityException())))
    }

    @Test
    fun `sqlite exception maps to database error`() {
        assertEquals(
            LogEvent.DATABASE_ERROR,
            LogEventClassifier.classify(entry(throwable = android.database.sqlite.SQLiteException("boom")))
        )
    }

    @Test
    fun `VerifyPinViewModel maps to pin vs otp based on message`() {
        assertEquals(
            LogEvent.PIN_VERIFY_FAILED,
            LogEventClassifier.classify(entry(className = "VerifyPinViewModel", message = "Failed to verify PIN"))
        )
        assertEquals(
            LogEvent.OTP_VERIFICATION_FAILED,
            LogEventClassifier.classify(entry(className = "VerifyPinViewModel", message = "Failed to verify OTP"))
        )
    }

    @Test
    fun `SessionHealthController maps timeout vs expiry based on message`() {
        assertEquals(
            LogEvent.SESSION_EXPIRED,
            LogEventClassifier.classify(entry(className = "SessionHealthController", message = "Session invalidated by server"))
        )
    }

    @Test
    fun `SessionLockController maps timeout vs generic lock based on message`() {
        assertEquals(
            LogEvent.FACE_LOCK_TIMEOUT,
            LogEventClassifier.classify(entry(className = "SessionLockController", message = "Inactivity timeout"))
        )
        assertEquals(
            LogEvent.FACE_LOCK_SESSION,
            LogEventClassifier.classify(entry(className = "SessionLockController", message = "Locking session"))
        )
    }

    @Test
    fun `DispenseFlowViewModel branches on ndc, vial, rx keywords`() {
        assertEquals(
            LogEvent.NDC_SCAN_FAILED,
            LogEventClassifier.classify(entry(className = "DispenseFlowViewModel", message = "NDC scan failed"))
        )
        assertEquals(
            LogEvent.VIAL_SCAN_FAILED,
            LogEventClassifier.classify(entry(className = "DispenseFlowViewModel", message = "Vial scan failed"))
        )
        assertEquals(
            LogEvent.RX_SCAN_FAILED,
            LogEventClassifier.classify(entry(className = "DispenseFlowViewModel", message = "RX scan failed"))
        )
        assertEquals(
            LogEvent.DISPENSE_FAILED,
            LogEventClassifier.classify(entry(className = "DispenseFlowViewModel", message = "Something else failed"))
        )
    }

    @Test
    fun `unmapped class with throwable falls back to app crash`() {
        assertEquals(LogEvent.APP_CRASH, LogEventClassifier.classify(entry(className = "Unmapped", throwable = RuntimeException())))
    }

    @Test
    fun `unmapped class without throwable falls back to unknown error`() {
        assertEquals(LogEvent.UNKNOWN_ERROR, LogEventClassifier.classify(entry(className = "Unmapped")))
    }
}
