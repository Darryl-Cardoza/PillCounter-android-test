package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Decoder for the new gloves TFLite model ("Option A" graph).
 *
 * Same architecture and DFL math as the pills model; differences vs pills:
 *  - cls maps have 2 channels (channel 0 = gloves, channel 1 = no_gloves)
 *  - NMS runs PER CLASS so an overlapping gloves+no_gloves pair (e.g. a
 *    half-gloved hand) stays as two detections instead of one merging the other
 *
 * Output contract (per MOBILE_INTEGRATION_GUIDE 03_GLOVES_MODEL.md):
 *   cls_stride{8,16,32}  shape (1, H, W, 2)   already-sigmoided probabilities
 *   reg_stride{8,16,32}  shape (1, H, W, 68)  raw DFL logits = 4 sides × 17 bins
 */
object GloveDetector {

    private const val TAG = "GloveDetector"

    val CLASSES = listOf("gloves", "no_gloves")
    private const val NUM_CLASSES = 2
    private const val DFL_BINS = 17
    private const val SIDES = 4
    private const val REG_CHANNELS = SIDES * DFL_BINS   // 68
    private val FPN_STRIDES = intArrayOf(8, 16, 32)
    private val FPN_GRIDS = intArrayOf(80, 40, 20)

    // Docs' recommended starting thresholds.
    private const val CONF_THRESHOLD = 0.25f
    private const val IOU_THRESHOLD = 0.60f

    /**
     * Pre-allocated output buffers + the TFLite tensor indices each maps to.
     * Allocated once at model-load and reused across frames.
     *
     *   clsBuffers[level]  shape [1, grid, grid, 2]
     *   regBuffers[level]  shape [1, grid, grid, 68]
     */
    class Outputs(
        val clsBuffers: Array<Array<Array<Array<FloatArray>>>>,
        val regBuffers: Array<Array<Array<Array<FloatArray>>>>,
        val clsIdx: IntArray,
        val regIdx: IntArray
    )

    fun allocateOutput(interpreter: Interpreter): Outputs {
        val cls = arrayOfNulls<Array<Array<Array<FloatArray>>>>(FPN_STRIDES.size)
        val reg = arrayOfNulls<Array<Array<Array<FloatArray>>>>(FPN_STRIDES.size)
        val clsIdx = IntArray(FPN_STRIDES.size) { -1 }
        val regIdx = IntArray(FPN_STRIDES.size) { -1 }

        // Match outputs by SHAPE — TFLite reorders auto-generated PartitionedCall:N.
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            if (shape.size != 4 || shape[1] != shape[2]) continue
            val grid = shape[1]
            val levelIdx = FPN_GRIDS.indexOf(grid)
            if (levelIdx < 0) continue

            when (shape[3]) {
                NUM_CLASSES -> {
                    clsIdx[levelIdx] = i
                    cls[levelIdx] = Array(1) { Array(grid) { Array(grid) { FloatArray(NUM_CLASSES) } } }
                }
                REG_CHANNELS -> {
                    regIdx[levelIdx] = i
                    reg[levelIdx] = Array(1) { Array(grid) { Array(grid) { FloatArray(REG_CHANNELS) } } }
                }
            }
        }

        require(clsIdx.none { it < 0 } && regIdx.none { it < 0 }) {
            "Glove model outputs not as expected: " +
                    (0 until interpreter.outputTensorCount).joinToString {
                        "$it=${interpreter.getOutputTensor(it).shape().toList()}"
                    }
        }

