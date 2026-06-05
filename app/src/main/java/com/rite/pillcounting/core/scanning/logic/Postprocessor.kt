package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Decoder for the new pills TFLite model ("Option A" graph).
 *
 * The model emits 6 raw FPN-level tensors instead of decoded boxes — the
 * DFL+anchor decode now lives here in app code (this is the change that
 * unlocks the 30-45 ms GPU-delegate latency on Samsung S22; see
 * MOBILE_INTEGRATION_GUIDE: 02_PILLS_MODEL.md and 06_ANDROID_GPU_DELEGATE.md).
 *
 *   cls_stride{8,16,32}  shape (1, H, W, 1)   already-sigmoided probability
 *   reg_stride{8,16,32}  shape (1, H, W, 68)  raw DFL logits = 4 sides × 17 bins
 *
 *   Anchor center at (col, row) with stride s: ((col+0.5)*s, (row+0.5)*s)
 *   Each side: softmax over 17 bins → dot with [0..16] → multiply by s → distance px
 *   Box: x1=cx-d_left, y1=cy-d_top, x2=cx+d_right, y2=cy+d_bottom (in 640-space)
 */
object Postprocessor {

    private const val DFL_BINS = 17
    private const val SIDES = 4
    private const val REG_CHANNELS = SIDES * DFL_BINS   // 68
    private val FPN_STRIDES = intArrayOf(8, 16, 32)
    private val FPN_GRIDS = intArrayOf(80, 40, 20)

    /**
     * Pre-allocated output buffers + the TFLite tensor indices each maps to.
     * Allocated once at model-load via [allocateOutputs] and reused across frames.
     *
     * The model outputs are bound to **direct [ByteBuffer]s** rather than boxed
     * `Array<Array<Array<FloatArray>>>` tensors. The reg level alone is
     * grid²×68 ≈ 571k floats; copied out of native into a nested Java array
     * TFLite touches every element across JNI each frame. A direct ByteBuffer is
     * a single bulk memcpy. [decode] then copies each level's buffer into a flat
     * [clsFlat]/[regFlat] scratch float[] once (bulk) and indexes that — no
     * per-element JNI reads in the hot loop.
     *
     *   clsBuffers[level]  raw [1, grid, grid, 1]  float32, NHWC row-major
     *   regBuffers[level]  raw [1, grid, grid, 68] float32, NHWC row-major
     */
    class PillOutputs(
        val clsBuffers: Array<ByteBuffer>,
        val regBuffers: Array<ByteBuffer>,
        val clsFlat: Array<FloatArray>,
        val regFlat: Array<FloatArray>,
        val clsIdx: IntArray,
        val regIdx: IntArray
    )

    fun allocateOutputs(interpreter: Interpreter): PillOutputs {
        val cls = arrayOfNulls<ByteBuffer>(FPN_STRIDES.size)
        val reg = arrayOfNulls<ByteBuffer>(FPN_STRIDES.size)
        val clsFlat = arrayOfNulls<FloatArray>(FPN_STRIDES.size)
        val regFlat = arrayOfNulls<FloatArray>(FPN_STRIDES.size)
        val clsIdx = IntArray(FPN_STRIDES.size) { -1 }
        val regIdx = IntArray(FPN_STRIDES.size) { -1 }

        // Match outputs by SHAPE (TFLite reorders auto-generated PartitionedCall:N).
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            if (shape.size != 4 || shape[1] != shape[2]) continue  // expect NHWC square
            val grid = shape[1]
            val levelIdx = FPN_GRIDS.indexOf(grid)
            if (levelIdx < 0) continue

            when (shape[3]) {
                1 -> {
                    clsIdx[levelIdx] = i
                    val n = grid * grid * 1
                    cls[levelIdx] = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
                    clsFlat[levelIdx] = FloatArray(n)
                }
                REG_CHANNELS -> {
                    regIdx[levelIdx] = i
                    val n = grid * grid * REG_CHANNELS
                    reg[levelIdx] = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
                    regFlat[levelIdx] = FloatArray(n)
                }
            }
        }

