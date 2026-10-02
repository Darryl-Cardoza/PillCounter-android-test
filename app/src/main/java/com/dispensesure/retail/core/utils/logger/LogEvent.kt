package com.dispensesure.retail.core.utils.logger

/**
 * The action a [LogEntry] is about, in the two/three-word `SCREAMING_SNAKE_CASE` shape a remote
 * log aggregator (Datadog) expects for its `event` field — e.g. `SCAN_FAILED`, `DISPENSE_FAILED`.
 * Optional on every call site: when omitted, [com.dispensesure.retail.core.utils.logger.destination.RemoteLogDestination]
 * falls back to a generic value derived from the entry itself.
 *
 * Only ERROR entries leave the device, so only failure events are listed.
 */
enum class LogEvent {

    // ── Auth / Login (feature.login, core.auth) ────────────────────────────
    LOGIN_FAILED,
    LOGOUT_FAILED,
    SESSION_EXPIRED,

    // ── OTP verification (feature.verifyPin) ────────────────────────────────
    OTP_VERIFICATION_FAILED,

    // ── PIN verification (feature.verifyPin) ────────────────────────────────
    PIN_VERIFY_FAILED,

    // ── Face auth (feature.faceAuth, core.faceAuth) ─────────────────────────
    FACE_REGISTER_FAILED,
    FACE_CAPTURE_FAILED,
    FACE_DUPLICATE_FOUND,
    FACE_VERIFY_FAILED,
    FACE_DELETE_FAILED,
    FACE_LOCK_SESSION,
    FACE_LOCK_TIMEOUT,

    // ── Dashboard (feature.dashboard) ───────────────────────────────────────
    USER_FETCH_FAILED,
    DASHBOARD_LOAD_FAILED,
    KEK_ROTATE_FAILED,

    // ── Dispense flow (feature.dispenseFlow) ────────────────────────────────
    RX_SCAN_FAILED,
    NDC_SCAN_FAILED,
    VIAL_SCAN_FAILED,
    DISPENSE_RESUME_FAILED,
    DISPENSE_FAILED,

    // ── Batch count / inventory flow (feature.batchCount, feature.inventoryFlow) ─
    BATCH_CREATE_FAILED,
    BATCH_PROCESS_FAILED,
    BATCH_DELETE_FAILED,
    INVENTORY_SCAN_FAILED,
    INVENTORY_COUNT_FAILED,

    // ── Pill scanning / counting (core.scanning camera workflow) ────────────
    PILL_COUNT_FAILED,
    PILL_COUNT_RESET_FAILED,
    TRAY_CLASSIFY_FAILED,
    GLOVE_DETECT_FAILED,
    MODEL_LOAD_FAILED,

    // ── History (feature.history) ────────────────────────────────────────────
    HISTORY_LOAD_FAILED,
    HISTORY_DELETE_FAILED,

    // ── HL7 (feature.hl7, core.hl7) ──────────────────────────────────────────
    HL7_SEND_FAILED,
    HL7_RECEIVE_FAILED,
    HL7_CONNECT_FAILED,
    HL7_RESEND_FAILED,
    HL7_SERVICE_ERROR,
    HL7_SERVICE_STOPPED,

    // ── Sync / offline (feature.unsyncedTransaction) ─────────────────────────
    TRANSACTION_SYNC_FAILED,

    // ── Profile / terminal / settings (feature.profile, feature.settings) ────
    PROFILE_UPDATE_FAILED,
    PROFILE_DELETE_FAILED,
    TERMINAL_UPDATE_FAILED,
    TERMINAL_LOAD_FAILED,
    SETTINGS_FETCH_FAILED,
    SETTINGS_APPLY_FAILED,
    TRANSACTION_PURGE_FAILED,

    // ── Drug lookup (core.scanning DrugRepository) ───────────────────────────
    DRUG_LOOKUP_FAILED,
    DRUG_IMAGE_FAILED,

    // ── Token / health (core.refreshToken, core.health) ──────────────────────
    TOKEN_REFRESH_FAILED,
    TOKEN_FETCH_FAILED,
    HEALTH_CHECK_FAILED,

    // ── Security (core.security, core.room) ──────────────────────────────────
    DB_KEY_ROTATE_FAILED,
    AUDIT_LOG_FAILED,
    SECURITY_CHECK_FAILED,
    CACHE_READ_FAILED,

    // ── Generic infra ─────────────────────────────────────────────────────────
    SCAN_FAILED,
    NETWORK_TIMEOUT,
    NETWORK_ERROR,
    DATABASE_ERROR,
    PERMISSION_DENIED,
    FILE_READ_ERROR,
    FILE_WRITE_ERROR,

    // ── Fallbacks (used by RemoteLogDestination when a call site omits event) ──
    APP_CRASH,
    PARSING_ERROR,
    FUNCTIONALITY_ERROR,
    UNKNOWN_ERROR
}
