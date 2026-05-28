package com.rite.pillcounting.feature.dispenseFlow.presentation.logic

import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Runs the YOLOv11 glove-detection model (best_float32.tflite) on a
 * pre-processed 640×640 float32 input buffer.
 *
 * The model has 2 classes:
 *   - 0  →  "gloves"
 *   - 1  →  "no_gloves"
 *
 * Output layout: [1, 6, N]  (channel-first, 4 box coords + 2 class scores)
 * — identical to YOLOv8/11n float32 exports.
 *
 * This is a direct Kotlin port of detect_live.py: postprocess() + nms().
 */
object GloveDetector {

    private const val TAG = "GloveDetector"

    const val CLASS_GLOVES = "gloves"
    const val CLASS_NO_GLOVES = "no_gloves"

    val CLASSES = listOf(CLASS_GLOVES, CLASS_NO_GLOVES)
    private const val INPUT_SIZE = 640
    // 0.35 was way too permissive — produced ~9 false-positive "gloves" boxes on
    // empty frames at 36–52% conf, which permanently locked glove detection off.
    private const val CONF_THRESHOLD = 0.65f
    private const val IOU_THRESHOLD  = 0.45f

    /**
     * Run glove inference and return a list of detections in original-image
     * pixel coordinates.
     *
     * @param interpreter  The loaded glove TFLite interpreter.
     * @param inputBuffer  A rewound 640×640×3 float32 ByteBuffer
     *                     (shared with pill/tray preprocessing — duplicate before use).
     * @param scaleInfo    Letterbox scale + padding produced by [Letterbox].
     * @param originalWidth   Width  of the camera frame before letterboxing.
     * @param originalHeight  Height of the camera frame before letterboxing.
     */
    /** Allocates the glove model's [1, 6, N] output buffer (re-usable across frames). */
    fun allocateOutput(interpreter: Interpreter): Array<Array<FloatArray>> {
        val shape = interpreter.getOutputTensor(0).shape()
        return Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
    }

    fun detect(
        interpreter: Interpreter,
        inputBuffer: ByteBuffer,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int,
        rawOutput: Array<Array<FloatArray>>
    ): List<GloveDetection> {

        return try {
            val channels = rawOutput[0].size
            val numAnchors = rawOutput[0][0].size

            // Rewind so the interpreter reads from the start
            inputBuffer.rewind()
            interpreter.run(inputBuffer, rawOutput)

            // ── 2. Decode [1, 6, N] → [N, 6] ─────────────────────────────────
            val nc = CLASSES.size            // 2
            val expected = 4 + nc            // 6

            if (channels != expected) {
                Log.w(TAG, "Unexpected output channels: $channels (expected $expected)")
                return emptyList()
            }

            val raw = rawOutput[0]  // [6][N]

            // ── 3. Filter by confidence, decode xywh → xyxy ───────────────────
            val confThresh = CONF_THRESHOLD
            val candidates = mutableListOf<GloveDetection>()

            for (i in 0 until numAnchors) {
                // Class scores: raw[4][i] = gloves, raw[5][i] = no_gloves
                var bestScore = -1f
                var bestClass = 0
                for (c in 0 until nc) {
                    val s = raw[4 + c][i]
                    if (s > bestScore) {
                        bestScore = s
                        bestClass = c
                    }
                }

                if (bestScore < confThresh) continue

                // Box is in 640-space (absolute pixels inside the letterboxed image)
                val cx = raw[0][i]
                val cy = raw[1][i]
                val bw = raw[2][i]
                val bh = raw[3][i]

                // Reverse letterbox → original frame pixels
                val x1 = ((cx - bw / 2f) - scaleInfo.padX) / scaleInfo.scale
                val y1 = ((cy - bh / 2f) - scaleInfo.padY) / scaleInfo.scale
                val x2 = ((cx + bw / 2f) - scaleInfo.padX) / scaleInfo.scale
                val y2 = ((cy + bh / 2f) - scaleInfo.padY) / scaleInfo.scale

                val rect = RectF(
                    x1.coerceIn(0f, originalWidth.toFloat()),
                    y1.coerceIn(0f, originalHeight.toFloat()),
                    x2.coerceIn(0f, originalWidth.toFloat()),
                    y2.coerceIn(0f, originalHeight.toFloat())
                )

                candidates.add(
                    GloveDetection(
                        rect       = rect,
                        confidence = bestScore,
                        classId    = bestClass,
                        className  = CLASSES[bestClass]
                    )
                )
            }

            val finalDetections = nms(candidates, IOU_THRESHOLD)
            Log.i(TAG, "Glove Detection Result: ${finalDetections.size} boxes kept after NMS")
            finalDetections

        } catch (e: Exception) {
            Log.e(TAG, "Glove inference failed", e)
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Non-Maximum Suppression
    // ─────────────────────────────────────────────────────────────────────────

    private fun nms(
        detections: List<GloveDetection>,
        iouThreshold: Float
    ): List<GloveDetection> {
        val results = mutableListOf<GloveDetection>()

        // Run NMS per class so gloves and no_gloves boxes don't suppress each other
        for (classId in CLASSES.indices) {
            val forClass = detections
                .filter { it.classId == classId }
                .sortedByDescending { it.confidence }

            val kept = mutableListOf<GloveDetection>()
            for (det in forClass) {
                if (kept.none { iou(det.rect, it.rect) > iouThreshold }) {
                    kept.add(det)
                }
            }
            results.addAll(kept)
        }

        return results
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interL = max(a.left, b.left)
        val interT = max(a.top, b.top)
        val interR = min(a.right, b.right)
        val interB = min(a.bottom, b.bottom)

        val interW = interR - interL
        val interH = interB - interT
        if (interW <= 0f || interH <= 0f) return 0f

        val interArea = interW * interH
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}