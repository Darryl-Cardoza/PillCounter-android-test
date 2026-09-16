package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF

/**
 * One detection produced by the glove model
 * (YOLOX-Nano `gloves_detector_fp32.tflite`).
 *
 * @param rect       Bounding box in original-image pixel coordinates.
 * @param confidence Detection score in [0, 1]  (= sigmoid(obj) * max(sigmoid(cls))).
 * @param classId    0 = gloves, 1 = no_gloves  (matches data/coco_to_yolox.py CLASS_MAP).
 *                   "no_gloves" means a hand with no glove on it.
 * @param className  "gloves" or "no_gloves".
 */
data class GloveDetection(
    val rect: RectF,
    val confidence: Float,
    val classId: Int,
    val className: String
)
