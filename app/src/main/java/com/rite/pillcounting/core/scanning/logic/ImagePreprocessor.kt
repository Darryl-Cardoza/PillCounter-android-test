package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resizes camera frames to the model's 640×640 letterbox input and converts to a
 * normalized float ByteBuffer that TFLite can consume directly.
 *
 * Hot path: called once per analyzed frame. The output ByteBuffer must be
 * allocated fresh each call — [PillAnalyzer] hands out three duplicate() views
 * of the same backing memory to run inference in parallel, so reusing the
 * buffer across frames would race with in-flight inference. The IntArray pixel
 * scratch IS safe to reuse: it's fully consumed before this function returns,
 * before any duplicate() view is taken.
 */
object ImagePreprocessor {

    private const val INPUT_SIZE = 640
    private const val FLOATS_PER_PIXEL = 3
    private const val BYTES_PER_FLOAT = 4

    // Lazy-init reusable pixel scratch. Safe to reuse: it's only read while
    // bitmapToFloatBuffer is running. Saves ~1.6 MB allocation per frame.
    private val pixelScratch: IntArray by lazy { IntArray(INPUT_SIZE * INPUT_SIZE) }

    fun preprocess(
        image: ImageProxy
    ): Triple<ByteBuffer, Bitmap, Bitmap> {

        val bitmap = image.toBitmap()
        val letterboxed = Letterbox.preprocess(bitmap, INPUT_SIZE)

        val buffer = bitmapToFloatBuffer(letterboxed)

        return Triple(buffer, letterboxed, bitmap)
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * FLOATS_PER_PIXEL * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())

        val pixels = pixelScratch
        bitmap.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        // Bulk-fill a FloatArray then put() it via the FloatBuffer view in one
        // call. Measurably faster than 409k individual putFloat() calls on weak
        // ARM devices because the JIT can hoist bounds checks across the loop.
        val n = pixels.size
        val rgb = FloatArray(FLOATS_PER_PIXEL * n)
        var i = 0
        var j = 0
        while (i < n) {
            val p = pixels[i]
            rgb[j] = ((p shr 16) and 0xFF) / 255f
            rgb[j + 1] = ((p shr 8) and 0xFF) / 255f
            rgb[j + 2] = (p and 0xFF) / 255f
            i++
            j += FLOATS_PER_PIXEL
        }
        buffer.asFloatBuffer().put(rgb)

        buffer.rewind()
        return buffer
    }
}