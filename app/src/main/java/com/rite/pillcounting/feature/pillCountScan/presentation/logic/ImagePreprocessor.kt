package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resizes camera frames to the model 640×640 letterbox input and produces the
 * two float32 buffers the per-frame pipeline needs:
 *
 *  - [Preprocessed.rgbNormalized]   NHWC RGB / 255             (pill + glove TFLite)
 *  - [Preprocessed.rgbImageNetNchw] NCHW RGB ImageNet-normalized (tray ONNX)
 *
 * **All scratch is reused across frames.** Each frame writes into the same
 * FloatArrays and direct ByteBuffers (~20 MB total). PillAnalyzer hands out
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

    // ImageNet normalization (RGB) — used by the tray masks ONNX model.
    // Constants from MOBILE_INTEGRATION_GUIDE 05_TRAY_MASKS_MODEL.md §4.
    private const val MEAN_R = 123.675f
    private const val MEAN_G = 116.28f
    private const val MEAN_B = 103.53f
    private const val STD_R = 58.395f
    private const val STD_G = 57.12f
    private const val STD_B = 57.375f

    // Cached scratch — allocated once, reused every frame.
    private val pixelScratch = IntArray(NUM_PIXELS)
    private val rgbScratch = FloatArray(CHANNELS * NUM_PIXELS)
    private val nchwScratch = FloatArray(CHANNELS * NUM_PIXELS)
    private val rgbBuf: ByteBuffer =
        ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder())
    private val nchwBuf: ByteBuffer =
        ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder())

    data class Preprocessed(
        val rgbNormalized: ByteBuffer,
        val rgbImageNetNchw: ByteBuffer,
        val letterboxed: Bitmap,
        val original: Bitmap
    )

    fun preprocess(image: ImageProxy): Preprocessed {
        val original = image.toBitmap()
        val letterboxed = Letterbox.preprocess(original, INPUT_SIZE)

        letterboxed.getPixels(pixelScratch, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val rgb = rgbScratch
        val nchw = nchwScratch
        val planeG = NUM_PIXELS
        val planeB = 2 * NUM_PIXELS

        var i = 0
        var j = 0
        while (i < NUM_PIXELS) {
            val p = pixelScratch[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()

            // NHWC RGB / 255
            rgb[j] = r / 255f
            rgb[j + 1] = g / 255f
            rgb[j + 2] = b / 255f

            // NCHW RGB ImageNet
            nchw[i]          = (r - MEAN_R) / STD_R
            nchw[planeG + i] = (g - MEAN_G) / STD_G
            nchw[planeB + i] = (b - MEAN_B) / STD_B

            i++
            j += CHANNELS
        }

        rgbBuf.clear()
        rgbBuf.asFloatBuffer().put(rgb)
        rgbBuf.rewind()
        nchwBuf.clear()
        nchwBuf.asFloatBuffer().put(nchw)
        nchwBuf.rewind()

        return Preprocessed(
            rgbNormalized = rgbBuf,
            rgbImageNetNchw = nchwBuf,
            letterboxed = letterboxed,
            original = original
        )
    }
}
