package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF

data class Detection(
    val rect: RectF,
    val confidence: Float
)