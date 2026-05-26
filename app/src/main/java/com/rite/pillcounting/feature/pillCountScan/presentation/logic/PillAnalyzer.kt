package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

/**
 * Runs the three-model pipeline on every camera frame:
 *
 *   1. Pre-process — letterbox to 640×640 NHWC float32 RGB-/255.
 *   2. Tray, pill, (optional) glove inference — IN PARALLEL on Dispatchers.Default.
 *      All three share duplicate() views of the same buffer (the on-device exports
 *      all accept the same NHWC RGB-/255 input despite what the docs claim).
 *      Each Interpreter has its own GpuDelegate.
 *   3. Postprocess pill output and filter to pills inside any tray's bbox.
 *   4. Callback to UI.
 */
class PillAnalyzer(
    private val pillInterpreter: Interpreter,
    // Tray is an ONNX masks detector (segmentation). Nullable so the analyzer
    // can run without it; when null, every detected pill is counted (no filter).
    private val trayMasksDetector: TrayMasksDetector?,
    private val gloveInterpreter: Interpreter?,
    private val performanceLogger: PerformanceLogger? = null,
    private val shouldRunGloveDetection: () -> Boolean,
    private val onResult: (
        pillCount: Int,
        pills: List<Detection>,
        trayRects: List<TrayDetection>,
        gloveDetections: List<GloveDetection>,
        debugBitmap: Bitmap,
        transformMatrix: Matrix,
        imageWidth: Int,
        imageHeight: Int
    ) -> Unit
) {

    val hasGloveInterpreter: Boolean get() = gloveInterpreter != null

    private val logger = AppLogger("PillAnalyzer")

    // Glove cadence: every frame until first detection, then rate-limited.
    private var lastGloveRunMs = 0L
    private var hasDetectedAnyGlove = false
    private var cachedGloveDetections: List<GloveDetection> = emptyList()

    // Last frame's accepted pill rects. Hysteresis: a borderline-confidence
    // pill that overlaps one of these is allowed to stay; new (non-overlapping)
    // pills must clear PILL_CONF_ENTER. Kills the 19↔20 count flicker.
    private var previousFramePillRects: List<android.graphics.RectF> = emptyList()

    // Reusable inference output buffers. Allocated once via interpreter shape
    // introspection so allocations don't show up as GC pressure on weak devices.
    // (Tray is ONNX-based — its detector allocates outputs internally per call.)
    private val pillOutputs by lazy { Postprocessor.allocateOutputs(pillInterpreter) }
    private val gloveOutputs by lazy { GloveDetector.allocateOutput(gloveInterpreter!!) }

    companion object {
        // Run every frame until first glove detection arrives; afterwards only every
        // GLOVE_STEADY_INTERVAL_MS so we keep the GPU free for pill + tray.
        private const val GLOVE_STEADY_INTERVAL_MS = 400L

        // Pill confidence hysteresis to kill 19↔20 frame-to-frame flicker.
        // With the tray-filter re-enabled, non-tray false positives (fingernails,
        // debris on the desk) are already filtered out spatially, so we can
        // afford a more permissive STAY threshold for stability on real pills.
        //   ENTER (0.55): a NEWLY visible pill must clear this in one frame.
        //   STAY  (0.40): a pill from the previous frame can persist at lower
        //                 confidence if it spatially overlaps (IoU ≥ 0.40).
        private const val PILL_CONF_ENTER = 0.55f
        private const val PILL_CONF_STAY = 0.40f
        private const val HYSTERESIS_IOU = 0.40f
        private const val PILL_NMS_IOU = 0.45f
    }

    suspend fun analyze(imageProxy: ImageProxy) {
        val overallStart = System.currentTimeMillis()
        var trackingBitmap: Bitmap? = null

        try {
            // ── STEP 1: Pre-process ───────────────────────────────────────────
            val pre = ImagePreprocessor.preprocess(imageProxy)
            val originalBitmap = pre.original
            trackingBitmap = originalBitmap

            val scaleInfo = Letterbox.currentScaleInfo
                ?: throw IllegalStateException("Letterbox.currentScaleInfo missing after preprocess")

            val originalWidth = imageProxy.width
            val originalHeight = imageProxy.height

            // TFLite pill+glove consume NHWC RGB /255; ONNX tray consumes NCHW
            // RGB ImageNet-normalized. duplicate() is cheap (no memcpy).
            val pillBuf = pre.rgbNormalized.duplicateRewound()
            val gloveBuf = pre.rgbNormalized.duplicateRewound()
            val trayBuf = pre.rgbImageNetNchw.duplicateRewound()

            val now = System.currentTimeMillis()
            val interval = if (hasDetectedAnyGlove) GLOVE_STEADY_INTERVAL_MS else 0L
            val runGloveThisFrame = gloveInterpreter != null &&
                    shouldRunGloveDetection() &&
                    (now - lastGloveRunMs >= interval)
            if (runGloveThisFrame) lastGloveRunMs = now

            // ── STEP 2: Run models in parallel ────────────────────────────────
            // Tray runs every frame so the UI tracks the camera live — when
            // the user moves the phone away, the tray bbox and the pill
            // centroid markers must disappear in the next frame, not linger
            // behind a stale cache.
            var trayDetections: List<TrayDetection> = emptyList()
            val pillSucceeded: Boolean
            val gloveDetections: List<GloveDetection>
            var pillInferenceStart: Long
            var pillInferenceMs: Long = 0L

            val parallelStart = System.currentTimeMillis()
            coroutineScope {
                val trayDeferred = trayMasksDetector?.let { detector ->
                    async(Dispatchers.Default) {
                        detector.detect(
                            inputBuffer = trayBuf,
                            scaleInfo = scaleInfo,
                            originalWidth = originalWidth,
                            originalHeight = originalHeight
                        )
                    }
                }

                pillInferenceStart = System.currentTimeMillis()
                val pillDeferred = async(Dispatchers.Default) {
                    runPillInference(pillBuf)
                }
                val gloveDeferred = if (runGloveThisFrame) async(Dispatchers.Default) {
                    GloveDetector.detect(
                        interpreter = gloveInterpreter!!,
                        inputBuffer = gloveBuf,
                        scaleInfo = scaleInfo,
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                        outputs = gloveOutputs
                    )
                } else null

                if (trayDeferred != null) trayDetections = trayDeferred.await()
                pillSucceeded = pillDeferred.await()
                pillInferenceMs = System.currentTimeMillis() - pillInferenceStart
                gloveDetections = if (gloveDeferred != null) {
                    val fresh = gloveDeferred.await()
                    cachedGloveDetections = fresh
                    if (fresh.isNotEmpty()) hasDetectedAnyGlove = true
                    fresh
                } else {
                    cachedGloveDetections
                }
            }
            val parallelMs = System.currentTimeMillis() - parallelStart

            // ── STEP 3: Postprocess pill output ───────────────────────────────
            val pillsInTray: List<Detection>
            if (!pillSucceeded) {
                logger.i("PillFilter — inference failed")
                pillsInTray = emptyList()
            } else {
                val allPills = Postprocessor.decode(
                    outputs = pillOutputs,
                    confThreshold = PILL_CONF_STAY,
                    scale = scaleInfo.scale,
                    padX = scaleInfo.padX,
                    padY = scaleInfo.padY
                )
                val pillsAfterNms = NMS.run(allPills, iouThreshold = PILL_NMS_IOU)
                val pillsAfterHysteresis = applyHysteresis(pillsAfterNms)
                val trayCount = trayDetections.count { it.cls == TrayClass.TRAY }
                val chuteCount = trayDetections.count { it.cls == TrayClass.CHUTE }
                logger.i("PillFilter — decoded=${allPills.size} afterNMS=${pillsAfterNms.size} afterHyst=${pillsAfterHysteresis.size} trayDets=$trayCount chuteDets=$chuteCount")

                // Tray filter — only applies when the tray model is available.
                // Until the ONNX tray is wired up, every detected pill is counted.
                val inScene = if (trayDetections.isEmpty()) {
                    pillsAfterHysteresis
                } else {
                    pillsAfterHysteresis.filter { pill ->
                        val cx = pill.rect.centerX().toInt()
                        val cy = pill.rect.centerY().toInt()
                        val inTray = trayDetections.any { t ->
                            t.cls == TrayClass.TRAY && t.containsPoint(cx, cy)
                        }
                        if (!inTray) return@filter false
                        val inChute = trayDetections.any { t ->
                            t.cls == TrayClass.CHUTE && t.containsPoint(cx, cy)
                        }
                        !inChute
                    }
                }
                pillsInTray = inScene
                previousFramePillRects = pillsInTray.map { it.rect }
            }

            val countedPills = pillsInTray.size

            val totalMs = System.currentTimeMillis() - overallStart
            logger.i(
                "Frame ${originalWidth}x${originalHeight} | trays=${trayDetections.size} " +
                        "counted=$countedPills gloves=${gloveDetections.size} " +
                        "pill=${pillInferenceMs}ms parallel=${parallelMs}ms total=${totalMs}ms"
            )

            if (runGloveThisFrame) {
                performanceLogger?.logInference(
                    modelName = "Glove Detection (PP-YOLOE+s)",
                    inferenceTimeMs = parallelMs.toLong(),
                    preprocessTimeMs = 0,
                    postprocessTimeMs = 0,
                    detectionCount = gloveDetections.size
                )
                if (gloveDetections.isNotEmpty()) {
                    val summary = gloveDetections.joinToString { "${it.className}(${(it.confidence * 100).toInt()}%)" }
                    android.util.Log.e("GLOVE_DETECTION", "🎯 FOUND: $summary")
                }
            }

            // ── STEP 4: Callback ──────────────────────────────────────────────
            onResult(
                countedPills,
                pillsInTray,
                trayDetections,
                gloveDetections,
                originalBitmap,
                Matrix(),
                originalWidth,
                originalHeight
            )

            // pre.letterboxed is owned and reused by Letterbox — do NOT recycle.

        } catch (e: Exception) {
            logger.e("[PillAnalyzer] Frame failed", e)
            trackingBitmap?.recycle()
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Re-enable fast (every-frame) glove inference. Call when the screen is
     * (re-)opened or the user manually resets the workflow, so the next "gloves on
     * vs off" decision is made within one frame instead of after the rate-limit
     * window.
     */
    fun resetGloveCadence() {
        hasDetectedAnyGlove = false
        lastGloveRunMs = 0L
        cachedGloveDetections = emptyList()
        // Drop pill hysteresis history so the next frame requires full conf
        // to accept pills (no stale rects matching new scene).
        previousFramePillRects = emptyList()
    }

    /**
     * Runs the pill interpreter, writing the 6 raw FPN tensors into [pillOutputs].
     * Returns true on success; caller reads the buffers via Postprocessor.decode().
     */
    private fun runPillInference(buf: ByteBuffer): Boolean {
        return try {
            buf.rewind()
            val outMap = HashMap<Int, Any>(6)
            for (i in pillOutputs.clsIdx.indices) {
                outMap[pillOutputs.clsIdx[i]] = pillOutputs.clsBuffers[i]
                outMap[pillOutputs.regIdx[i]] = pillOutputs.regBuffers[i]
            }
            pillInterpreter.runForMultipleInputsOutputs(arrayOf(buf), outMap)
            true
        } catch (e: Exception) {
            logger.e("Pill inference failed", e)
            false
        }
    }

    /**
     * Hysteresis filter on the post-NMS pill candidates.
     *
     * The decoder ran at the LOW (stay) confidence threshold to keep borderline
     * pills as candidates. Here we drop any candidate that doesn't qualify:
     *   - Conf >= PILL_CONF_ENTER → always kept (new pills clear the high bar)
     *   - Conf in [PILL_CONF_STAY, PILL_CONF_ENTER) → kept only if it overlaps
     *     a pill from the previous frame (IoU >= HYSTERESIS_IOU), meaning it's
     *     the same pill we already counted last frame.
     *
     * This is what removes the 19↔20 flicker on pills whose confidence is
     * jittering across the threshold from one camera frame to the next.
     */
    private fun applyHysteresis(candidates: List<Detection>): List<Detection> {
        if (candidates.isEmpty()) return candidates
        val prev = previousFramePillRects
        var keptByOverlap = 0
        val kept = candidates.filter { c ->
            if (c.confidence >= PILL_CONF_ENTER) {
                true
            } else {
                // Borderline conf — must overlap a previous-frame pill.
                val matched = prev.any { p -> iouRect(c.rect, p) >= HYSTERESIS_IOU }
                if (matched) keptByOverlap++
                matched
            }
        }
        if (keptByOverlap > 0) {
            logger.i("Hysteresis kept $keptByOverlap borderline pill(s) by IoU match with previous frame")
        }
        return kept
    }

    private fun iouRect(a: android.graphics.RectF, b: android.graphics.RectF): Float {
        val iw = (kotlin.math.min(a.right, b.right) - kotlin.math.max(a.left, b.left))
            .coerceAtLeast(0f)
        val ih = (kotlin.math.min(a.bottom, b.bottom) - kotlin.math.max(a.top, b.top))
            .coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun ByteBuffer.duplicateRewound(): ByteBuffer = duplicate().apply {
        order(this@duplicateRewound.order())
        rewind()
    }
}
