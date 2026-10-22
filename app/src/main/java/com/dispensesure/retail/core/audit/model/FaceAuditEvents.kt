package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class ScreenLockReason { IDLE_TIMEOUT, MENU, AFTER_LOGIN, APP_LAUNCH }

/** Match scores are recorded to 2 decimals. */
fun roundedMatchScore(score: Float): Double = (score * 100).roundToInt() / 100.0

@Serializable
@SerialName("SCREEN_LOCKED")
data class ScreenLocked(val reason: ScreenLockReason) : AuditLogEvent()

@Serializable
@SerialName("FACE_VERIFIED")
data class FaceVerified(
    @SerialName("face_profile_id") val faceProfileId: Long,
    @SerialName("match_score") val matchScore: Double,
    @SerialName("previous_operator_id") val previousOperatorProfileId: Long?
) : AuditLogEvent()

@Serializable
@SerialName("FACE_VERIFY_FAILED")
data class FaceVerifyFailed(
    @SerialName("best_score") val bestScore: Double
) : AuditLogEvent() {
    override val result: AuditResult get() = AuditResult.FAILURE
}

@Serializable
@SerialName("FACE_PROFILE_ENROLLED")
data class FaceProfileEnrolled(
    @SerialName("face_profile_id") val faceProfileId: Long,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("enrolled_by_email") val enrolledByEmail: String?,
    @SerialName("duplicate_warning_overridden") val duplicateWarningOverridden: Boolean
) : AuditLogEvent()

@Serializable
@SerialName("FACE_PROFILE_STATUS_CHANGED")
data class FaceProfileStatusChanged(
    @SerialName("face_profile_id") val faceProfileId: Long,
    @SerialName("old_enabled") val oldEnabled: Boolean,
    @SerialName("new_enabled") val newEnabled: Boolean,
    @SerialName("lock_released") val lockReleased: Boolean
) : AuditLogEvent()

@Serializable
@SerialName("FACE_PROFILE_DELETED")
data class FaceProfileDeleted(
    @SerialName("face_profile_id") val faceProfileId: Long,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("deleted_by_email") val deletedByEmail: String?
) : AuditLogEvent()
