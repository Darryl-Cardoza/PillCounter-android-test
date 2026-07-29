package com.rite.pillcounting.feature.profile.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProfileUpdateRequest(
    @Json(name = "fname") val fName: String,
    @Json(name = "lname") val lName: String,
    @Json(name = "pharmacy_name") val pharmacyName: String,
    @Json(name = "phone_number") val phoneNumber: String,
    @Json(name = "npi_id") val npiId: String,
    @Json(name = "is_profile_complete") val isProfileComplete: Boolean,
    @Json(name = "avatar_url") val avatarUrl: String,
    @Json(name = "notifications_enabled") val notificationsEnabled: Boolean,
    @Json(name = "language") val language: String,
    @Json(name = "timezone") val timezone: String,
    @Json(name = "terminal_id") val terminalId: String? = null,
    @Json(name = "pharmacy_type") val pharmacyType: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "state") val state: String? = null
)