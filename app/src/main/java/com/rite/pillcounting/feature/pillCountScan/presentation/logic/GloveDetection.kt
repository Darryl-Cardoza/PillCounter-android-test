package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import android.graphics.RectF

/**
 * One detection produced by the glove model
 * (YOLOX-Nano `gloves_yolox_nano_lrelu_320_fp16.tflite`).
 *
 * @param rect       Bounding box in original-image pixel coordinates.
 * @param confidence Detection score in [0, 1]  (= sigmoid(obj) * max(sigmoid(cls))).
 * @param classId    0 = gloves, 1 = hands  (matches data/coco_to_yolox.py CLASS_MAP).
 *                   "hands" means bare hand visible (no gloves on).
 * @param className  "gloves" or "hands".
 */
data class GloveDetection(
    val rect: RectF,
    val confidence: Float,
    val classId: Int,
    val className: String
)
