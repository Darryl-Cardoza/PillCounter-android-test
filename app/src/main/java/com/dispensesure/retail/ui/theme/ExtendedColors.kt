package com.dispensesure.retail.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class ExtendedColors(
    val primaryBackground: Color,
    val secondaryBackground: Color,
    val textColor: Color,
    val inputBackground: Color,
    val statusChipBackgroundOnPrimary: Color,
    val statusChipBackgroundOnSecondary: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        primaryBackground = PrimaryBackground,
        secondaryBackground = SecondaryBackground,
        textColor = TextColor,
        inputBackground = inputBackground,
        statusChipBackgroundOnPrimary = statusChipBackgroundOnPrimary,
        statusChipBackgroundOnSecondary = statusChipBackgroundOnSecondary
    )
}
