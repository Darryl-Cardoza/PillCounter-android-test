package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * TFLite wrapper for the YOLOX-Nano gloves / no_gloves detector
 * (`gloves_detector_fp32.tflite`, shipped AES-GCM encrypted).
 *
 * Replaces the previous MobileNetV2 binary classifier. The detector
 * restores the implicit-negatives behavior the classifier lost in the
 * bbox-to-classification conversion: an empty scene (no hand at all)
 * produces zero detections above the confidence threshold, which the
 * app reads as "no gloves", eliminating the empty-scene false-positives
 * the classifier exhibited.
 *
 * Architecture (Mali r38p1-friendly, all ops on the GPU delegate):
 *   - LeakyReLU(0.1) throughout (instead of SiLU)
 *   - 6×6 stride-2 Conv stem (instead of Focus' strided slice)
 *   - Nearest-mode upsample in PAFPN neck
 *   - 320×320 NHWC float32 input, RAW pixel values in [0, 255]
 *     (no normalization — YOLOX trains on raw pixels)
 *
 * Output graph contract (3 outputs, NHWC after onnx2tf transpose):
 *     output_stride8  : [1, 40, 40, 7]
 *     output_stride16 : [1, 20, 20, 7]
 *     output_stride32 : [1, 10, 10, 7]
 *   Channel layout (axis=C=3): tx, ty, tw, th, obj_logit, cls0_logit, cls1_logit
 *
 * Decode + NMS run here on the CPU (Kotlin) — the .tflite graph stays
 * 100% GPU-delegated because no NMS / sigmoid-then-mul ops are baked in.
 *
 * Class id contract (LOCKED — matches data/coco_to_yolox.py CLASS_MAP):
 *   0 = gloves
 *   1 = no_gloves   (a hand with no glove on it)
 *
 * Downstream consumers (PillScanningViewModel) keep filtering on
 * `classId == 0 && confidence >= 0.75` for "user is wearing gloves",
 * so the existing 0.75 hysteresis threshold continues to apply.
 * The class-1 (`no_gloves`) detections are surfaced for the on-screen
 * overlay (CameraPreviewSection draws them red) but do not trip the gate.
 *
 * NOT thread-safe — scratch buffers are reused across frames. Call
 * `detect` from a single coroutine (PillAnalyzer does this).
 */
object GloveDetector {

    private const val TAG = "GloveDetector"
    private val logger = AppLogger(TAG)

    /** Model input spatial resolution. MUST match the exported .tflite. */
    const val INPUT_SIZE = 320
    private const val NUM_CHANNELS = 3
    private const val NUM_CLASSES = 2
    private const val CHANNELS_PER_PRED = 5 + NUM_CLASSES   // tx,ty,tw,th,obj + 2 cls
    private const val BYTES_PER_FLOAT = 4
    private const val LETTERBOX_SIZE = 640                  // pipeline's standard letterbox size

    /**
     * Default deployment thresholds. NMS IoU matches `deploy_nms_iou` in
     * `yolox_nano_gloves.py`. The confidence threshold is set above the training
     * default so only clearly-confident gloves count and weak/borderline
     * detections are dropped.
     */
    const val DEFAULT_CONF_THRESHOLD = 0.80f
    const val DEFAULT_NMS_IOU = 0.45f

    // Public class-label constants (consumed by CameraPreviewSection to colour
    // the on-screen overlay). Kept as the single source of truth for CLASS_NAMES.
    const val CLASS_GLOVES = "gloves"
    const val CLASS_NO_GLOVES = "no_gloves"

    /** Class labels (id-indexed). Order matches the Python CLASS_MAP (0=gloves, 1=no_gloves). */
    val CLASS_NAMES = arrayOf(CLASS_GLOVES, CLASS_NO_GLOVES)

    private val STRIDES = intArrayOf(8, 16, 32)
    private val GRID_SIZES = intArrayOf(
        INPUT_SIZE / 8,    // 40
        INPUT_SIZE / 16,   // 20
        INPUT_SIZE / 32,   // 10
    )

    // ── Cached scratch (allocated once, reused across frames) ────────────
    private val inputBitmap: Bitmap by lazy {
        Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    }
    private val inputCanvas: Canvas by lazy { Canvas(inputBitmap) }
    private val resizeMatrix = Matrix()
    private val resizePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pixelScratch by lazy { IntArray(INPUT_SIZE * INPUT_SIZE) }
    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
    }

    // Output scratch per stride — shape [1][H][W][7] NHWC (onnx2tf default).
    // Allocated lazily because we need the interpreter's output dtype/shape on first use.
    // Type breakdown:
    //   outer Array(3)                       = one entry per FPN level (stride 8/16/32)
    //   each entry: Array<Array<Array<FloatArray>>> = the 4D NHWC tensor [1][H][W][C]
    private var outputScratch: Array<Array<Array<Array<FloatArray>>>>? = null
    private var outputIndexByStride: IntArray = IntArray(3)  // map stride8/16/32 -> interp output idx

    /**
     * Run detection on a frame.
     *
     * @param interpreter        TFLite interpreter for the detector model.
     * @param letterboxedBitmap  640×640 ARGB letterboxed frame from [ImagePreprocessor].
     * @param scaleInfo640       The letterbox scale info for the 640 stage.
     *                           Needed to unwarp boxes back to original-camera coords.
     * @param originalWidth, originalHeight  Original camera frame dims; used to
     *                                       clamp the final boxes to image bounds.
     * @param confThreshold      Score = sigmoid(obj) * max(sigmoid(cls)). Default 0.30.
     * @param iouThreshold       Class-wise NMS IoU. Default 0.45.
     *
     * @return  List of [GloveDetection] in original-image pixel coordinates.
     *          Empty list = nothing detected above threshold (interpret as
     *          "no gloves visible" at the compliance gate).
     */
    fun detect(
        interpreter: Interpreter,
        letterboxedBitmap: Bitmap,
        scaleInfo640: Letterbox.ScaleInfo,
        originalWidth: Int,
        originalHeight: Int,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_NMS_IOU,
    ): List<GloveDetection> {
        return try {
            ensureOutputScratch(interpreter)
            preprocessTo320(letterboxedBitmap)
            runInference(interpreter)

            val raw = decodeAllStrides(confThreshold)
            val survivors = nmsClassWise(raw, iouThreshold)

            // Boxes are currently in 320-letterbox coords (where 320 is the
            // model input). The 640 letterbox was downscaled by 2.0× to feed
            // the model, so multiplying coords by 2 brings them back into
            // 640-letterbox space. Then the 640 letterbox scaleInfo unwarps
            // to original camera coords.
            val to640 = LETTERBOX_SIZE.toFloat() / INPUT_SIZE.toFloat()   // = 2.0
            val invScale = 1f / scaleInfo640.scale
            val padX = scaleInfo640.padX
            val padY = scaleInfo640.padY

            val result = ArrayList<GloveDetection>(survivors.size)
            for (d in survivors) {
                val x1_640 = d.x1 * to640
                val y1_640 = d.y1 * to640
                val x2_640 = d.x2 * to640
                val y2_640 = d.y2 * to640

                val x1 = ((x1_640 - padX) * invScale).coerceIn(0f, originalWidth.toFloat())
                val y1 = ((y1_640 - padY) * invScale).coerceIn(0f, originalHeight.toFloat())
                val x2 = ((x2_640 - padX) * invScale).coerceIn(0f, originalWidth.toFloat())
                val y2 = ((y2_640 - padY) * invScale).coerceIn(0f, originalHeight.toFloat())

                if (x2 - x1 < 2f || y2 - y1 < 2f) continue   // degenerate box

                result.add(
                    GloveDetection(
                        rect = RectF(x1, y1, x2, y2),
                        confidence = d.score,
                        classId = d.classId,
                        className = CLASS_NAMES[d.classId],
                    )
                )
            }

            if (result.isNotEmpty()) {
                logger.d("detections=${result.size}  " +
                        result.joinToString { "${it.className}(${"%.2f".format(it.confidence)})" })
            }
            result
        } catch (e: Exception) {
            logger.e("Glove detector inference failed", e)
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Preprocess: 640 letterbox -> 320 input bitmap -> NHWC float32 buffer.
    // ──────────────────────────────────────────────────────────────────────
    private fun preprocessTo320(letterboxedBitmap: Bitmap) {
        val k = INPUT_SIZE.toFloat() / letterboxedBitmap.width.toFloat()  // 320/640 = 0.5
        resizeMatrix.reset()
        resizeMatrix.postScale(k, k)
        inputCanvas.drawColor(0)
        inputCanvas.drawBitmap(letterboxedBitmap, resizeMatrix, resizePaint)

        inputBitmap.getPixels(pixelScratch, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // NHWC float32 in raw [0, 255]. YOLOX does NOT bake in ImageNet
        // normalization — it trains directly on raw pixel values.
        // Channel order is BGR (NOT RGB): the model was trained on BGR input,
        // so feeding RGB here caused the bare-hand→"gloves" false positives.
        inputBuffer.clear()
        val fv = inputBuffer.asFloatBuffer()
        var i = 0
        while (i < pixelScratch.size) {
            val p = pixelScratch[i]
            fv.put((p and 0xFF).toFloat())            // B
            fv.put(((p shr 8) and 0xFF).toFloat())    // G
            fv.put(((p shr 16) and 0xFF).toFloat())   // R
            i++
        }
        inputBuffer.rewind()
    }

    // ──────────────────────────────────────────────────────────────────────
    // First-call setup: identify which interpreter output index is stride
    // 8 / 16 / 32, and allocate the corresponding scratch arrays.
    // onnx2tf may emit the outputs in any order; we sort by spatial size
    // (largest H = stride 8).
    // ──────────────────────────────────────────────────────────────────────
    @Suppress("UNCHECKED_CAST")
    private fun ensureOutputScratch(interpreter: Interpreter) {
        if (outputScratch != null) return

        val nOut = interpreter.outputTensorCount
        check(nOut == 3) { "expected 3 output tensors, got $nOut" }

        // Pair (interp-idx, spatial-H) for each output.
        val outShapes = Array(nOut) { idx ->
            val t = interpreter.getOutputTensor(idx)
            val s = t.shape()   // expect [1, H, W, 7]
            check(s.size == 4 && s[3] == CHANNELS_PER_PRED) {
                "unexpected output shape at idx=$idx: ${s.toList()}"
            }
            Triple(idx, s[1], s[2])
        }

        // Sort descending by H so largest grid = stride 8.
        val sorted = outShapes.sortedByDescending { it.second }
        for (i in 0 until 3) {
            val (idx, h, w) = sorted[i]
            check(h == GRID_SIZES[i] && w == GRID_SIZES[i]) {
                "grid mismatch at level $i: model says ${h}x$w, expected " +
                        "${GRID_SIZES[i]}x${GRID_SIZES[i]}. INPUT_SIZE constant " +
                        "may be out of sync with the exported model."
            }
            outputIndexByStride[i] = idx
        }

        // Allocate NHWC scratch per level: [1][H][W][7]
        outputScratch = Array(3) { i ->
            val g = GRID_SIZES[i]
            Array(1) { Array(g) { Array(g) { FloatArray(CHANNELS_PER_PRED) } } }
        }
    }

    private fun runInference(interpreter: Interpreter) {
        val scratch = outputScratch!!
        val outMap = HashMap<Int, Any>(3)
        for (i in 0 until 3) {
            outMap[outputIndexByStride[i]] = scratch[i]
        }
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outMap)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Decode + NMS — mirrors yolox_nano/eval/decode_reference.py exactly.
    // ──────────────────────────────────────────────────────────────────────
    private data class DecodedBox(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val score: Float, val classId: Int,
    )

    private fun decodeAllStrides(confThreshold: Float): MutableList<DecodedBox> {
        val out = ArrayList<DecodedBox>(64)
        val scratch = outputScratch!!
        for (level in 0 until 3) {
            val stride = STRIDES[level]
            val grid = GRID_SIZES[level]
            val feat = scratch[level][0]   // [H][W][7]

            for (gy in 0 until grid) {
                val row = feat[gy]
                for (gx in 0 until grid) {
                    val c = row[gx]
                    val tx = c[0]
                    val ty = c[1]
                    val tw = c[2]
                    val th = c[3]
                    val objLogit = c[4]

                    val objP = sigmoid(objLogit)
                    if (objP < confThreshold * 0.5f) continue   // quick reject

                    // Find best class among cls0, cls1 in [5..6].
                    var bestCls = 0
                    var bestClsP = sigmoid(c[5])
                    val cls1P = sigmoid(c[6])
                    if (cls1P > bestClsP) {
                        bestCls = 1
                        bestClsP = cls1P
                    }
                    val score = objP * bestClsP
                    if (score < confThreshold) continue

                    val cx = (tx + gx) * stride
                    val cy = (ty + gy) * stride
                    val w = exp(tw) * stride
                    val h = exp(th) * stride

                    val x1 = cx - w * 0.5f
                    val y1 = cy - h * 0.5f
                    val x2 = cx + w * 0.5f
                    val y2 = cy + h * 0.5f

                    out.add(DecodedBox(x1, y1, x2, y2, score, bestCls))
                }
            }
        }
        return out
    }

    private fun nmsClassWise(boxes: MutableList<DecodedBox>, iouThreshold: Float): List<DecodedBox> {
        if (boxes.isEmpty()) return emptyList()
        // Bucket by class then NMS within bucket.
        val byClass = arrayOf(ArrayList<DecodedBox>(), ArrayList<DecodedBox>())
        for (b in boxes) byClass[b.classId].add(b)

        val survivors = ArrayList<DecodedBox>(boxes.size)
        for (group in byClass) {
            group.sortByDescending { it.score }
            val kept = BooleanArray(group.size)   // false = still alive
            for (i in group.indices) {
                if (kept[i]) continue
                val a = group[i]
                survivors.add(a)
                for (j in i + 1 until group.size) {
                    if (kept[j]) continue
                    if (iou(a, group[j]) >= iouThreshold) kept[j] = true
                }
            }
        }
        survivors.sortByDescending { it.score }
        return survivors
    }

    private fun iou(a: DecodedBox, b: DecodedBox): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val aa = (a.x2 - a.x1).coerceAtLeast(0f) * (a.y2 - a.y1).coerceAtLeast(0f)
        val bb = (b.x2 - b.x1).coerceAtLeast(0f) * (b.y2 - b.y1).coerceAtLeast(0f)
        val union = aa + bb - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun sigmoid(x: Float): Float =
        if (x >= 0f) 1f / (1f + exp(-x)) else exp(x) / (1f + exp(x))
}
