package com.rite.pillcounting.feature.verifyPin.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the user object returned after OTP verification.
 */
@JsonClass(generateAdapter = true)
data class VerifiedUser(

    /** Unique user identifier */
    @Json(name = "user_id")
    val userId: String? = null,

    /** User's email address */
    @Json(name = "email")
    val email: String? = null,

    /** Whether the user's email is verified */
    @Json(name = "is_verified")
    val isVerified: Boolean? = null,

    /** Role object containing id and name */
    @Json(name = "role")
    val role: UserRole? = null,

    /** Whether the user account is locked */
    @Json(name = "auth_is_locked")
    val authIsLocked: Boolean? = null,

    /** Whether the user have access to hl7 service or not **/
    @Json(name = "is_hl7_enabled")
    val isHl7Enabled: Boolean? = null,

    /** Whether the user has completed their profile setup */
    @Json(name = "is_profile_completed")
    val isProfileCompleted: Boolean? = null,

    /** Whether the user's pharmacy is PMS integrated */
    @Json(name = "is_pms_integrated")
    val isPmsIntegrated: Boolean? = null,

    /** Whether SSL certificate verification should be bypassed */
    @Json(name = "bypass_ssl")
    val bypassSsl: Boolean? = null,

    /** Whether the account runs in standalone mode */
    @Json(name = "is_standalone")
    val isStandalone: Boolean? = null,

    /** Maximum number of terminals allowed for this account */
    @Json(name = "terminal_limit")
    val terminalLimit: Int? = null,

    /** Maximum number of devices allowed for this account */
    @Json(name = "device_limit")
    val deviceLimit: Int? = null
)
