package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What started the /health check that found the app offline. */
@Serializable
enum class OfflineTrigger { REQUEST_FAILED, APP_RESUMED, NETWORK_RETURNED, PREFLIGHT }

@Serializable
enum class OfflineEndedBy { BACK_ONLINE, OFFLINE_LIMIT_REACHED, LOGGED_OUT, APP_RESTARTED }

@Serializable
@SerialName("OFFLINE_MODE_ENTERED")
data class OfflineModeEntered(
    val trigger: OfflineTrigger,
    @SerialName("last_good_health_at") val lastGoodHealthAt: String?,
    @SerialName("offline_limit_seconds") val offlineLimitSeconds: Long
) : AuditLogEvent() {
    override val result: AuditResult get() = AuditResult.FAILURE
}

@Serializable
@SerialName("OFFLINE_MODE_EXITED")
data class OfflineModeExited(
    @SerialName("ended_by") val endedBy: OfflineEndedBy,
    @SerialName("offline_started_at") val offlineStartedAt: String,
    @SerialName("duration_seconds") val durationSeconds: Long
) : AuditLogEvent()
