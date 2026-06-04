package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resizes camera frames to the model 640×640 letterbox input and produces the
 * float buffer the per-frame pipeline needs for the TFLite pill + glove models:
 *
 *  - [Preprocessed.rgbNormalized]  NHWC RGB / 255 at 640x640 (pill + glove TFLite)
 *  - [Preprocessed.letterboxed]    640x640 ARGB_8888 Bitmap (handed to the tray
 *                                  segmentation detector, which does its own
 *                                  640->384 downscale + [0, 255] rescale internally)
 *
 * **All scratch is reused across frames.** Each frame writes into the same
 * FloatArray and direct ByteBuffer (~10 MB total). PillAnalyzer hands out
 * `duplicate()` views and `coroutineScope { ... }.await()` guarantees all
 * three parallel inferences complete before `analyze()` returns, so the next
 * frame cannot start until the current frame's reads are done.
 *
 * NOT thread-safe across concurrent callers — CameraX's ImageAnalysis use
 * case delivers frames sequentially, which is what makes this safe.
 */
object ImagePreprocessor {

    private const val INPUT_SIZE = 640
    private const val NUM_PIXELS = INPUT_SIZE * INPUT_SIZE
    private const val CHANNELS = 3
    private const val BYTES_PER_FLOAT = 4
    private const val BUFFER_BYTES = CHANNELS * NUM_PIXELS * BYTES_PER_FLOAT

    // Cached scratch — allocated once, reused every frame.
    private val pixelScratch = IntArray(NUM_PIXELS)
    private val rgbScratch = FloatArray(CHANNELS * NUM_PIXELS)
    private val rgbBuf: ByteBuffer =
        ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder())

    data class Preprocessed(
        val rgbNormalized: ByteBuffer,
        val letterboxed: Bitmap,
        val original: Bitmap
    )

    fun preprocess(image: ImageProxy): Preprocessed {
        val original = image.toBitmap()
        val letterboxed = Letterbox.preprocess(original, INPUT_SIZE)

        letterboxed.getPixels(pixelScratch, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val rgb = rgbScratch

        var i = 0
        var j = 0
        while (i < NUM_PIXELS) {
            val p = pixelScratch[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()

            // NHWC RGB / 255 — what the pill and glove TFLite models expect.
            rgb[j] = r / 255f
            rgb[j + 1] = g / 255f
            rgb[j + 2] = b / 255f

            i++
            j += CHANNELS
        }

        rgbBuf.clear()
        rgbBuf.asFloatBuffer().put(rgb)
        rgbBuf.rewind()

        return Preprocessed(
            rgbNormalized = rgbBuf,
            letterboxed = letterboxed,
            original = original
        )
    }
}