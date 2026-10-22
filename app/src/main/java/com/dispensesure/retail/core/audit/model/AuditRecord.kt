package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One line of the audit file before encryption: the fields every event shares, plus its tag and data. */
@Serializable
data class AuditRecord(
    @SerialName("event_id") val eventId: String,
    val seq: Long,
    val timestamp: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String?,
    val email: String?,
    @SerialName("operator_id") val operatorProfileId: Long?,
    @SerialName("operator_name") val operatorName: String?,
    @SerialName("device_key") val deviceKey: String?,
    @SerialName("terminal_id") val terminalId: String?,
    @SerialName("app_version") val appVersion: String,
    val tag: String,
    val result: AuditResult,
    val data: JsonObject
)
