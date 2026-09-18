package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF

// classId: 0 = pill, 1 = broken_pill, 2 = half_pill (pill detector head order).
// All three count as one pill; the id is metadata for overlay and logging.
data class Detection(
    val rect: RectF,
    val confidence: Float,
    val classId: Int = 0
)
