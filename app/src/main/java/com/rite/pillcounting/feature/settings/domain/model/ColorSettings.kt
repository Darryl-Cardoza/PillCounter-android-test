package com.rite.pillcounting.feature.settings.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the complete color theme configuration for both light and dark modes.
 *
 * This model is typically received from a remote API and used to dynamically
 * theme the application UI based on user preference or system settings.
 *
 * @property light The set of colors used in light mode.
 * @property dark The set of colors used in dark mode.
 */
@JsonClass(generateAdapter = true)
data class ColorSettings(
    @Json(name = "light") val light: ThemeColors,
    @Json(name = "dark") val dark: ThemeColors
)
