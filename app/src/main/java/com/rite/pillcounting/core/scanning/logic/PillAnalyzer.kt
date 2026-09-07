package com.rite.pillcounting.core.scanning.logic

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
    // Tray is a TFLite GPU semantic segmentation detector (MobileNetV2-UNet,
    // 384 input, 3 classes: bg/chute/tray). Nullable so the analyzer can run
    // without it; when null, every detected pill is counted (no spatial filter).
    private val traySegDetector: TraySegmentationDetector?,
    private val gloveInterpreter: Interpreter?,
    private val performanceLogger: PerformanceLogger? = null,
    private val shouldRunGloveDetection: () -> Boolean,
    private val shouldDetectTrayColor: () -> Boolean = { false },
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

    // Cross-frame pill state. The tracker decides which detections are real
    // (enter/keep/exit); the stabilizer decides what number is displayed.
    private val pillTracker = PillTracker()
    private val countStabilizer = CountStabilizer()

    // ── Anti-flicker temporal smoothing state ──────────────────────────────
    // Tray seg occasionally misses a frame even when the tray is steady; holding
    // the last good detections for a few frames stops the overlay + pill gate
    // from blinking.
    private var heldTrayDetections: List<TrayDetection> = emptyList()
    private var trayMissFrames = 0

    // Reusable inference output buffers. Allocated once via interpreter shape
    // introspection so allocations don't show up as GC pressure on weak devices.
    // (Tray is TFLite GPU — its detector allocates outputs internally per call.
    // Glove is YOLOX-Nano (320 input) — its 3 FPN output tensors are
    // allocated inside GloveDetector as static scratch on first call.)
    private val pillOutputs by lazy { Postprocessor.allocateOutputs(pillInterpreter) }

    companion object {
        // Run every frame until first glove detection arrives; afterwards only every
        // GLOVE_STEADY_INTERVAL_MS so we keep the GPU free for pill + tray.
        private const val GLOVE_STEADY_INTERVAL_MS = 400L

        // Decode floor: below the tracker's keep score nothing can hold a track,
        // so anchors under this never need decoding.
        private const val PRE_NMS_SCORE_FLOOR = 0.35f
        private const val PILL_NMS_IOU = 0.60f

        // ── Anti-flicker smoothing ──────────────────────────────────────────
        // Bridge a single dropped tray detection so the overlay doesn't blink on
        // a stable scene. Kept at 1 frame so the stale tray box (and the pill
        // centroids gated by it) clear almost immediately when the camera moves
        // away — a longer hold left a visible ~0.5 s ghost of the old box. The
        // count is separately protected from a one-frame drop by CountStabilizer,
        // so a short hold is enough.
        private const val TRAY_HOLD_FRAMES = 1

        // Reject a "tray" whose bbox covers at least this fraction of the frame —
        // a background surface (green table) fills the frame; a real tray is a
        // bounded object. Tunable from the on-device "TrayGate coverage=" logs.
        private const val TRAY_MAX_FRAME_COVERAGE = 0.75f
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

            // Only pill consumes the NHWC /255 float buffer now.
            // Tray (semantic seg) and glove (binary classifier) both take the
            // *letterboxed Bitmap* directly and do their own internal
            // resize + [0, 255] rescale + in-graph ImageNet normalization.
            val pillBuf = pre.rgbNormalized.duplicateRewound()

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
                val trayDeferred = traySegDetector?.let { detector ->
                    async(Dispatchers.Default) {
                        detector.detect(
                            letterboxedBitmap = pre.letterboxed,
                            scaleInfo640 = scaleInfo,
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
                        letterboxedBitmap = pre.letterboxed,
                        scaleInfo640 = scaleInfo,
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
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

            // ── Tray temporal hold (anti-flicker) ─────────────────────────────
            // Bridge brief tray-seg misses so the overlay and the pill gate that
            // depends on it don't flicker on/off. Clears after TRAY_HOLD_FRAMES
            // consecutive misses so the tray still disappears when you move away.
            if (trayDetections.isNotEmpty()) {
                heldTrayDetections = trayDetections
                trayMissFrames = 0
            } else if (trayMissFrames < TRAY_HOLD_FRAMES) {
                trayMissFrames++
                trayDetections = heldTrayDetections
            } else {
                heldTrayDetections = emptyList()
            }

            // ── STEP 2b: Detect tray color ────────────────────────────────────────
            // Always runs when trays are present — the classification popup is gated
            // in processDetections (isTrayColorDetectionEnabled), not here. Keeping
            // detection unconditional avoids a main-thread / background-thread race
            // where the flag update from setHazardousTransaction() might not be
            // visible to this Dispatchers.Default coroutine yet.
            // Each tray crop is downsized to 64×64, so this adds < 2 ms per tray.
            logger.d("PillAnalyzer step2b: trays=${trayDetections.size} shouldDetectTrayColor=${shouldDetectTrayColor()}")
            if (trayDetections.isNotEmpty()) {
                trayDetections = trayDetections.map { tray ->
                    tray.copy(trayColor = TrayColorDetector.detect(originalBitmap, tray.rect))
                }
                logger.d("Color detection ran → ${trayDetections.map { it.trayColor.label }}")
            }

            // ── "Complete product" gate ───────────────────────────────────────
            // A valid tray = BOTH a tray AND a chute detected in the frame.
            // Anything less (tray-only, chute-only, or neither) is NOT a tray at
            // all: no pill count AND no tray/chute overlay surfaced to the UI.
            // This is why a blank/chute-less scene shows nothing — a lone "tray"
            // detection without its chute is not a complete product.
            val trayCount = trayDetections.count { it.cls == TrayClass.TRAY }
            val chuteCount = trayDetections.count { it.cls == TrayClass.CHUTE }

            // Geometric guard: a background surface fills the frame, whereas a
            // real tray is a bounded object inside it. Reject a "tray" whose bbox
            // covers too much of the frame — that's the surface, not a tray.
            val frameArea = (originalWidth.toFloat() * originalHeight.toFloat()).coerceAtLeast(1f)
            val trayCoverage = trayDetections
                .filter { it.cls == TrayClass.TRAY }
                .maxOfOrNull { (it.rect.width() * it.rect.height()) / frameArea } ?: 0f
            val trayFillsFrame = trayCoverage >= TRAY_MAX_FRAME_COVERAGE

            val isCompleteTray = trayCount > 0 && chuteCount > 0 && !trayFillsFrame
            val displayTrayDetections = if (isCompleteTray) trayDetections else emptyList()
            logger.i("TrayGate — trayCount=$trayCount chuteCount=$chuteCount coverage=${"%.2f".format(trayCoverage)} fillsFrame=$trayFillsFrame complete=$isCompleteTray")

            // ── STEP 3: Postprocess pill output ───────────────────────────────
            val pillsInTray: List<Detection>
            if (!pillSucceeded) {
                logger.i("PillFilter — inference failed")
                pillsInTray = emptyList()
            } else {
                val allPills = Postprocessor.decode(
                    outputs = pillOutputs,
                    confThreshold = PRE_NMS_SCORE_FLOOR,
                    scale = scaleInfo.scale,
                    padX = scaleInfo.padX,
                    padY = scaleInfo.padY
                )
                val pillsAfterNms = NMS.run(allPills, iouThreshold = PILL_NMS_IOU)
                val confirmedPills = pillTracker.update(pillsAfterNms)
                logger.i("PillFilter — decoded=${allPills.size} afterNMS=${pillsAfterNms.size} confirmed=${confirmedPills.size} trayDets=$trayCount chuteDets=$chuteCount")

                // Pill counting GATE — only count once BOTH a tray AND a chute
                // are detected in the same frame (co-occurrence). Both models
                // keep running every frame; this gates only the pill RESULT, not
                // inference. Any partial scene (tray-only, chute-only, or neither)
                // is treated as "not ready" → zero pills, no markers drawn.
                val gateOpen = isCompleteTray
                val inScene = if (!gateOpen) {
                    emptyList()
                } else {
                    confirmedPills.filter { pill ->
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
            }

            // Displayed count is smoothed twice: median over the recent window,
            // then a latch requiring consecutive agreement. Markers stay live.
            val countedPills = countStabilizer.update(pillsInTray.size)

            val totalMs = System.currentTimeMillis() - overallStart
            // Class breakdown is metadata only — all three classes count as one pill.
            val classBreakdown = pillsInTray
                .groupingBy { det -> Postprocessor.PILL_CLASS_NAMES.getOrElse(det.classId) { "cls${det.classId}" } }
                .eachCount()
            logger.i(
                "Frame ${originalWidth}x${originalHeight} | trays=${trayDetections.size} " +
                        "counted=$countedPills classes=$classBreakdown gloves=${gloveDetections.size} " +
                        "pill=${pillInferenceMs}ms parallel=${parallelMs}ms total=${totalMs}ms"
            )

            if (runGloveThisFrame) {
                performanceLogger?.logInference(
                    modelName = "Glove Detection (YOLOX-Nano LReLU 320)",
                    inferenceTimeMs = parallelMs.toLong(),
                    preprocessTimeMs = 0,
                    postprocessTimeMs = 0,
                    detectionCount = gloveDetections.size
                )
                val summary = if (gloveDetections.isNotEmpty()) {
                    gloveDetections.joinToString { "${it.className}(${(it.confidence * 100).toInt()}%)" }
                } else {
                    "none"
                }
                logger.i("GLOVE_FRAME — $summary")
            }

            // ── STEP 4: Callback ──────────────────────────────────────────────
            onResult(
                countedPills,
                pillsInTray,
                displayTrayDetections,
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
        // Drop all tracks so the new scene re-confirms pills from scratch
        // (no stale rects matching the new scene).
        pillTracker.reset()
        // Clear anti-flicker smoothing so the new scene starts fresh (no stale
        // tray held, no carried-over count).
        heldTrayDetections = emptyList()
        trayMissFrames = 0
        countStabilizer.reset()
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
                // Output buffers are direct ByteBuffers reused across frames —
                // rewind so TFLite writes from position 0 each call.
                pillOutputs.clsBuffers[i].rewind()
                pillOutputs.regBuffers[i].rewind()
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

    private fun ByteBuffer.duplicateRewound(): ByteBuffer = duplicate().apply {
        order(this@duplicateRewound.order())
        rewind()
    }
}