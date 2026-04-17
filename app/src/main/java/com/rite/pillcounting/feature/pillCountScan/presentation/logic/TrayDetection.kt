package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import android.graphics.RectF
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class TrayDetection(
    val mask: Array<BooleanArray>,
    val rect: RectF,
    val confidence: Float
) {
    fun containsPoint(x: Int, y: Int): Boolean {
        if (y < 0 || y >= mask.size) return false
        if (mask.isEmpty()) return false
        if (x < 0 || x >= mask[0].size) return false
        return mask[y][x]
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

    fun detect(
        interpreter: Interpreter,
        inputBuffer: ByteBuffer,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int
    ): List<TrayDetection> {

        // Rewind so the buffer can be re-read even if the pill model used it first.
        inputBuffer.rewind()

        val detOutput = Array(1) { Array(38) { FloatArray(NUM_ANCHORS) } }
        val protoOutput = Array(1) {
            Array(PROTO_H) {
                Array(PROTO_W) {
                    FloatArray(NUM_COEFFS)
                }
            }
        }

        val outputs = mapOf(
            0 to detOutput as Any,
            1 to protoOutput as Any
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        logDiagnostics(detOutput)

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

        val detections = mutableListOf<TrayDetection>()

        for ((index, box) in kept.withIndex()) {
            val rawMask = buildRawMask(protoOutput[0], box.coeffs)
            val croppedMask = cropMaskToBox(rawMask, box)

            val mask640 = upsampleNearest(
                src = croppedMask,
                srcH = PROTO_H,
                srcW = PROTO_W,
                dstH = INPUT_SIZE,
                dstW = INPUT_SIZE
            )

            val originalMask = reverseLetterboxMask(
                mask640 = mask640,
                scaleInfo = scaleInfo,
                originalWidth = originalWidth,
                originalHeight = originalHeight
            )

            val maskRect = maskBoundingRect(originalMask, originalWidth, originalHeight)
            val boxRect = reverseLetterboxBox(box, scaleInfo, originalWidth, originalHeight)

            val finalRect = maskRect ?: boxRect

            if (finalRect.width() <= 0f || finalRect.height() <= 0f) {
                logger.i("Skipping tray[$index]: invalid rect | maskRect=$maskRect boxRect=$boxRect")
                continue
            }

            logger.i("Tray[$index] conf=${box.conf} maskRect=$maskRect boxRect=$boxRect finalRect=$finalRect")

            detections.add(
                TrayDetection(
                    mask = originalMask,
                    rect = finalRect,
                    confidence = box.conf
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
            if (detOutput[0][5][a] > maxCls1) maxCls1 = detOutput[0][5][a]
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
                        "cls1=${detOutput[0][5][a]}"
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

    private fun upsampleNearest(
        src: Array<FloatArray>,
        srcH: Int,
        srcW: Int,
        dstH: Int,
        dstW: Int
    ): Array<FloatArray> {
        val dst = Array(dstH) { FloatArray(dstW) }

        for (y in 0 until dstH) {
            val sy = (y * srcH / dstH).coerceIn(0, srcH - 1)
            for (x in 0 until dstW) {
                val sx = (x * srcW / dstW).coerceIn(0, srcW - 1)
                dst[y][x] = src[sy][sx]
            }
        }

        return dst
    }

    private fun reverseLetterboxMask(
        mask640: Array<FloatArray>,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int
    ): Array<BooleanArray> {
        val result = Array(originalHeight) { BooleanArray(originalWidth) }

        for (origY in 0 until originalHeight) {
            for (origX in 0 until originalWidth) {
                val lx = (origX * scaleInfo.scale + scaleInfo.padX).toInt().coerceIn(0, INPUT_SIZE - 1)
                val ly = (origY * scaleInfo.scale + scaleInfo.padY).toInt().coerceIn(0, INPUT_SIZE - 1)
                result[origY][origX] = mask640[ly][lx] > MASK_THRESHOLD
            }
        }

        return result
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

    private fun maskBoundingRect(
        mask: Array<BooleanArray>,
        width: Int,
        height: Int
    ): RectF? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y][x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        return if (minX > maxX || minY > maxY) {
            null
        } else {
            RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
        }
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

}