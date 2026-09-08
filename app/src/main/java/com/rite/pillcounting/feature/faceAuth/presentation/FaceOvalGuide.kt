package com.rite.pillcounting.feature.faceAuth.presentation

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
