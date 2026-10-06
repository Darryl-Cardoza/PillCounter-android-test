package com.dispensesure.retail.core.utils.logger.destination.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Body sent to `/mobile/logs` by [com.dispensesure.retail.core.utils.logger.destination.RemoteLogDestination].
 * Field names/shape match the Datadog log payload agreed with the backend team.
 */
@JsonClass(generateAdapter = true)
data class RemoteLogRequest(
    @Json(name = "device_key") val deviceKey: String?,
    @Json(name = "app_name") val appName: String,
    @Json(name = "app_version") val appVersion: String,
    @Json(name = "build_version") val buildVersion: String? = null,
    @Json(name = "platform") val platform: String,
    @Json(name = "os_version") val osVersion: String,
    @Json(name = "device_model") val deviceModel: String,
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "log_id") val logId: String,
    @Json(name = "severity") val severity: Int,
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "message") val message: String,
    @Json(name = "tag") val tag: String,
    @Json(name = "event") val event: String,
    @Json(name = "context") val context: Map<String, Any?>? = null,
    @Json(name = "error") val error: RemoteLogError? = null,
    @Json(name = "network") val network: RemoteLogNetwork
)

@JsonClass(generateAdapter = true)
data class RemoteLogError(
    @Json(name = "type") val type: String,
    @Json(name = "message") val message: String,
    @Json(name = "stack_trace") val stackTrace: String,
    @Json(name = "is_fatal") val isFatal: Boolean
)

@JsonClass(generateAdapter = true)
data class RemoteLogNetwork(
    @Json(name = "type") val type: String,
    @Json(name = "is_online") val isOnline: Boolean
)
