package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents user preferences/settings.
 */
@JsonClass(generateAdapter = true)
data class UserSettings(
    @Json(name = "notifications_enabled") val notificationsEnabled: Boolean? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "hl7_version") val hl7Version: String? = null,
    @Json(name = "terminals") val terminals: List<Terminal>? = null,
    @Json(name = "bucket") val bucket: List<String>? = null,
    @Json(name = "is_pms_integrated") val isPMSIntegrated: Boolean? = null,
    @Json(name = "allow_local_storage") val allowLocalStorage: Boolean? = null,
    @Json(name = "bypass_ssl") val bypassSSL: Boolean? = null,
    @Json(name = "hl7_message_spec") val hl7MessageSpec: String? = null,
)