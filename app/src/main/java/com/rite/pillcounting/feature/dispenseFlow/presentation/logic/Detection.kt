package com.rite.pillcounting.feature.dispenseFlow.presentation.logic

import android.graphics.RectF

data class Detection(
    val rect: RectF,
    val confidence: Float
)