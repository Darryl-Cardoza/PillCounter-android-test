package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import kotlin.math.max
import kotlin.math.min

/**
 * TFLite wrapper for the MobileNetV2-UNet tray semantic segmentation model
 * (`tray_seg_mbv2_unet_384_float16.tflite`).
 *
 * Replaces the legacy RTMDet-Ins instance-segmentation tray detector. The
 * graph is locked to TFLite GPU (Mali OpenCL on the A14 target); see the
 * loader's GPU-only init code path.
 *
 * Model contract:
 *   - Input:  `images`, NHWC float32 [1, 384, 384, 3], **raw RGB in [0, 255]**.
 *             ImageNet normalization is baked into the graph; the caller does
 *             NOT subtract mean or divide by std.
 *   - Output: NHWC float32 [1, 384, 384, 3], raw per-pixel class logits.
 *             Channel ordering: 0 = background, 1 = chute, 2 = tray.
 *
 * The detector owns the 640 → 384 downscale internally so the per-frame
 * pipeline doesn't need a separate 384 preprocessing path. We take the
 * existing 640-letterboxed Bitmap (produced once per frame by
 * [ImagePreprocessor]/[Letterbox]) and scale-into a cached 384 Bitmap via
 * Canvas + Matrix. ~1 ms on A14, all hardware-blitted.
 *
 * NOT thread-safe — call [detect] on a single coroutine at a time, same
 * contract as the legacy detector.
 */
