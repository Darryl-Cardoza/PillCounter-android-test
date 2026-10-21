package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Fresh launch found one or more failed integrity checks. Logged only; the app keeps working. */
@Serializable
@SerialName("DEVICE_INTEGRITY_CHECK_FAILED")
data class DeviceIntegrityCheckFailed(
    @SerialName("failed_checks") val failedChecks: List<String>,
    @SerialName("not_checked") val notChecked: List<String>,
    val installer: String,
    @SerialName("overlay_packages") val overlayPackages: List<String>
) : AuditLogEvent() {
    override val result: AuditResult get() = AuditResult.FAILURE
}
