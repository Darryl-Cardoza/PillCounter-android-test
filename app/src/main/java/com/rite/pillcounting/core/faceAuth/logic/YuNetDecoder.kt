package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.RectF
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import com.rite.pillcounting.core.scanning.logic.Detection
import com.rite.pillcounting.core.scanning.logic.NMS
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.exp
import kotlin.math.sqrt

/** A raw TFLite output tensor, decoupled from any Interpreter type so this file stays unit-testable. */
data class RawOutput(val shape: IntArray, val data: FloatArray)

/**
 * Decodes YuNet's raw anchor-grid outputs into face boxes + 5 landmarks + score.
 *
 * Description:
 * Direct Kotlin port of `standalone_face_tf.py`'s `_group_outputs()` + `yunet_decode()`.
 * YuNet emits 12 raw outputs (4 per stride × 3 strides: bbox, keypoints, and 2 score
 * maps whose geometric-mean is the face score) in an unspecified order; this groups
 * them by inferred stride using each tensor's anchor count and last-dim size, then
 * runs the anchor-decode math and hands candidate boxes to the existing [NMS.run].
 *
 * What it does:
 * - [groupOutputs] maps each raw tensor to `("bbox"|"kps"|"score_a"|"score_b", stride)`.
 * - [decode] turns every grid cell above [scoreThreshold] into a [FaceBox], then
 *   suppresses overlapping duplicates via [NMS.run] at [nmsThreshold].
 */
object YuNetDecoder {

    private val STRIDES = intArrayOf(8, 16, 32)

    /**
     * Groups YuNet's 12 raw outputs by stride and role.
     *
     * @param rawOutputs The interpreter's raw output tensors, any order.
     * @param inputW Detector input width in pixels (e.g. 640).
     * @param inputH Detector input height in pixels (e.g. 640).
     * @return Map keyed by (role, stride) — role is one of "bbox", "kps", "score_a", "score_b".
     *
     * Example Usage:
     * val grouped = YuNetDecoder.groupOutputs(rawOutputs, 640, 640)
     */
    fun groupOutputs(rawOutputs: List<RawOutput>, inputW: Int, inputH: Int): Map<Pair<String, Int>, RawOutput> {
        val anchorsToStride = STRIDES.associateBy { (inputH / it) * (inputW / it) }
        val grouped = HashMap<Pair<String, Int>, RawOutput>()
        val scoreMapsByStride = HashMap<Int, MutableList<RawOutput>>()

        for (out in rawOutputs) {
            val anchors = out.shape[1]
            val last = out.shape[2]
            val stride = anchorsToStride[anchors]
                ?: error("output with $anchors anchors does not fit ${inputW}x$inputH")
            when (last) {
                4 -> grouped["bbox" to stride] = out
                10 -> grouped["kps" to stride] = out
                1 -> scoreMapsByStride.getOrPut(stride) { mutableListOf() }.add(out)
                else -> error("unexpected output last dim $last")
            }
        }
        for ((stride, maps) in scoreMapsByStride) {
            check(maps.size == 2) { "stride $stride: expected 2 score maps, got ${maps.size}" }
            grouped["score_a" to stride] = maps[0]
            grouped["score_b" to stride] = maps[1]
        }
        return grouped
    }

    /**
     * Decodes raw detector outputs into face boxes, filtered by score and NMS.
     *
     * @param rawOutputs The interpreter's raw output tensors, any order.
     * @param inputW Detector input width in pixels.
     * @param inputH Detector input height in pixels.
     * @param scoreThreshold Minimum sqrt(cls*obj) score to keep a candidate (script default 0.85).
     * @param nmsThreshold IoU threshold for suppressing overlapping candidates (script default 0.3).
     * @param scale Multiplier mapping detector-input-space coordinates back to the original frame (1.0 if already in frame space).
     * @return Detected faces, largest-area first is NOT guaranteed here — caller sorts if needed.
     *
     * Example Usage:
     * val faces = YuNetDecoder.decode(rawOutputs, 640, 640, 0.85f, 0.3f, scale = 1.5f)
     */
    fun decode(
        rawOutputs: List<RawOutput>,
        inputW: Int,
        inputH: Int,
        scoreThreshold: Float,
        nmsThreshold: Float,
        scale: Float = 1f
    ): List<FaceBox> {
        val grouped = groupOutputs(rawOutputs, inputW, inputH)
        val candidates = ArrayList<FaceBox>()

        for (stride in STRIDES) {
            val cols = inputW / stride
            val n = (inputH / stride) * cols
            val cls = grouped["score_a" to stride]?.data ?: continue
            val obj = grouped["score_b" to stride]?.data ?: continue
            val bbox = grouped["bbox" to stride]?.data ?: continue
            val kps = grouped["kps" to stride]?.data ?: continue

            for (idx in 0 until n) {
                val c = cls[idx].coerceIn(0f, 1f)
                val o = obj[idx].coerceIn(0f, 1f)
                val score = sqrt(c * o)
                if (score < scoreThreshold) continue

                val col = (idx % cols).toFloat()
                val row = (idx / cols).toFloat()

                val cx = (col + bbox[idx * 4 + 0]) * stride
                val cy = (row + bbox[idx * 4 + 1]) * stride
                val bw = exp(bbox[idx * 4 + 2]) * stride
                val bh = exp(bbox[idx * 4 + 3]) * stride

                val landmarks = FloatArray(10)
                for (j in 0 until 5) {
                    landmarks[2 * j] = (col + kps[idx * 10 + 2 * j]) * stride * scale
                    landmarks[2 * j + 1] = (row + kps[idx * 10 + 2 * j + 1]) * stride * scale
                }

                val rect = RectF(
                    (cx - bw / 2) * scale,
                    (cy - bh / 2) * scale,
                    (cx - bw / 2) * scale + bw * scale,
                    (cy - bh / 2) * scale + bh * scale
                )
                candidates.add(FaceBox(rect, landmarks, score))
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // Identity set, not equality: NMS.run returns the same instances it was given,
        // and equality-matching would resurrect a suppressed duplicate whose rect+score
        // happen to equal a kept one.
        val detections = candidates.map { Detection(it.rect, it.score) }
        val kept = NMS.run(detections, nmsThreshold)
        val keptSet = Collections.newSetFromMap(IdentityHashMap<Detection, Boolean>()).apply { addAll(kept) }
        return candidates.filterIndexed { i, _ -> detections[i] in keptSet }
    }
}
