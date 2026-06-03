package com.rite.pillcounting.feature.settings.domain.model

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
    @Json(name = "placeholderLogo") val placeholderLogo: String
)
