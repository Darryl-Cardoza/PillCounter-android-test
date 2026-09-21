package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns camera frames into model inputs. Two steps, because the pill model's
 * input depends on the tray result:
 *
 *  - [prepareFrame]  ImageProxy → [Frame]: the original Bitmap plus the 640×640
 *                    letterbox of the whole frame. The letterbox goes to the
 *                    tray segmentation detector and the glove detector, which
 *                    do their own resize + [0, 255] rescale internally.
 *  - [pillInput]     any 640×640 Bitmap → NHWC RGB / 255 float32 direct buffer
 *                    for the pill model. PillAnalyzer hands it the tray crop
 *                    when a tray is in view, else the full-frame letterbox.
 *
 * **All scratch is reused across frames.** Each frame writes into the same
 * FloatArray and direct ByteBuffer (~10 MB total). Every inference on a frame
 * completes before `analyze()` returns, so the next frame cannot start until
 * the current frame's reads are done.
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

    data class Frame(
        val original: Bitmap,
        val letterboxed: Bitmap
    )

    /** Decode the frame and letterbox the whole of it to 640×640. */
    fun prepareFrame(image: ImageProxy): Frame {
        val original = image.toBitmap()
        return Frame(original = original, letterboxed = Letterbox.preprocess(original, INPUT_SIZE))
    }

    /**
     * Write [letterboxed] (640×640) into the pill model's NHWC RGB / 255 float
     * buffer and return it, rewound. The buffer is shared scratch — consume it
     * before the next call.
     */
    fun pillInput(letterboxed: Bitmap): ByteBuffer {
        require(letterboxed.width == INPUT_SIZE && letterboxed.height == INPUT_SIZE) {
            "pill input must be ${INPUT_SIZE}x$INPUT_SIZE, got ${letterboxed.width}x${letterboxed.height}"
        }
        letterboxed.getPixels(pixelScratch, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val rgb = rgbScratch

        var i = 0
        var j = 0
        while (i < NUM_PIXELS) {
            val p = pixelScratch[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()

            // NHWC RGB / 255 — what the pill TFLite model expects.
            rgb[j] = r / 255f
            rgb[j + 1] = g / 255f
            rgb[j + 2] = b / 255f

            i++
            j += CHANNELS
        }

        rgbBuf.clear()
        rgbBuf.asFloatBuffer().put(rgb)
        rgbBuf.rewind()
        return rgbBuf
    }
}
