package com.dispensesure.retail.feature.settings.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the entire settings response from the API.
 */
/**
 * Represents the "settings" object, containing colors and logos.
 */
@JsonClass(generateAdapter = true)
data class ApplicationSettingsResponse(
    @Json(name = "colors") val colors: ColorSettings,
    @Json(name = "appLogo") val appLogo: String,
    @Json(name = "placeholderLogo") val placeholderLogo: String,
    /**
     * Backend-controlled maximum time (seconds) the user is allowed to operate offline
     * before being forced back to Login. Nullable so older backends still deserialize;
     * `SessionHealthController` falls back to the persisted default when this is null.
     */
    @Json(name = "offline_session_threshold_seconds") val offlineSessionThresholdSeconds: Long? = null
)
