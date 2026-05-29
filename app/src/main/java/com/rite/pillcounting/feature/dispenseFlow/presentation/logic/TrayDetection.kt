package com.rite.pillcounting.feature.dispenseFlow.presentation.logic

import android.graphics.RectF
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * One detected tray.
 *
 * @param protoMask    The instance mask in proto resolution (160×160 floats, sigmoid-ed
 *                     and cropped to the box). Index as `protoMask[y][x]`. Avoiding
 *                     a per-pixel original-resolution mask is the main perf win —
 *                     `containsPoint` translates original-frame coords back to proto
 *                     coords on each query rather than upsampling the whole mask.
 * @param rect         Bounding box in original camera-frame pixel coords (for UI).
 * @param confidence   Tray-class confidence in [0, 1].
 * @param scaleInfo    Letterbox parameters used to translate orig-frame coords to
 *                     proto coords inside [containsPoint].
 */
data class TrayDetection(
    val protoMask: Array<FloatArray>,
    val rect: RectF,
    val confidence: Float,
    val scaleInfo: Letterbox.ScaleInfo,
    val maskThreshold: Float,
    val trayColor: TrayColor = TrayColor.UNKNOWN
) {
    fun containsPoint(x: Int, y: Int): Boolean {
        if (protoMask.isEmpty()) return false

        // orig pixel → 640 letterbox space → proto (160) space
        val x640 = x * scaleInfo.scale + scaleInfo.padX
        val y640 = y * scaleInfo.scale + scaleInfo.padY

        val protoH = protoMask.size
        val protoW = protoMask[0].size

        val xp = (x640 / scaleInfo.inputSize * protoW).toInt()
        val yp = (y640 / scaleInfo.inputSize * protoH).toInt()

        if (xp < 0 || xp >= protoW || yp < 0 || yp >= protoH) return false

        return protoMask[yp][xp] > maskThreshold
    }

    fun getMaskStats(): String {
        if (protoMask.isEmpty()) return "Empty mask"
        var trueCount = 0
        var totalCount = 0
        for (row in protoMask) {
            for (value in row) {
                if (value > maskThreshold) trueCount++
                totalCount++
            }
        }
        val pct = if (totalCount > 0) trueCount * 100 / totalCount else 0
        return "ProtoMask: ${protoMask.size}×${protoMask[0].size} = $totalCount cells, $trueCount > thr ($pct%)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrayDetection) return false
        return confidence == other.confidence && rect == other.rect
    }

    override fun hashCode(): Int = 31 * rect.hashCode() + confidence.hashCode()
}

object TrayDetector {

    private const val INPUT_SIZE = 640

    private const val CONF_THRESHOLD = 0.50f
    private const val MASK_THRESHOLD = 0.30f
    private const val NMS_IOU_THRESHOLD = 0.45f
    private const val MAX_DETECTIONS = 5  // Safety limit: max 5 trays per frame

    private const val NUM_COEFFS = 32
    private const val NUM_ANCHORS = 8400
    private const val PROTO_H = 160
    private const val PROTO_W = 160
    private const val TRAY_CLASS_ROW = 5

    private val logger = AppLogger("TrayDetector")

    private data class RawBox(
        val conf: Float,
        val cx: Float,
        val cy: Float,
        val bw: Float,
        val bh: Float,
        val coeffs: FloatArray
    )

    /**
     * Allocates the giant tray output buffers once. ~1MB of floats per frame is
     * otherwise allocated + zeroed + GC'd; reusing these buffers measurably cuts
     * postprocess time on weak devices.
     */
    fun allocateDetOutput(): Array<Array<FloatArray>> =
        Array(1) { Array(38) { FloatArray(NUM_ANCHORS) } }

    fun allocateProtoOutput(): Array<Array<Array<FloatArray>>> =
        Array(1) { Array(PROTO_H) { Array(PROTO_W) { FloatArray(NUM_COEFFS) } } }

