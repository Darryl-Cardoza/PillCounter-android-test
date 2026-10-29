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
            entry(className = "LoginViewModel", throwable = SocketTimeoutException(), event = LogEvent.DISPENSE_FAILED)
        )
        assertEquals(LogEvent.DISPENSE_FAILED, result)
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
    fun `HL7 disconnect is not classified as a connect failure`() {
        assertEquals(LogEvent.HL7_CONNECT_FAILED, LogEventClassifier.classify(entry(className = "MllpClient", message = "connect failed")))
        assertEquals(LogEvent.HL7_SERVICE_STOPPED, LogEventClassifier.classify(entry(className = "MllpClient", message = "client disconnected")))
    }

    @Test
    fun `file not found maps to file read error`() {
        assertEquals(LogEvent.FILE_READ_ERROR, LogEventClassifier.classify(entry(throwable = FileNotFoundException())))
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
    fun `DispenseFlowVM branches on ndc, vial, rx keywords`() {
        assertEquals(
            LogEvent.NDC_SCAN_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "DispenseFlowVM",
                    message = "NDC scan failed",
                ),
            ),
        )
        assertEquals(
            LogEvent.VIAL_SCAN_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "DispenseFlowVM",
                    message = "Vial scan failed",
                ),
            ),
        )
        assertEquals(
            LogEvent.RX_SCAN_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "DispenseFlowVM",
                    message = "RX scan failed",
                ),
            ),
        )
        assertEquals(
            LogEvent.DISPENSE_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "DispenseFlowVM",
                    message = "Something else failed",
                ),
            ),
        )
    }

    @Test
    fun `StockCountVM, LooseCountVM and StockCountRepository map to inventory events`() {
        assertEquals(
            LogEvent.INVENTORY_SCAN_FAILED,
            LogEventClassifier.classify(entry(className = "StockCountVM", message = "scan failed")),
        )
        assertEquals(
            LogEvent.INVENTORY_COUNT_FAILED,
            LogEventClassifier.classify(entry(className = "StockCountVM", message = "save failed")),
        )
        assertEquals(
            LogEvent.INVENTORY_COUNT_FAILED,
            LogEventClassifier.classify(entry(className = "LooseCountVM", message = "save failed")),
        )
        assertEquals(
            LogEvent.INVENTORY_COUNT_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "StockCountRepository",
                    message = "commit failed",
                ),
            ),
        )
    }

    @Test
    fun `DispenseCountVM and PillCountingEngine map to pill count events`() {
        assertEquals(
            LogEvent.MODEL_LOAD_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "DispenseCountVM",
                    message = "model load failed",
                ),
            ),
        )
        assertEquals(
            LogEvent.PILL_COUNT_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "DispenseCountVM",
                    message = "count failed",
                ),
            ),
        )
        assertEquals(
            LogEvent.GLOVE_DETECT_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "PillCountingEngine",
                    message = "glove check failed",
                ),
            ),
        )
        assertEquals(
            LogEvent.PILL_COUNT_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "PillCountingEngine",
                    message = "frame failed",
                ),
            ),
        )
    }

    @Test
    fun `StockBatchDetailViewModel branches on delete`() {
        assertEquals(
            LogEvent.BATCH_DELETE_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "StockBatchDetailViewModel",
                    message = "delete failed",
                ),
            ),
        )
        assertEquals(
            LogEvent.BATCH_CREATE_FAILED,
            LogEventClassifier.classify(
                entry(
                    className = "StockBatchDetailViewModel",
                    message = "load failed",
                ),
            ),
        )
    }

    @Test
    fun `unmapped class with throwable falls back to unknown error, not app crash`() {
        assertEquals(LogEvent.UNKNOWN_ERROR, LogEventClassifier.classify(entry(className = "Unmapped", throwable = RuntimeException())))
    }

    @Test
    fun `unmapped class without throwable falls back to unknown error`() {
        assertEquals(LogEvent.UNKNOWN_ERROR, LogEventClassifier.classify(entry(className = "Unmapped")))
    }
}
