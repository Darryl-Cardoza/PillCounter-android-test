package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import android.graphics.RectF

/**
 * One detection produced by the glove model (best_float32.tflite).
 *
 * @param rect       Bounding box in original-image pixel coordinates.
 * @param confidence Detection score in [0, 1].
 * @param classId    0 = gloves, 1 = no_gloves  (matches Python CLASSES list).
 * @param className  "gloves" or "no_gloves".
 */
data class GloveDetection(
    val rect: RectF,
    val confidence: Float,
    val classId: Int,
    val className: String
)