        require(clsIdx.none { it < 0 } && regIdx.none { it < 0 }) {
            "Pill model outputs not as expected: " +
                    (0 until interpreter.outputTensorCount).joinToString {
                        "$it=${interpreter.getOutputTensor(it).shape().toList()}"
                    }
        }

        @Suppress("UNCHECKED_CAST")
        return PillOutputs(
            clsBuffers = cls as Array<ByteBuffer>,
            regBuffers = reg as Array<ByteBuffer>,
            clsFlat = clsFlat as Array<FloatArray>,
            regFlat = regFlat as Array<FloatArray>,
            clsIdx = clsIdx,
            regIdx = regIdx
        )
    }

    /**
     * Decode the 6 raw FPN tensors into a list of pill detections in the
     * original camera-frame coordinate space.
     *
     * @param outputs        pre-allocated buffers that were populated by the
     *                       most recent interpreter.runForMultipleInputsOutputs.
     * @param confThreshold  per-anchor sigmoid score cutoff (docs default 0.25).
     * @param scale          letterbox scale (640_pixel = source_pixel * scale).
     * @param padX           letterbox horizontal pad in 640-space.
     * @param padY           letterbox vertical pad in 640-space.
     */
    fun decode(
        outputs: PillOutputs,
        confThreshold: Float,
        scale: Float,
        padX: Float,
        padY: Float
    ): List<Detection> {
        val results = ArrayList<Detection>(64)

        for (level in FPN_STRIDES.indices) {
            val stride = FPN_STRIDES[level].toFloat()
            val grid = FPN_GRIDS[level]

            // Bulk-copy this level's raw output out of the direct buffer into a
            // flat float[] once, then index it below — avoids per-element JNI reads.
            val clsFlat = outputs.clsFlat[level]
            val regFlat = outputs.regFlat[level]
            outputs.clsBuffers[level].rewind()
            outputs.clsBuffers[level].asFloatBuffer().get(clsFlat)
            outputs.regBuffers[level].rewind()
            outputs.regBuffers[level].asFloatBuffer().get(regFlat)

            for (row in 0 until grid) {
                val rowBase = row * grid
                for (col in 0 until grid) {
                    val cellIdx = rowBase + col
                    val score = clsFlat[cellIdx]   // NHWC, 1 channel → flat index = row*grid+col
                    if (score < confThreshold) continue

                    // NHWC: reg cell (row,col) starts at (row*grid+col)*68; each
                    // side is a contiguous 17-bin slice within that cell.
                    val regBase = cellIdx * REG_CHANNELS
                    // DFL projection: softmax over each 17-bin slice, then dot with [0..16],
                    // then multiply by stride to get pixel distance per side.
                    val dLeft   = dflProject(regFlat, regBase + 0 * DFL_BINS) * stride
                    val dTop    = dflProject(regFlat, regBase + 1 * DFL_BINS) * stride
                    val dRight  = dflProject(regFlat, regBase + 2 * DFL_BINS) * stride
                    val dBottom = dflProject(regFlat, regBase + 3 * DFL_BINS) * stride

                    val cx = (col + 0.5f) * stride
                    val cy = (row + 0.5f) * stride

                    // 640-space → source-frame: reverse the letterbox transform
                    val x1 = ((cx - dLeft) - padX) / scale
                    val y1 = ((cy - dTop) - padY) / scale
                    val x2 = ((cx + dRight) - padX) / scale
                    val y2 = ((cy + dBottom) - padY) / scale

                    results.add(Detection(rect = RectF(x1, y1, x2, y2), confidence = score))
                }
            }
        }

        return results
    }

    /**
     * Numerically-stable softmax over a 17-bin slice followed by the dot product
     * with [0, 1, ..., 16]. Returns the expected value (still in stride units —
     * caller multiplies by stride).
     */
    private fun dflProject(regVec: FloatArray, offset: Int): Float {
        // 1) max for numerical stability
        var maxLogit = regVec[offset]
        for (i in 1 until DFL_BINS) {
            val v = regVec[offset + i]
            if (v > maxLogit) maxLogit = v
        }
        // 2) softmax + 3) dot with [0..16] in one pass
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
