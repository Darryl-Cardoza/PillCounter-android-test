package com.dispensesure.retail.core.utils.logger

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Central place that infers a [LogEvent] for a [LogEntry] whose call site didn't specify one —
 * used by [com.dispensesure.retail.core.utils.logger.destination.RemoteLogDestination] so the
 * `/mobile/logs` payload always carries a meaningful `event`, without needing every one of the
 * app's `AppLogger.e/w/i` call sites to be touched.
 *
 * Priority, most to least specific:
 *  1. An explicit [LogEntry.event] the call site passed in — never overridden.
 *  2. The exception's type — a timeout is a timeout regardless of which screen hit it.
 *  3. The originating class ([LogEntry.className], i.e. the `AppLogger` tag) mapped to its
 *     feature's most likely failure, refined by a couple of keywords in the message where one
 *     class covers more than one action (e.g. "verify PIN" vs "verify OTP").
 *  4. A last-resort generic value.
 */
object LogEventClassifier {

    fun classify(entry: LogEntry): LogEvent {
        entry.event?.let { return it }
        exceptionEvent(entry.throwable)?.let { return it }
        domainEvent(entry)?.let { return it }
        return if (entry.throwable != null) LogEvent.APP_CRASH else LogEvent.UNKNOWN_ERROR
    }

    private fun exceptionEvent(throwable: Throwable?): LogEvent? = when {
        throwable is SocketTimeoutException -> LogEvent.NETWORK_TIMEOUT
        throwable is UnknownHostException || throwable is ConnectException -> LogEvent.NETWORK_ERROR
        throwable is FileNotFoundException -> LogEvent.FILE_WRITE_ERROR
        throwable is SecurityException -> LogEvent.PERMISSION_DENIED
        isDatabaseException(throwable) -> LogEvent.DATABASE_ERROR
        else -> null
    }

    private fun isDatabaseException(throwable: Throwable?): Boolean {
        val name = throwable?.javaClass?.name ?: return false
        return name.contains("SQLite", ignoreCase = true) || name.contains("android.database", ignoreCase = true)
    }

    private fun domainEvent(entry: LogEntry): LogEvent? {
        val message = entry.message.lowercase()

        return when (entry.className) {
            "LoginViewModel", "LoginRepository" -> LogEvent.LOGIN_FAILED

            "VerifyPinViewModel", "VerifyPinRepository" ->
                if ("pin" in message) LogEvent.PIN_VERIFY_FAILED else LogEvent.OTP_VERIFICATION_FAILED

            "FaceAuthViewModel", "FaceProfileRepository" -> when {
                "delete" in message -> LogEvent.FACE_DELETE_FAILED
                "verify" in message -> LogEvent.FACE_VERIFY_FAILED
                "register" in message || "enroll" in message -> LogEvent.FACE_REGISTER_FAILED
                "duplicate" in message -> LogEvent.FACE_DUPLICATE_FOUND
                else -> LogEvent.FACE_CAPTURE_FAILED
            }

            "SessionLockController" ->
                if ("timeout" in message) LogEvent.FACE_LOCK_TIMEOUT else LogEvent.FACE_LOCK_SESSION

            "SessionHealthController" -> LogEvent.SESSION_EXPIRED

            "AuthEventBus" -> LogEvent.SESSION_EXPIRED
            "TokenAuthenticator" -> LogEvent.TOKEN_REFRESH_FAILED

            "DashboardViewModel" -> LogEvent.DASHBOARD_LOAD_FAILED
            "UserDetailRepository" -> LogEvent.USER_FETCH_FAILED
            "DatabaseKeyProvider" ->
                if ("rotate" in message) LogEvent.KEK_ROTATE_FAILED else LogEvent.DB_KEY_ROTATE_FAILED

            "DispenseFlowViewModel" -> when {
                "ndc" in message -> LogEvent.NDC_SCAN_FAILED
                "vial" in message -> LogEvent.VIAL_SCAN_FAILED
                "rx" in message -> LogEvent.RX_SCAN_FAILED
                else -> LogEvent.DISPENSE_FAILED
            }

            "BatchViewModel" ->
                if ("delete" in message) LogEvent.BATCH_DELETE_FAILED else LogEvent.BATCH_CREATE_FAILED

            "InventoryScanViewModel" ->
                if ("scan" in message) LogEvent.INVENTORY_SCAN_FAILED else LogEvent.INVENTORY_COUNT_FAILED

            "PillScanningViewModel" -> when {
                "model" in message -> LogEvent.MODEL_LOAD_FAILED
                "glove" in message -> LogEvent.GLOVE_DETECT_FAILED
                "tray" in message -> LogEvent.TRAY_CLASSIFY_FAILED
                "reset" in message -> LogEvent.PILL_COUNT_RESET
                else -> LogEvent.PILL_COUNT_FAILED
            }

            "HistoryViewModel", "HistoryDetailsViewModel" ->
                if ("delete" in message) LogEvent.HISTORY_DELETE_FAILED else LogEvent.HISTORY_LOAD_FAILED

            "Hl7Repository", "Hl7ServiceManager", "Hl7EventHandler", "HL7BackgroundService",
            "MllpClient", "MllpConnectionManager", "MllpServer", "NsdHelper" -> when {
                "connect" in message -> LogEvent.HL7_CONNECT_FAILED
                "resend" in message -> LogEvent.HL7_RESEND_FAILED
                "receive" in message -> LogEvent.HL7_RECEIVE_FAILED
                "stop" in message -> LogEvent.HL7_SERVICE_STOPPED
                else -> LogEvent.HL7_SEND_FAILED
            }

            "UnsyncedTransactionViewModel" -> LogEvent.TRANSACTION_SYNC_FAILED

            "ProfileViewModel", "ProfileRepository" ->
                if ("delete" in message) LogEvent.PROFILE_DELETE_FAILED else LogEvent.PROFILE_UPDATE_FAILED

            "TerminalRepository" -> LogEvent.TERMINAL_UPDATE_FAILED

            "MainActivityViewModel", "ApplicationSettingsRepository" ->
                if ("purge" in message || "delete" in message) LogEvent.TRANSACTION_PURGE_FAILED
                else LogEvent.SETTINGS_APPLY_FAILED

            "DrugRepository" -> LogEvent.DRUG_LOOKUP_FAILED
            "DrugImageDownloader" -> LogEvent.DRUG_IMAGE_FAILED
            "HealthRepository" -> LogEvent.HEALTH_CHECK_FAILED
            "SecurityAuditLogger" -> LogEvent.AUDIT_LOG_FAILED

            else -> null
        }
    }
}
