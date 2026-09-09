package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/**
 * Letterboxes a camera frame (or a rectangular region of it) to the model's
 * square input. The output Bitmap, Canvas, Matrix, and Paint are all reused
 * across calls — 640×640 ARGB_8888 is ~1.6 MB, and allocating one per frame
 * was the largest GC contributor in the per-frame hot path.
 *
 * Not thread-safe — call from a single coroutine (CameraX's ImageAnalysis
 * delivers frames sequentially, so this is fine in practice).
 */
object Letterbox {

    /**
     * Maps source-frame pixels to letterbox pixels:
     *   x_in = (x_src - offsetX) * scale + padX
     * [offsetX]/[offsetY] are the source-frame origin of the letterboxed region
     * (0 when the whole frame was letterboxed, the crop's top-left otherwise).
     */
    data class ScaleInfo(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val inputSize: Int,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f
    )

    var currentScaleInfo: ScaleInfo? = null
        private set

    private var outputBitmap: Bitmap? = null
    private var outputCanvas: Canvas? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Crop path only: the tray region is usually *upscaled* to 640, so sample
    // bilinearly instead of duplicating pixels.
    private val cropDstRect = RectF()
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * @param srcRect  Region of [src] to letterbox. `null` letterboxes the whole
     *                 frame. Pixels outside the rect never reach the output —
     *                 the padding stays black.
     */
    fun preprocess(src: Bitmap, targetSize: Int = 640, srcRect: Rect? = null): Bitmap {
        val w = (srcRect?.width() ?: src.width).toFloat()
        val h = (srcRect?.height() ?: src.height).toFloat()
        val scale = min(targetSize / w, targetSize / h)
        val newW = w * scale
        val newH = h * scale
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        currentScaleInfo = ScaleInfo(
            scale = scale,
            padX = padX,
            padY = padY,
            inputSize = targetSize,
            offsetX = srcRect?.left?.toFloat() ?: 0f,
            offsetY = srcRect?.top?.toFloat() ?: 0f
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

        if (srcRect != null) {
            // Blit only the crop: drawBitmap(src, srcRect, dst) never samples
            // outside srcRect, so nothing beyond the crop leaks into the padding.
            cropDstRect.set(padX, padY, padX + newW, padY + newH)
            canvas.drawBitmap(src, srcRect, cropDstRect, cropPaint)
        } else {
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(padX, padY)
            canvas.drawBitmap(src, matrix, paint)
        }

        return output
    }
}