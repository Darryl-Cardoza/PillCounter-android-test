package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Size of the oval face guide over the camera preview.
// Shared so both face screens draw the same oval.

/** Full size, used on tablets. */
internal val FACE_OVAL_MAX_WIDTH: Dp = 450.dp

/** How much of the preview the oval may fill. */
internal const val FACE_OVAL_FILL_FRACTION = 0.80f

/** Oval width / height. */
internal const val FACE_OVAL_ASPECT_RATIO = 450f / 550f

/** Corner radius in percent, so the shape holds when the oval shrinks. */
internal const val FACE_OVAL_CORNER_PERCENT = 44

/**
 * Draws the face guide centred over the camera preview.
 *
 * @param color Border colour; registration and verify each use their own.
 */
@Composable
internal fun BoxWithConstraintsScope.FaceOvalGuide(color: Color) {
    // Full size on tablets, smaller on phones so the oval always fits.
    val ovalWidth = minOf(
        FACE_OVAL_MAX_WIDTH,
        maxWidth * FACE_OVAL_FILL_FRACTION,
        maxHeight * FACE_OVAL_FILL_FRACTION * FACE_OVAL_ASPECT_RATIO
    )
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .width(ovalWidth)
            .aspectRatio(FACE_OVAL_ASPECT_RATIO)
            .border(width = 3.dp, color = color, shape = RoundedCornerShape(percent = FACE_OVAL_CORNER_PERCENT))
    )
}
