package com.rite.pillcounting.feature.settings.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Defines the complete color palette for a specific theme mode (light or dark).
 *
 * This data class centralizes all color definitions used throughout the application UI.
 * Each property maps to a specific UI element or context, making it easier to apply
 * consistent theming dynamically.
 *
 * @property primary The primary brand color used for main actions, buttons, etc.
 * @property secondary A secondary accent color used to complement the primary.
 * @property tertiary A third-level supporting color, often for icons or accents.
 * @property primaryBackground The main background color used across large containers or screens.
 * @property secondaryBackground The secondary background used for cards, panels, etc.
 * @property textColor The default color used for standard text content.
 * @property inputBackground The background color for input fields or form components.
 * @property statusChipBackgroundOnPrimary Background for status chips/icons placed over a primary background.
 * @property statusChipBackgroundOnSecondary Background for status chips/icons placed over a secondary background.
 */
@JsonClass(generateAdapter = true)
data class ThemeColors(
    @Json(name = "primary") val primary: String,
    @Json(name = "secondary") val secondary: String,
    @Json(name = "tertiary") val tertiary: String,
    @Json(name = "primaryBackground") val primaryBackground: String,
    @Json(name = "secondaryBackground") val secondaryBackground: String,
    @Json(name = "textColor") val textColor: String,
    @Json(name = "inputBackground") val inputBackground: String,
    @Json(name = "statusChipBackgroundOnPrimary") val statusChipBackgroundOnPrimary: String,
    @Json(name = "statusChipBackgroundOnSecondary") val statusChipBackgroundOnSecondary: String
)
