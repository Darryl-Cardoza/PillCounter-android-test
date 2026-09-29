package com.dispensesure.retail.core.utils.logger

/**
 * The action a [LogEntry] is about, in the two/three-word `SCREAMING_SNAKE_CASE` shape a remote
 * log aggregator (Datadog) expects for its `event` field — e.g. `SCAN_TIMEOUT`, `DISPENSE_COUNT`.
 * Optional on every call site: when omitted, [com.dispensesure.retail.core.utils.logger.destination.RemoteLogDestination]
 * falls back to a generic value derived from the entry itself.
 *
 * Grouped by feature module below. Where an action can both succeed and fail in a way worth
 * telling apart later (e.g. in a Datadog dashboard), both variants are listed; where only the
 * failure is ever worth a log line (the success path is silent by design), only `_FAILED` exists.
 */
enum class LogEvent {

    // ── Auth / Login (feature.login, core.auth) ────────────────────────────
    LOGIN_SUBMITTED,
    LOGIN_FAILED,
    LOGOUT_SUCCESS,
    LOGOUT_FAILED,
    SESSION_EXPIRED,

    // ── OTP verification (feature.verifyPin) ────────────────────────────────
    OTP_VERIFIED,
    OTP_VERIFICATION_FAILED,

    // ── PIN verification (feature.verifyPin) ────────────────────────────────
    PIN_VERIFIED,
    PIN_VERIFY_FAILED,

    // ── Face auth (feature.faceAuth, core.faceAuth) ─────────────────────────
    FACE_REGISTER_SUCCESS,
    FACE_REGISTER_FAILED,
    FACE_CAPTURE_FAILED,
    FACE_DUPLICATE_FOUND,
    FACE_VERIFY_SUCCESS,
    FACE_VERIFY_FAILED,
    FACE_DELETE_SUCCESS,
    FACE_DELETE_FAILED,
    FACE_LOCK_SESSION,
    FACE_LOCK_TIMEOUT,

    // ── Dashboard (feature.dashboard) ───────────────────────────────────────
    USER_FETCH_FAILED,
    DASHBOARD_LOAD_FAILED,
    KEK_ROTATE_SUCCESS,
    KEK_ROTATE_FAILED,

    // ── Dispense flow (feature.dispenseFlow) ────────────────────────────────
    RX_SCAN_SUCCESS,
    RX_SCAN_FAILED,
    RX_CONFIRMED,
    RX_CANCELLED,
    NDC_SCAN_SUCCESS,
    NDC_SCAN_FAILED,
    NDC_MISMATCH,
    NDC_NOT_FOUND,
    SUBSTITUTE_CONFIRMED,
    VIAL_SCAN_FAILED,
    DISPENSE_RESUMED,
    DISPENSE_RESUME_FAILED,
    DISPENSE_COUNT,
    DISPENSE_FAILED,

    // ── Batch count / inventory flow (feature.batchCount, feature.inventoryFlow) ─
    BATCH_COUNT,
    BATCH_CREATE_FAILED,
    BATCH_DELETE_SUCCESS,
    BATCH_PROCESS_FAILED,
    BATCH_DELETE_FAILED,
    INVENTORY_SCAN_FAILED,
    INVENTORY_COUNT_SAVED,
    INVENTORY_COUNT_FAILED,
    STOCK_COUNT_COMPLETE,
    STOCK_COUNT_DISCARDED,

    // ── Pill scanning / counting (core.scanning camera workflow) ────────────
    COUNT_RESUME,
    PILL_COUNT_SAVED,
    PILL_COUNT_FAILED,
    PILL_COUNT_RESET,
    TRAY_CLASSIFY_FAILED,
    GLOVE_DETECT_FAILED,
    MODEL_LOAD_FAILED,

    // ── History (feature.history) ────────────────────────────────────────────
    HISTORY_LOAD_FAILED,
    HISTORY_DELETE_SUCCESS,
    HISTORY_DELETE_FAILED,

    // ── HL7 (feature.hl7, core.hl7) ──────────────────────────────────────────
    HL7_SEND_SUCCESS,
    HL7_SEND_FAILED,
    HL7_RECEIVE_FAILED,
    HL7_CONNECT_FAILED,
    HL7_RESEND_FAILED,
    HL7_ORDER_EDIT,
    HL7_ORDER_CANCEL,
    HL7_SERVICE_ERROR,
    HL7_SERVICE_STOPPED,

    // ── Sync / offline (feature.unsyncedTransaction) ─────────────────────────
    TRANSACTION_SYNC_SUCCESS,
    TRANSACTION_SYNC_FAILED,
    INVENTORY_SYNC_FAILED,
    SYNC_RETRY_FAILED,

    // ── Profile / terminal / settings (feature.profile, feature.settings) ────
    PROFILE_UPDATE_SUCCESS,
    PROFILE_UPDATE_FAILED,
    PROFILE_DELETE_FAILED,
    TERMINAL_UPDATE_FAILED,
    TERMINAL_LOAD_FAILED,
    SETTINGS_FETCH_FAILED,
    SETTINGS_APPLY_FAILED,
    TRANSACTION_PURGE_SUCCESS,
    TRANSACTION_PURGE_FAILED,
    PMS_TEST_SUCCESS,
    PMS_TEST_FAILED,

    // ── Drug lookup (core.scanning DrugRepository) ───────────────────────────
    DRUG_LOOKUP_FAILED,
    DRUG_IMAGE_FAILED,

    // ── Token / health (core.refreshToken, core.health) ──────────────────────
    TOKEN_REFRESH_SUCCESS,
    TOKEN_REFRESH_FAILED,
    TOKEN_FETCH_FAILED,
    HEALTH_CHECK_FAILED,

    // ── Security (core.security, core.room) ──────────────────────────────────
    DB_KEY_ROTATE_FAILED,
    AUDIT_LOG_FAILED,
    SECURITY_CHECK_FAILED,
    CACHE_READ_FAILED,
    NAVIGATION_FAILED,

    // ── Generic infra ─────────────────────────────────────────────────────────
    SCAN_TIMEOUT,
    SCAN_FAILED,
    NETWORK_TIMEOUT,
    NETWORK_ERROR,
    DATABASE_ERROR,
    BLUETOOTH_ERROR,
    PERMISSION_DENIED,
    FILE_READ_ERROR,
    FILE_WRITE_ERROR,

    // ── Fallbacks (used by RemoteLogDestination when a call site omits event) ──
    APP_CRASH,
    PARSING_ERROR,
    FUNCTIONALITY_ERROR,
    RUNTIME_ERROR,
    UNKNOWN_ERROR
}
