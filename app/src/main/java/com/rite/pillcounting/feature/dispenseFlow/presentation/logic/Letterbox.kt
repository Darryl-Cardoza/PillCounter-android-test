package com.rite.pillcounting.feature.dispenseFlow.presentation.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.min

object Letterbox {

    data class ScaleInfo(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val inputSize: Int
    )

    var currentScaleInfo: ScaleInfo? = null
        private set

    fun preprocess(
        src: Bitmap,
        targetSize: Int = 640
    ): Bitmap {

        val w = src.width.toFloat()
        val h = src.height.toFloat()

        // SCALE (same as iOS)
        val scale = min(targetSize / w, targetSize / h)

        val newW = w * scale
        val newH = h * scale

        // PADDING (center)
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        currentScaleInfo = ScaleInfo(
            scale = scale,
            padX = padX,
            padY = padY,
            inputSize = targetSize
        )

        // CREATE OUTPUT BITMAP
        val output = Bitmap.createBitmap(
            targetSize,
            targetSize,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(padX, padY)
        }

        canvas.drawBitmap(src, matrix, Paint(Paint.ANTI_ALIAS_FLAG))

        return output
    }
}