class TraySegmentationDetector(
    private val interpreter: Interpreter
) {
    private val logger = AppLogger(TAG)

    // 384 input scratch — one Bitmap + one Canvas, both reused across frames.
    // Allocating these per-frame was ~590 KB of Bitmap churn each frame in
    // the legacy code's prototype path; cached, it's zero GC.
    private val inputBitmap: Bitmap =
        Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas: Canvas = Canvas(inputBitmap)
    private val resizeMatrix = Matrix()
    private val resizePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // Int pixel scratch for the 384 frame.
    private val pixelScratch = IntArray(INPUT_SIZE * INPUT_SIZE)

    // NHWC float32 input buffer fed to the TFLite Interpreter.
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())

    // Output tensor scratch: (1, 384, 384, 3) float32 — TFLite hands it back
    // as a 4D float array.
    private val outputScratch: Array<Array<Array<FloatArray>>> =
        Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(NUM_CLASSES) } } }

    // Per-class mask scratch. Cloned on output so callers can hold references.
    private val chuteMaskScratch = BitSet(INPUT_SIZE * INPUT_SIZE)
    private val trayMaskScratch = BitSet(INPUT_SIZE * INPUT_SIZE)

    /**
     * Run inference on a frame and decode to [TrayDetection]s.
     *
     * @param letterboxedBitmap  The 640-letterboxed RGB frame produced by
     *                           [ImagePreprocessor] (640x640 ARGB_8888).
     *                           This detector downsamples it to 384x384
     *                           internally before the TFLite call.
     * @param scaleInfo640       The 640-space [Letterbox.ScaleInfo] from the
     *                           main preprocessor. We derive the 384-space
     *                           equivalent internally for mask lookups.
     * @param originalWidth, originalHeight  Original camera frame dimensions.
     */
    fun detect(
        letterboxedBitmap: Bitmap,
        scaleInfo640: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int
    ): List<TrayDetection> {
        return try {
            preprocessTo384(letterboxedBitmap)
            interpreter.run(inputBuffer, outputScratch)

            // Derive 384-space scaleInfo by composing the 640 scaleInfo with
            // the 640->384 downscale. Mathematically:
            //   x_384 = (x_orig * scale_640 + padX_640) * (384/640)
            //         = x_orig * (scale_640 * 384/640) + padX_640 * 384/640
            // So the 384 scaleInfo's scale/padX/padY are the 640 values * (384/640).
            val k = INPUT_SIZE.toFloat() / scaleInfo640.inputSize.toFloat()
            val scaleInfo384 = Letterbox.ScaleInfo(
                scale = scaleInfo640.scale * k,
                padX = scaleInfo640.padX * k,
                padY = scaleInfo640.padY * k,
                inputSize = INPUT_SIZE
            )

            decodeOutputs(scaleInfo384, originalWidth, originalHeight)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite tray inference failed", e)
            emptyList()
        }
    }

    /**
     * Scale the 640 letterbox to our 384 input Bitmap, read its pixels, and
     * write NHWC float32 raw [0, 255] into [inputBuffer].
     */
    private fun preprocessTo384(letterboxedBitmap: Bitmap) {
        // 640 -> 384 scale via Canvas. FILTER_BITMAP_FLAG enables bilinear.
        val k = INPUT_SIZE.toFloat() / letterboxedBitmap.width.toFloat()
        resizeMatrix.reset()
        resizeMatrix.postScale(k, k)
        inputCanvas.drawColor(0)
        inputCanvas.drawBitmap(letterboxedBitmap, resizeMatrix, resizePaint)

        // Read the resized 384 frame's pixels in one batched call.
        inputBitmap.getPixels(pixelScratch, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // Write NHWC float32 raw [0, 255]. The model bakes in ImageNet
        // normalization internally, so we just hand it pixel values.
        inputBuffer.clear()
        val floatView = inputBuffer.asFloatBuffer()
        var i = 0
        while (i < pixelScratch.size) {
            val p = pixelScratch[i]
            floatView.put(((p shr 16) and 0xFF).toFloat())   // R
            floatView.put(((p shr 8) and 0xFF).toFloat())    // G
            floatView.put((p and 0xFF).toFloat())            // B
            i++
        }
        inputBuffer.rewind()
    }

    /**
     * Argmax over the class dim, then collect bboxes + per-class BitSet masks.
     */
    private fun decodeOutputs(
        scaleInfo384: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int
    ): List<TrayDetection> {
        chuteMaskScratch.clear()
        trayMaskScratch.clear()

        val plane = outputScratch[0]   // [384][384][3]

        var chuteMinX = INPUT_SIZE; var chuteMinY = INPUT_SIZE
        var chuteMaxX = -1; var chuteMaxY = -1
        var trayMinX = INPUT_SIZE; var trayMinY = INPUT_SIZE
        var trayMaxX = -1; var trayMaxY = -1

        var chutePixelCount = 0
        var trayPixelCount = 0

        for (y in 0 until INPUT_SIZE) {
            val row = plane[y]
            val rowBase = y * INPUT_SIZE
            for (x in 0 until INPUT_SIZE) {
                val logits = row[x]
                val bg = logits[CLASS_BG]
                val ch = logits[CLASS_CHUTE]
                val tr = logits[CLASS_TRAY]
                // Pick the winning foreground class (or background) by argmax.
                val cls = when {
                    bg >= ch && bg >= tr -> CLASS_BG
                    ch >= tr -> CLASS_CHUTE
                    else -> CLASS_TRAY
                }
                if (cls == CLASS_BG) continue

                // Confidence gate: the foreground class's logit must beat
                // background by at least FG_LOGIT_MARGIN. This rejects
                // low-confidence "the model is barely sure" pixels where the
                // foreground and background logits are nearly tied. Roughly
                // equivalent to requiring softmax(fg) > 0.73 at margin=1.0,
                // but cheaper (no exp() in the inner loop).
                val fgLogit = if (cls == CLASS_CHUTE) ch else tr
                if (fgLogit - bg < FG_LOGIT_MARGIN) continue

                val idx = rowBase + x
                if (cls == CLASS_CHUTE) {
                    chuteMaskScratch.set(idx)
                    if (x < chuteMinX) chuteMinX = x
                    if (x > chuteMaxX) chuteMaxX = x
                    if (y < chuteMinY) chuteMinY = y
                    if (y > chuteMaxY) chuteMaxY = y
                    chutePixelCount++
                } else {
                    trayMaskScratch.set(idx)
                    if (x < trayMinX) trayMinX = x
                    if (x > trayMaxX) trayMaxX = x
                    if (y < trayMinY) trayMinY = y
                    if (y > trayMaxY) trayMaxY = y
                    trayPixelCount++
                }
            }
        }

        val out = ArrayList<TrayDetection>(2)
        if (trayPixelCount > MIN_CLASS_PIXELS) {
            out.add(
                buildDetection(
                    cls = TrayClass.TRAY,
                    mask = trayMaskScratch.clone() as BitSet,
                    minX = trayMinX, minY = trayMinY,
                    maxX = trayMaxX, maxY = trayMaxY,
                    scaleInfo = scaleInfo384,
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                )
            )
        }
        if (chutePixelCount > MIN_CLASS_PIXELS) {
            out.add(
                buildDetection(
                    cls = TrayClass.CHUTE,
                    mask = chuteMaskScratch.clone() as BitSet,
                    minX = chuteMinX, minY = chuteMinY,
                    maxX = chuteMaxX, maxY = chuteMaxY,
                    scaleInfo = scaleInfo384,
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                )
            )
        }

        logger.i(
            "TraySeg: tray=${if (trayPixelCount > MIN_CLASS_PIXELS) "yes" else "no"} (${trayPixelCount}px), " +
                "chute=${if (chutePixelCount > MIN_CLASS_PIXELS) "yes" else "no"} (${chutePixelCount}px)"
        )
        return out
    }

    private fun buildDetection(
        cls: TrayClass,
        mask: BitSet,
        minX: Int, minY: Int, maxX: Int, maxY: Int,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int,
    ): TrayDetection {
        // Convert 384-space bbox back to original-image coords.
        val x1 = (minX.toFloat() - scaleInfo.padX) / scaleInfo.scale
        val y1 = (minY.toFloat() - scaleInfo.padY) / scaleInfo.scale
        val x2 = ((maxX + 1).toFloat() - scaleInfo.padX) / scaleInfo.scale
        val y2 = ((maxY + 1).toFloat() - scaleInfo.padY) / scaleInfo.scale
        val rect = RectF(
            max(0f, min(x1, originalWidth.toFloat())),
            max(0f, min(y1, originalHeight.toFloat())),
            max(0f, min(x2, originalWidth.toFloat())),
            max(0f, min(y2, originalHeight.toFloat()))
        )

        return TrayDetection(
            rect = rect,
            confidence = 1.0f,
            cls = cls,
            mask = mask,
            maskSize = INPUT_SIZE,
            scaleInfo = scaleInfo
        )
    }

    fun close() {
        try { interpreter.close() } catch (e: Exception) {
            Log.w(TAG, "TFLite tray Interpreter close failed: ${e.message}")
        }
        try { inputBitmap.recycle() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "TraySegDetector"

        /** Model input/output spatial resolution. Must match the exported .tflite. */
        const val INPUT_SIZE = 384

        /** Output channels = 3 semantic classes (matches `num_classes` in the YAML config). */
        const val NUM_CLASSES = 3
        const val NUM_CHANNELS = 3
        const val BYTES_PER_FLOAT = 4

        // Channel indices in the model output. Keep in sync with
        // `polygons_to_masks.py` and `seg_mobilenetv2_unet.yaml`.
        const val CLASS_BG = 0
        const val CLASS_CHUTE = 1
        const val CLASS_TRAY = 2

        /**
         * Minimum pixel count for a class to be reported as a detection.
         * 800 pixels at 384x384 is ~0.54% of the frame — well below any real
         * tray or chute (typically 5k-20k px in the on-device logs) and
         * large enough to reject borderline noise blobs that would otherwise
         * gate pills incorrectly. Bump higher to be stricter; never below
         * ~300 or you'll start rejecting real chutes seen at oblique angles.
         */
        const val MIN_CLASS_PIXELS = 800

        /**
         * Confidence margin between the winning foreground class's logit and
         * the background logit, in logit units. A pixel is only assigned to
         * a foreground class if `fg_logit - bg_logit >= FG_LOGIT_MARGIN`.
         *
         * - 0.0 = argmax-only (no extra confidence requirement)
         * - 1.0 = roughly equivalent to requiring softmax(fg) > 0.73
         * - 2.0 = roughly equivalent to requiring softmax(fg) > 0.88
         *
         * Raise this if you see false-positive tray pixels in real frames;
         * lower it if the model misses real chute boundaries.
         */
        const val FG_LOGIT_MARGIN = 2.0f
    }
}
