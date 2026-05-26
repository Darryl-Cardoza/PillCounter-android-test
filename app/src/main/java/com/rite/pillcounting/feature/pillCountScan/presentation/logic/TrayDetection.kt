package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.RectF
import android.util.Log
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.nio.ByteBuffer
import java.util.BitSet

/** RTMDet tray model emits two classes — 0 = chute, 1 = tray. */
enum class TrayClass { TRAY, CHUTE }

/**
 * One detected tray or chute.
 *
 * Mask is stored as a packed [BitSet] in row-major 640×640 order — one
 * allocation per detection (~50 KB) instead of 640 BooleanArray allocations
 * (~640 small objects + ~410 KB). Lookups are O(1) via a single index.
 */
data class TrayDetection(
    val rect: RectF,
    val confidence: Float,
    val cls: TrayClass = TrayClass.TRAY,
    val mask: BitSet? = null,
    val maskSize: Int = 0,                       // side length of the square mask (= 640)
    val scaleInfo: Letterbox.ScaleInfo? = null
) {
    fun containsPoint(x: Int, y: Int): Boolean {
        val m = mask
        val s = scaleInfo
        if (m != null && s != null && maskSize > 0) {
            val x640 = (x.toFloat() * s.scale + s.padX).toInt()
            val y640 = (y.toFloat() * s.scale + s.padY).toInt()
            if (x640 < 0 || x640 >= maskSize || y640 < 0 || y640 >= maskSize) return false
            return m.get(y640 * maskSize + x640)
        }
        return x.toFloat() in rect.left..rect.right && y.toFloat() in rect.top..rect.bottom
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrayDetection) return false
        return confidence == other.confidence && rect == other.rect && cls == other.cls
    }
    override fun hashCode(): Int =
        31 * (31 * rect.hashCode() + confidence.hashCode()) + cls.hashCode()
}

/**
 * ONNX Runtime wrapper for `rtmdet_ins_tray_tiny_640.onnx`.
 *
 *  - Input:    `input`, NCHW float32 [1, 3, 640, 640], RGB ImageNet-normalized
 *  - Outputs:  dets [1, N, 5]  (x1, y1, x2, y2, score in 640-space)
 *              labels [1, N]   int64 (0 = chute, 1 = tray)
 *              masks  [1, N, 640, 640] sigmoid mask (threshold at 0.5)
 *
 * Detections are capped in-graph: max N = 10, score_threshold = 0.25.
 *
 * In-graph NMS — no app-side NMS needed.
 * NOT thread-safe — call detect() on a single coroutine at a time.
 */
class TrayMasksDetector(
    private val env: OrtEnvironment,
    private val session: OrtSession
) {
    private val logger = AppLogger(TAG)

    // Per-detection mask scratch — reused for the float → BitSet thresholding.
    private val maskScratch = FloatArray(INPUT_SIZE * INPUT_SIZE)

    fun detect(
        inputBuffer: ByteBuffer,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int
    ): List<TrayDetection> {
        return try {
            inputBuffer.rewind()
            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val floatView = inputBuffer.asFloatBuffer()
            OnnxTensor.createTensor(env, floatView, shape).use { inputTensor ->
                session.run(mapOf(INPUT_NAME to inputTensor)).use { results ->
                    decodeOutputs(results, scaleInfo, originalWidth, originalHeight)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ONNX tray inference failed", e)
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeOutputs(
        results: OrtSession.Result,
        scaleInfo: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int
    ): List<TrayDetection> {
        val dets   = (results.get(0).value as Array<Array<FloatArray>>)[0]   // [N, 5]
        val labels = (results.get(1).value as Array<LongArray>)[0]           // [N]
        val n = dets.size
        if (n == 0) return emptyList()

        val masksTensor = results.get(2) as OnnxTensor
        val maskFloats = masksTensor.floatBuffer  // length = N * 640 * 640
        val maskSize = INPUT_SIZE * INPUT_SIZE

        val out = ArrayList<TrayDetection>(4)
        for (i in 0 until n) {
            val score = dets[i][4]
            if (score < SCORE_THRESHOLD) continue

            val cls = if (labels[i].toInt() == TRAY_CLASS_ID) TrayClass.TRAY else TrayClass.CHUTE

            val x1 = (dets[i][0] - scaleInfo.padX) / scaleInfo.scale
            val y1 = (dets[i][1] - scaleInfo.padY) / scaleInfo.scale
            val x2 = (dets[i][2] - scaleInfo.padX) / scaleInfo.scale
            val y2 = (dets[i][3] - scaleInfo.padY) / scaleInfo.scale
            val rect = RectF(
                x1.coerceIn(0f, originalWidth.toFloat()),
                y1.coerceIn(0f, originalHeight.toFloat()),
                x2.coerceIn(0f, originalWidth.toFloat()),
                y2.coerceIn(0f, originalHeight.toFloat())
            )
            if (rect.width() <= 0f || rect.height() <= 0f) continue

            maskFloats.position(i * maskSize)
            maskFloats.get(maskScratch)

            // Pack the 640×640 threshold result into a single BitSet.
            // BitSet's internal long[] is ~50 KB vs ~410 KB for Array<BooleanArray>,
            // and the threshold loop is straight-line over a flat FloatArray.
            val bits = BitSet(maskSize)
            for (idx in 0 until maskSize) {
                if (maskScratch[idx] > MASK_THRESHOLD) bits.set(idx)
            }

            out.add(
                TrayDetection(
                    rect = rect,
                    confidence = score,
                    cls = cls,
                    mask = bits,
                    maskSize = INPUT_SIZE,
                    scaleInfo = scaleInfo
                )
            )
        }

        val trays = out.count { it.cls == TrayClass.TRAY }
        val chutes = out.count { it.cls == TrayClass.CHUTE }
        val firstTray = out.firstOrNull { it.cls == TrayClass.TRAY }
        val maskTrueCount = firstTray?.mask?.cardinality() ?: 0
        logger.i("TrayMasks: $trays tray(s), $chutes chute(s) [N_raw=$n N_kept=${out.size} trayMaskTrue=$maskTrueCount / $maskSize]")
        return out
    }

    fun close() {
        try { session.close() } catch (e: Exception) { Log.w(TAG, "OrtSession close failed: ${e.message}") }
    }

    companion object {
        private const val TAG = "TrayMasksDetector"
        const val INPUT_SIZE = 640
        const val INPUT_NAME = "input"
        const val SCORE_THRESHOLD = 0.6f
        const val MASK_THRESHOLD = 0.5f
        const val TRAY_CLASS_ID = 1   // 0 = chute, 1 = tray (per docs)
    }
}
