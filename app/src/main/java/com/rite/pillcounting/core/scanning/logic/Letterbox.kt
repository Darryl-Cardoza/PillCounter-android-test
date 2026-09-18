package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.min

/**
 * Letterboxes a camera frame to the model's square input. The output Bitmap,
 * Canvas, Matrix, and Paint are all reused across calls — 640×640 ARGB_8888
 * is ~1.6 MB, and allocating one per frame was the largest GC contributor in
 * the per-frame hot path.
 *
 * Not thread-safe — call from a single coroutine (CameraX's ImageAnalysis
 * delivers frames sequentially, so this is fine in practice).
 */
object Letterbox {

    data class ScaleInfo(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val inputSize: Int
    )

    var currentScaleInfo: ScaleInfo? = null
        private set

    private var outputBitmap: Bitmap? = null
    private var outputCanvas: Canvas? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun preprocess(src: Bitmap, targetSize: Int = 640): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val scale = min(targetSize / w, targetSize / h)
        val newW = w * scale
        val newH = h * scale
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        currentScaleInfo = ScaleInfo(
            scale = scale,
            padX = padX,
            padY = padY,
            inputSize = targetSize
        )

        // (Re)allocate only if size changed or first call. Steady-state hits
        // the cached path with zero allocations beyond the Matrix mutation.
        val output = outputBitmap.let { existing ->
            if (existing != null && !existing.isRecycled
                && existing.width == targetSize && existing.height == targetSize) {
                existing
            } else {
                val fresh = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                outputBitmap = fresh
                outputCanvas = Canvas(fresh)
                fresh
            }
        }
        val canvas = outputCanvas ?: Canvas(output).also { outputCanvas = it }

        canvas.drawColor(Color.BLACK)

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(padX, padY)
        canvas.drawBitmap(src, matrix, paint)

        return output
    }
}
