package com.dispensesure.retail.core.utils.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Every tunable value of the Login / OTP look lives in this file,
// so the design can be adjusted here without touching screen code.

/** Which theme colour a background blob is painted with. */
enum class BlobColorRole { PRIMARY, SECONDARY }

/**
 * One soft, feathered circle behind the card. Centre is a fraction of the screen
 * width/height; radius is a fraction of the screen's longer side.
 */
data class BackgroundBlob(
    val colorRole: BlobColorRole,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val lightAlpha: Float,
    val darkAlpha: Float,
)

val backgroundBlobs = listOf(
    // Cyan, top-left (large) and bottom-left (small)
    BackgroundBlob(BlobColorRole.PRIMARY, centerX = 0.10f, centerY = 0.15f, radius = 0.45f, lightAlpha = 0.18f, darkAlpha = 0.22f),
    BackgroundBlob(BlobColorRole.PRIMARY, centerX = 0.02f, centerY = 0.95f, radius = 0.25f, lightAlpha = 0.14f, darkAlpha = 0.18f),
    // Pink, top-right (small) and bottom-right (large)
    BackgroundBlob(BlobColorRole.SECONDARY, centerX = 0.92f, centerY = 0.05f, radius = 0.25f, lightAlpha = 0.10f, darkAlpha = 0.16f),
    BackgroundBlob(BlobColorRole.SECONDARY, centerX = 0.88f, centerY = 0.85f, radius = 0.40f, lightAlpha = 0.10f, darkAlpha = 0.16f),
)

// Card size and shape
val CARD_MAX_WIDTH_PHONE = 360.dp
val CARD_MAX_WIDTH_TABLET = 440.dp
val CARD_CORNER_RADIUS = 16.dp
val CARD_OUTLINE_WIDTH = 1.dp

// Card fill in light mode. Dark mode keeps the theme's primaryBackground.
val CARD_BACKGROUND_LIGHT = Color(0xFFFFFFFF)

// Light: grey outline + dark shadow. Dark: light outline + light glow.
const val CARD_OUTLINE_ALPHA_LIGHT = 0.12f
const val CARD_OUTLINE_ALPHA_DARK = 0.35f
const val CARD_SHADOW_ALPHA_LIGHT = 0.15f
const val CARD_GLOW_ALPHA_DARK = 0.25f
val CARD_SHADOW_ELEVATION = 16.dp

// Phone landscape is short, so the logo and footer are hidden there unless this is true.
const val SHOW_LOGO_AND_FOOTER_ON_PHONE_LANDSCAPE = false

fun shouldShowLogoAndFooter(
    isLandscape: Boolean,
    isTablet: Boolean,
    showOnPhoneLandscape: Boolean = SHOW_LOGO_AND_FOOTER_ON_PHONE_LANDSCAPE,
): Boolean {
    val isPhoneLandscape = isLandscape && !isTablet
    return !isPhoneLandscape || showOnPhoneLandscape
}

fun cardMaxWidth(isTablet: Boolean): Dp =
    if (isTablet) CARD_MAX_WIDTH_TABLET else CARD_MAX_WIDTH_PHONE
