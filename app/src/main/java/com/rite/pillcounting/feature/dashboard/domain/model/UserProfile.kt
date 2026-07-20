package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents nested user profile information.
 */
@JsonClass(generateAdapter = true)
data class UserProfile(
    @Json(name = "lname") val lName: String? = null,
    @Json(name = "fname") val fName: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "phone_number") val phoneNumber: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    @Json(name = "is_profile_completed") val isProfileCompleted: Boolean? = null,
    @Json(name = "pharmacy_name") val pharmacyName: String? = null,
    @Json(name = "npi_id") val npiId: String? = null,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "role") val role: UserRole? = null,
    @Json(name = "pharmacy_type") val pharmacyType: String? = null,
    @Json(name = "is_verified") val isVerified: Boolean? = null
)