    fun detect(
        interpreter: Interpreter,
        inputBuffer: ByteBuffer,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int,
        detOutput: Array<Array<FloatArray>>,
        protoOutput: Array<Array<Array<FloatArray>>>
    ): List<TrayDetection> {

        // Rewind so the buffer can be re-read even if the pill model used it first.
        inputBuffer.rewind()

        val outputs = mapOf(
            0 to detOutput as Any,
            1 to protoOutput as Any
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        // Diagnostic logging disabled by default to reduce memory pressure
        // Uncomment for debugging: logDiagnostics(detOutput)

        val rawBoxes = mutableListOf<RawBox>()

        for (a in 0 until NUM_ANCHORS) {
            val conf = detOutput[0][TRAY_CLASS_ROW][a]
            if (conf < CONF_THRESHOLD) continue

            // IMPORTANT:
            // Logs confirm these coords are normalized [0..1], so scale to 640-space.
            val cx = detOutput[0][0][a] * INPUT_SIZE
            val cy = detOutput[0][1][a] * INPUT_SIZE
            val bw = detOutput[0][2][a] * INPUT_SIZE
            val bh = detOutput[0][3][a] * INPUT_SIZE

            if (bw <= 0f || bh <= 0f) continue

            rawBoxes.add(
                RawBox(
                    conf = conf,
                    cx = cx,
                    cy = cy,
                    bw = bw,
                    bh = bh,
                    coeffs = FloatArray(NUM_COEFFS) { i ->
                        detOutput[0][6 + i][a]
                    }
                )
            )
        }

        logger.i("Anchors above tray threshold: ${rawBoxes.size}")

        if (rawBoxes.isEmpty()) return emptyList()

        val kept = nms(rawBoxes, NMS_IOU_THRESHOLD)
        logger.i("After NMS: ${kept.size} unique tray(s)")

        // Safety limit: prevent memory exhaustion from too many detections
        val safeKept = kept.take(MAX_DETECTIONS)
        if (kept.size > MAX_DETECTIONS) {
            logger.w("⚠️ Limiting trays from ${kept.size} to $MAX_DETECTIONS to prevent memory exhaustion")
        }

        val detections = mutableListOf<TrayDetection>()

        for ((index, box) in safeKept.withIndex()) {
            // Build the box's instance mask at proto resolution only. Translating
            // orig-frame coords back to proto inside containsPoint is ~10 ops per query
            // and avoids two ~600k-iteration loops (upsample + reverse-letterbox) per
            // frame.
            val rawMask = buildRawMask(protoOutput[0], box.coeffs)
            val croppedMask = cropMaskToBox(rawMask, box)

            // Bounding rect derived in proto space, then mapped back to original.
            val maskRect = maskBoundingRectProto(croppedMask, scaleInfo, originalWidth, originalHeight)
            val boxRect = reverseLetterboxBox(box, scaleInfo, originalWidth, originalHeight)

            val finalRect = maskRect ?: boxRect

            if (finalRect.width() <= 0f || finalRect.height() <= 0f) {
                continue
            }

            if (index < 3) {
                logger.i("Tray[$index] conf=${box.conf} rect=$finalRect")
            }

            detections.add(
                TrayDetection(
                    protoMask = croppedMask,
                    rect = finalRect,
                    confidence = box.conf,
                    scaleInfo = scaleInfo,
                    maskThreshold = MASK_THRESHOLD
                )
            )
        }

        logger.i("TrayDetector final: ${detections.size} tray(s) detected")
        return detections
    }

    private fun logDiagnostics(detOutput: Array<Array<FloatArray>>) {
        var maxCls0 = 0f
        var maxCls1 = 0f

        for (a in 0 until NUM_ANCHORS) {
            if (detOutput[0][4][a] > maxCls0) maxCls0 = detOutput[0][4][a]
            if (detOutput[0][TRAY_CLASS_ROW][a] > maxCls1) maxCls1 = detOutput[0][TRAY_CLASS_ROW][a]
        }

        logger.i("Max scores -> cls0=$maxCls0 cls1(tray)=$maxCls1 threshold=$CONF_THRESHOLD")

        for (a in 0 until min(10, NUM_ANCHORS)) {
            logger.i(
                "sample[$a] " +
                        "cx=${detOutput[0][0][a]} " +
                        "cy=${detOutput[0][1][a]} " +
                        "w=${detOutput[0][2][a]} " +
                        "h=${detOutput[0][3][a]} " +
                        "cls0=${detOutput[0][4][a]} " +
                        "cls1=${detOutput[0][TRAY_CLASS_ROW][a]}"
            )
        }
    }

    private fun buildRawMask(
        proto: Array<Array<FloatArray>>,
        coeffs: FloatArray
    ): Array<FloatArray> {
        return Array(PROTO_H) { y ->
            FloatArray(PROTO_W) { x ->
                sigmoid(dot(proto[y][x], coeffs))
            }
        }
    }

    private fun cropMaskToBox(
        rawMask: Array<FloatArray>,
        box: RawBox
    ): Array<FloatArray> {
        val result = Array(PROTO_H) { y -> rawMask[y].clone() }

        val x1p = (((box.cx - box.bw / 2f) / INPUT_SIZE) * PROTO_W).toInt().coerceIn(0, PROTO_W - 1)
        val y1p = (((box.cy - box.bh / 2f) / INPUT_SIZE) * PROTO_H).toInt().coerceIn(0, PROTO_H - 1)
        val x2p = (((box.cx + box.bw / 2f) / INPUT_SIZE) * PROTO_W).toInt().coerceIn(0, PROTO_W - 1)
        val y2p = (((box.cy + box.bh / 2f) / INPUT_SIZE) * PROTO_H).toInt().coerceIn(0, PROTO_H - 1)

        for (y in 0 until PROTO_H) {
            for (x in 0 until PROTO_W) {
                if (x < x1p || x > x2p || y < y1p || y > y2p) {
                    result[y][x] = 0f
                }
            }
        }

        return result
    }

    private fun nms(boxes: List<RawBox>, iouThreshold: Float): List<RawBox> {
        val sorted = boxes.sortedByDescending { it.conf }
        val keep = mutableListOf<RawBox>()

        for (box in sorted) {
            val suppress = keep.any { kept ->
                iouBoxes(box, kept) > iouThreshold
            }
            if (!suppress) keep.add(box)
        }

        return keep
    }

    private fun iouBoxes(a: RawBox, b: RawBox): Float {
        val ax1 = a.cx - a.bw / 2f
        val ay1 = a.cy - a.bh / 2f
        val ax2 = a.cx + a.bw / 2f
        val ay2 = a.cy + a.bh / 2f

        val bx1 = b.cx - b.bw / 2f
        val by1 = b.cy - b.bh / 2f
        val bx2 = b.cx + b.bw / 2f
        val by2 = b.cy + b.bh / 2f

        val iw = min(ax2, bx2) - max(ax1, bx1)
        val ih = min(ay2, by2) - max(ay1, by1)
        if (iw <= 0f || ih <= 0f) return 0f

        val inter = iw * ih
        val aArea = a.bw * a.bh
        val bArea = b.bw * b.bh

        return inter / (aArea + bArea - inter)
    }

    private fun reverseLetterboxBox(
        box: RawBox,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int
    ): RectF {
        val x1 = ((box.cx - box.bw / 2f) - scaleInfo.padX) / scaleInfo.scale
        val y1 = ((box.cy - box.bh / 2f) - scaleInfo.padY) / scaleInfo.scale
        val x2 = ((box.cx + box.bw / 2f) - scaleInfo.padX) / scaleInfo.scale
        val y2 = ((box.cy + box.bh / 2f) - scaleInfo.padY) / scaleInfo.scale

        return RectF(
            x1.coerceIn(0f, originalWidth.toFloat()),
            y1.coerceIn(0f, originalHeight.toFloat()),
            x2.coerceIn(0f, originalWidth.toFloat()),
            y2.coerceIn(0f, originalHeight.toFloat())
        )
    }

    /**
     * Computes the mask's bounding rect in proto coords, then maps back to the
     * original frame. Iterates 160×160 = 25,600 cells instead of 720×960 ≈ 700k.
     */
    private fun maskBoundingRectProto(
        protoMask: Array<FloatArray>,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int
    ): RectF? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        for (y in protoMask.indices) {
            val row = protoMask[y]
            for (x in row.indices) {
                if (row[x] > MASK_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (minX > maxX || minY > maxY) return null

        // proto → 640 → original
        val protoW = if (protoMask.isNotEmpty()) protoMask[0].size else PROTO_W
        val protoH = protoMask.size
        val scaleX = scaleInfo.inputSize.toFloat() / protoW
        val scaleY = scaleInfo.inputSize.toFloat() / protoH

        val x1_640 = minX * scaleX
        val y1_640 = minY * scaleY
        val x2_640 = (maxX + 1) * scaleX
        val y2_640 = (maxY + 1) * scaleY

        val x1 = (x1_640 - scaleInfo.padX) / scaleInfo.scale
        val y1 = (y1_640 - scaleInfo.padY) / scaleInfo.scale
        val x2 = (x2_640 - scaleInfo.padX) / scaleInfo.scale
        val y2 = (y2_640 - scaleInfo.padY) / scaleInfo.scale

        return RectF(
            x1.coerceIn(0f, originalWidth.toFloat()),
            y1.coerceIn(0f, originalHeight.toFloat()),
            x2.coerceIn(0f, originalWidth.toFloat()),
            y2.coerceIn(0f, originalHeight.toFloat())
        )
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

}