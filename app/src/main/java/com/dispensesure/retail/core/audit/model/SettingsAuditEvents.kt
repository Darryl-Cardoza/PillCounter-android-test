package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Settings the user changes on the device. */
@Serializable
enum class SecuritySetting {
    DOUBLE_COUNT_SCHEDULES,
    REQUIRE_BACK_COUNT,
    FACE_LOCK_TIMEOUT_MINUTES,
    HAZARDOUS_DETECTION,
    TRAY_COLORS,
    HISTORY_RETENTION_DAYS
}

/** Settings the server sets through /auth/me or mobile settings. */
@Serializable
enum class ServerSetting {
    HL7_ENABLED,
    BYPASS_TLS,
    PMS_IP,
    PMS_PORT,
    STATIC_PMS_CONNECTION,
    ALLOW_LOCAL_STORAGE,
    OFFLINE_LIMIT_SECONDS,
    TERMINAL
}

@Serializable
enum class CertPinChoice { CONFIRM, CANCEL }

@Serializable
@SerialName("SETTING_CHANGED")
data class SettingChanged(
    val setting: SecuritySetting,
    val old: String?,
    val new: String?
) : AuditLogEvent() {
    companion object {
        /** The event for [setting] going from [old] to [new], or null when the value didn't change. */
        fun ifChanged(setting: SecuritySetting, old: Any?, new: Any?): SettingChanged? =
            if (old == new) null else SettingChanged(setting, old?.toString(), new?.toString())
    }
}

@Serializable
@SerialName("LOCAL_DATA_CLEARED")
data class LocalDataCleared(
    @SerialName("rows_deleted") val rowsDeleted: Map<String, Int>,
    @SerialName("unsynced_dispense_ids") val unsyncedDispenseIds: List<Long>,
    @SerialName("unsynced_batch_ids") val unsyncedBatchIds: List<Long>
) : AuditLogEvent()

@Serializable
@SerialName("SERVER_CONFIG_CHANGED")
data class ServerConfigChanged(
    val setting: ServerSetting,
    val old: String?,
    val new: String?
) : AuditLogEvent() {
    companion object {
        /** The event for [setting] going from [old] to [new], or null when the value didn't change. */
        fun ifChanged(setting: ServerSetting, old: Any?, new: Any?): ServerConfigChanged? =
            if (old == new) null else ServerConfigChanged(setting, old?.toString(), new?.toString())
    }
}

@Serializable
@SerialName("PMS_CERT_PIN_MISMATCH")
data class PmsCertPinMismatch(
    @SerialName("old_fingerprint") val oldFingerprint: String?,
    val choice: CertPinChoice
) : AuditLogEvent()