        @Suppress("UNCHECKED_CAST")
        return Outputs(
            clsBuffers = cls as Array<Array<Array<Array<FloatArray>>>>,
            regBuffers = reg as Array<Array<Array<Array<FloatArray>>>>,
            clsIdx = clsIdx,
            regIdx = regIdx
        )
    }

    fun detect(
        interpreter: Interpreter,
        inputBuffer: ByteBuffer,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int,
        outputs: Outputs
    ): List<GloveDetection> {
        return try {
            inputBuffer.rewind()
            val outMap = HashMap<Int, Any>(FPN_STRIDES.size * 2)
            for (i in outputs.clsIdx.indices) {
                outMap[outputs.clsIdx[i]] = outputs.clsBuffers[i]
                outMap[outputs.regIdx[i]] = outputs.regBuffers[i]
            }
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outMap)

            // Decode every anchor at every FPN level into per-class candidates.
            // candidates[c] = candidates for class c (gloves=0, no_gloves=1).
            val candidates = Array(NUM_CLASSES) { ArrayList<GloveDetection>(32) }

            for (level in FPN_STRIDES.indices) {
                val stride = FPN_STRIDES[level].toFloat()
                val grid = FPN_GRIDS[level]
                val cls = outputs.clsBuffers[level]
                val reg = outputs.regBuffers[level]

                for (row in 0 until grid) {
                    for (col in 0 until grid) {
                        val scoresPerClass = cls[0][row][col]
                        // Quick reject if NO class clears the threshold.
                        var anyAbove = false
                        for (c in 0 until NUM_CLASSES) {
                            if (scoresPerClass[c] >= CONF_THRESHOLD) { anyAbove = true; break }
                        }
                        if (!anyAbove) continue

                        // DFL decode the 4 sides (shared across classes).
                        val regVec = reg[0][row][col]
                        val dLeft   = dflProject(regVec, 0 * DFL_BINS) * stride
                        val dTop    = dflProject(regVec, 1 * DFL_BINS) * stride
                        val dRight  = dflProject(regVec, 2 * DFL_BINS) * stride
                        val dBottom = dflProject(regVec, 3 * DFL_BINS) * stride

                        val cx = (col + 0.5f) * stride
                        val cy = (row + 0.5f) * stride

                        val x1 = ((cx - dLeft) - scaleInfo.padX) / scaleInfo.scale
                        val y1 = ((cy - dTop) - scaleInfo.padY) / scaleInfo.scale
                        val x2 = ((cx + dRight) - scaleInfo.padX) / scaleInfo.scale
                        val y2 = ((cy + dBottom) - scaleInfo.padY) / scaleInfo.scale

                        val rect = RectF(
                            x1.coerceIn(0f, originalWidth.toFloat()),
                            y1.coerceIn(0f, originalHeight.toFloat()),
                            x2.coerceIn(0f, originalWidth.toFloat()),
                            y2.coerceIn(0f, originalHeight.toFloat())
                        )

                        // One detection per class above threshold.
                        for (c in 0 until NUM_CLASSES) {
                            val s = scoresPerClass[c]
                            if (s < CONF_THRESHOLD) continue
                            candidates[c].add(
                                GloveDetection(
                                    rect = rect,
                                    confidence = s,
                                    classId = c,
                                    className = CLASSES[c]
                                )
                            )
                        }
                    }
                }
            }

            // Per-class NMS — gloves and no_gloves boxes that overlap on the
            // same hand are a legitimate state and should NOT suppress each other.
            val kept = ArrayList<GloveDetection>()
            for (c in 0 until NUM_CLASSES) {
                kept += perClassNms(candidates[c], IOU_THRESHOLD)
            }
            Log.i(TAG, "Glove detections — gloves=${kept.count { it.classId == 0 }} no_gloves=${kept.count { it.classId == 1 }}")
            kept

        } catch (e: Exception) {
            Log.e(TAG, "Glove inference failed", e)
            emptyList()
        }
    }

    /** Greedy NMS within a single class. */
    private fun perClassNms(
        detections: List<GloveDetection>,
        iouThreshold: Float
    ): List<GloveDetection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = ArrayList<GloveDetection>()
        for (det in sorted) {
            if (kept.none { iou(det.rect, it.rect) > iouThreshold }) {
                kept.add(det)
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interL = max(a.left, b.left)
        val interT = max(a.top, b.top)
        val interR = min(a.right, b.right)
        val interB = min(a.bottom, b.bottom)
        val iw = interR - interL
        val ih = interB - interT
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    /**
     * Numerically-stable softmax over a 17-bin slice then dot product with
     * [0, 1, ..., 16]. Returns the expected value in stride units (caller
     * multiplies by the FPN stride to get pixels).
     */
    private fun dflProject(regVec: FloatArray, offset: Int): Float {
        var maxLogit = regVec[offset]
        for (i in 1 until DFL_BINS) {
            val v = regVec[offset + i]
            if (v > maxLogit) maxLogit = v
        }
        var sumExp = 0f
        var weightedSum = 0f
        for (i in 0 until DFL_BINS) {
            val e = exp(regVec[offset + i] - maxLogit)
            sumExp += e
            weightedSum += e * i
        }
        return weightedSum / sumExp
    }
}
