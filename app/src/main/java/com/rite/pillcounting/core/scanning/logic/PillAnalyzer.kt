package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Runs the three-model pipeline on every camera frame:
 *
 *   1. Pre-process — letterbox the whole frame to 640×640.
 *   2. Tray + (optional) glove inference — IN PARALLEL on Dispatchers.Default.
 *      Both take the letterboxed Bitmap and resize it internally. Each
 *      Interpreter has its own GpuDelegate.
 *   3. Pill inference on the TRAY CROP. Once a complete tray (tray + chute) is
 *      in view, only the tray's bounding box is letterboxed to 640×640 NHWC
 *      RGB-/255 and sent to the pill model — the chute and the rest of the
 *      frame never reach it, and pills land near the ~35 px scale the model
 *      was trained at instead of shrinking with the whole frame (deploy
 *      contract: "feed the detector the tray crop, not the whole frame").
 *      Without a complete tray the full-frame letterbox is used.
 *   4. Postprocess pill output (boxes mapped back to full-frame coordinates)
 *      and keep pills whose centre is on the tray mask dilated by half a pill.
 *   5. Callback to UI.
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
    // Frame-to-frame camera motion, fed to the tracker so a hand-held pan does
    // not break every pill's association at once.
    private val motionEstimator = CameraMotionEstimator()

    // ── Tray gate hysteresis state ─────────────────────────────────────────
    // The last COMPLETE tray set (tray + chute) and how many consecutive frames
    // have failed to reproduce it. See the gate section in analyze().
    private var heldTrayDetections: List<TrayDetection> = emptyList()
    private var incompleteFrames = 0

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
        private const val PRE_NMS_SCORE_FLOOR = PillTracker.KEEP_SCORE
        // Deploy contract nms_iou. Measured in the training repo: no two distinct
        // pills overlap above IoU 0.5, so a second box above it is a duplicate on
        // the same pill — 0.6 let those through and inflated the count.
        private const val PILL_NMS_IOU = 0.50f
        // Reference decoder ceilings: at most this many candidates enter NMS and
        // this many detections leave it (nms_top_k / keep_top_k).
        private const val NMS_TOP_K = 1500
        private const val KEEP_TOP_K = 500
        // Deploy contract mask_dilate_pill_fraction: the tray mask is treated as
        // dilated by this × the median pill side, so a pill whose centre sits on
        // the segmented rim doesn't blink in and out as the boundary jitters.
        private const val MASK_DILATE_PILL_FRACTION = 0.5f

        // ── Tray gate hysteresis ────────────────────────────────────────────
        // The overlay box is held for at most TRAY_HOLD_FRAMES incomplete frames
        // so it clears almost immediately when the camera moves away (a longer
        // hold left a visible ghost box). The pill GATE is held longer: the
        // segmenter drops the chute (or the tray) for a frame or two on a steady
        // scene, and every such drop used to zero the pill result — that was the
        // on-screen flicker. While the gate is held, the last complete tray set
        // keeps driving the crop and the mask filter.
        // GATE_CLOSE_FRAMES is measured, not picked: 6 covers the longest chute
        // dropout seen in the on-device TrayGate logs on a steady scene.
        private const val TRAY_HOLD_FRAMES = 1
        private const val GATE_CLOSE_FRAMES = 6

        // Reject a "tray" whose bbox covers at least this fraction of the frame —
        // a background surface (green table) fills the frame; a real tray is a
        // bounded object. Tunable from the on-device "TrayGate coverage=" logs.
        private const val TRAY_MAX_FRAME_COVERAGE = 0.75f

        // ── Tray crop for the pill model ────────────────────────────────────
        // Margin added around the tray bbox per side, as a fraction of the bbox
        // size, so a pill sitting on the rim (the deploy contract dilates the
        // tray mask by half a pill) stays whole inside the crop.
        private const val TRAY_CROP_MARGIN = 0.05f
        // A crop narrower than this (px) means the tray is too far away to
        // count from; use the full frame rather than upscale noise.
        private const val TRAY_CROP_MIN_SIDE = 64

        /**
         * The deploy contract's mask rule, decided by vote: a pill counts when
         * more of the nine samples around its centre (spaced [dilate] px, half a
         * median pill side) land on TRAY than on CHUTE.
         *
         * Two rules were tried before this one and each failed at the chute wall,
         * where the two classes abut and the argmax boundary wanders 2–4 frame px
         * per frame:
         *
         *  - Tray mask, minus a veto on the raw chute pixel under the centre. The
         *    veto cancelled the dilation exactly at the wall, so every pill
         *    resting on the tray side blinked in and out and the count oscillated.
         *  - Dilated tray mask alone, no chute test. Dilation grows the tray half
         *    a pill in *every* direction, including into the chute, so the first
         *    row of pills sitting in the chute against the wall was counted.
         *
         * Voting fixes both because it moves the decision line off the boundary
         * and onto the midpoint between the two classes. A pill on the tray at
         * the wall has most of its samples on tray and counts; a pill in the
         * chute at the wall has most on chute and does not; a pill on the tray's
         * outer rim has some samples on background and none on chute, so tray
         * still wins and the dilation keeps doing its job. A 2–4 px wander moves
         * at most one vote of nine, which can only flip a pill already sitting on
         * the midline.
         */
        internal fun isOnTray(cx: Float, cy: Float, trays: List<TrayDetection>, dilate: Float): Boolean {
            var trayVotes = 0
            var chuteVotes = 0
            for (t in trays) {
                val votes = t.votesWithin(cx, cy, dilate)
                when (t.cls) {
                    TrayClass.TRAY -> if (votes > trayVotes) trayVotes = votes
                    TrayClass.CHUTE -> if (votes > chuteVotes) chuteVotes = votes
                }
            }
            return trayVotes > chuteVotes
        }
    }

    suspend fun analyze(imageProxy: ImageProxy) {
        val overallStart = System.currentTimeMillis()
        var trackingBitmap: Bitmap? = null

        try {
            // ── STEP 1: Pre-process ───────────────────────────────────────────
            val frame = ImagePreprocessor.prepareFrame(imageProxy)
            val originalBitmap = frame.original
            trackingBitmap = originalBitmap

            val scaleInfo = Letterbox.currentScaleInfo
                ?: throw IllegalStateException("Letterbox.currentScaleInfo missing after preprocess")

            val originalWidth = imageProxy.width
            val originalHeight = imageProxy.height

            val now = System.currentTimeMillis()
            val interval = if (hasDetectedAnyGlove) GLOVE_STEADY_INTERVAL_MS else 0L
            val runGloveThisFrame = gloveInterpreter != null &&
                    shouldRunGloveDetection() &&
                    (now - lastGloveRunMs >= interval)
            if (runGloveThisFrame) lastGloveRunMs = now

            // ── STEP 2: Tray + glove in parallel ──────────────────────────────
            // Tray runs every frame so the UI tracks the camera live — when
            // the user moves the phone away, the tray bbox and the pill
            // centroid markers must disappear in the next frame, not linger
            // behind a stale cache. Pill inference waits for the tray result
            // because its input is the tray crop (STEP 3).
            var trayDetections: List<TrayDetection> = emptyList()
            val gloveDetections: List<GloveDetection>
            val cameraMotion: CameraMotionEstimator.Shift?

            val parallelStart = System.currentTimeMillis()
            coroutineScope {
                // Registers this frame's full letterbox against the previous one.
                // Reads frame.letterboxed only, so it can share the frame with
                // tray and glove; it must finish before STEP 3 reuses that bitmap.
                val motionDeferred = async(Dispatchers.Default) {
                    motionEstimator.estimate(frame.letterboxed)
                }
                val trayDeferred = traySegDetector?.let { detector ->
                    async(Dispatchers.Default) {
                        detector.detect(
                            letterboxedBitmap = frame.letterboxed,
                            scaleInfo640 = scaleInfo,
                            originalWidth = originalWidth,
                            originalHeight = originalHeight
                        )
                    }
                }
                val gloveDeferred = if (runGloveThisFrame) async(Dispatchers.Default) {
                    GloveDetector.detect(
                        interpreter = gloveInterpreter!!,
                        letterboxedBitmap = frame.letterboxed,
                        scaleInfo640 = scaleInfo,
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                    )
                } else null

                if (trayDeferred != null) trayDetections = trayDeferred.await()
                cameraMotion = motionDeferred.await()
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
            // Letterbox pixels → frame pixels (the pad cancels in a difference).
            val cameraShift = cameraMotion?.let { PointF(it.dx / scaleInfo.scale, it.dy / scaleInfo.scale) }

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

            // Hysteresis: open on the first complete frame, close only after
            // GATE_CLOSE_FRAMES consecutive incomplete ones. gateTrays drives the
            // crop and the mask filter — this frame's set when it is complete,
            // otherwise the last complete one.
            if (isCompleteTray) {
                heldTrayDetections = trayDetections
                incompleteFrames = 0
            } else {
                incompleteFrames++
            }
            val gateOpen = heldTrayDetections.isNotEmpty() && incompleteFrames <= GATE_CLOSE_FRAMES
            if (!gateOpen) heldTrayDetections = emptyList()
            val gateTrays = heldTrayDetections
            val displayTrayDetections =
                if (gateOpen && incompleteFrames <= TRAY_HOLD_FRAMES) gateTrays else emptyList()
            logger.i("TrayGate — trayCount=$trayCount chuteCount=$chuteCount coverage=${"%.2f".format(trayCoverage)} fillsFrame=$trayFillsFrame complete=$isCompleteTray gateOpen=$gateOpen incomplete=$incompleteFrames")

            // ── STEP 3: Pill inference on the tray crop ───────────────────────
            // With a complete tray in view only the tray's bounding box goes to
            // the pill model: the chute and everything else in the frame are
            // cropped away, and the pills fill the 640 canvas at the scale the
            // model was trained on. Without a complete tray (or with the tray
            // model disabled) the full-frame letterbox is used as before.
            val pillRegion = if (gateOpen) {
                trayCropRegion(gateTrays, originalWidth, originalHeight)
            } else null
            val pillBitmap: Bitmap
            val pillScaleInfo: Letterbox.ScaleInfo
            if (pillRegion != null) {
                // Reuses Letterbox's cached 640 canvas — tray and glove have
                // already consumed the full-frame letterbox by this point.
                pillBitmap = Letterbox.preprocess(originalBitmap, srcRect = pillRegion)
                pillScaleInfo = Letterbox.currentScaleInfo ?: scaleInfo
            } else {
                pillBitmap = frame.letterboxed
                pillScaleInfo = scaleInfo
            }
            // Debug builds only (no-op otherwise): keep a sample of the exact
            // images handed to the pill model for inspection off-device.
            ModelInputDump.maybeSave(
                pillBitmap,
                pillRegion?.let { "crop_${it.width()}x${it.height()}" } ?: "full"
            )
            val pillInferenceStart = System.currentTimeMillis()
            val pillSucceeded = withContext(Dispatchers.Default) {
                runPillInference(ImagePreprocessor.pillInput(pillBitmap))
            }
            val pillInferenceMs = System.currentTimeMillis() - pillInferenceStart

            // ── STEP 4: Postprocess pill output ───────────────────────────────
            val pillsInTray: List<Detection>
            var visibleOnTray = 0
            // Post-NMS detections the mask rule rejected this frame — the chute,
            // the rim margin of the crop, or a segmentation boundary that moved.
            var offTray = 0
            if (!pillSucceeded) {
                logger.i("PillFilter — inference failed")
                pillsInTray = emptyList()
            } else {
                val allPills = Postprocessor.decode(
                    outputs = pillOutputs,
                    confThreshold = PRE_NMS_SCORE_FLOOR,
                    scale = pillScaleInfo.scale,
                    padX = pillScaleInfo.padX,
                    padY = pillScaleInfo.padY,
                    offsetX = pillScaleInfo.offsetX,
                    offsetY = pillScaleInfo.offsetY
                )
                val candidates = if (allPills.size > NMS_TOP_K) {
                    allPills.sortedByDescending { it.confidence }.take(NMS_TOP_K)
                } else allPills
                val pillsAfterNms = NMS.run(candidates, iouThreshold = PILL_NMS_IOU).take(KEEP_TOP_K)
                val confirmedPills = pillTracker.update(pillsAfterNms, cameraShift)
                logger.i("PillFilter — decoded=${allPills.size} afterNMS=${pillsAfterNms.size} confirmed=${confirmedPills.size} trayDets=$trayCount chuteDets=$chuteCount")

                // Pill counting GATE — the pill RESULT (not inference) is gated on
                // the hysteretic tray gate above: a complete tray (tray + chute)
                // must have been seen within the last GATE_CLOSE_FRAMES frames.
                // Deploy-contract mask rule: a pill counts when its centre lies on
                // the tray mask dilated by half a pill side (see isOnTray).
                if (!gateOpen) {
                    pillsInTray = emptyList()
                } else {
                    val dilate = MASK_DILATE_PILL_FRACTION * medianSide(confirmedPills)
                    val onTray = { pill: Detection ->
                        isOnTray(pill.rect.centerX(), pill.rect.centerY(), gateTrays, dilate)
                    }
                    pillsInTray = confirmedPills.filter(onTray)
                    // What the detector actually sees on the tray this frame, at the
                    // score a track can survive on. A coasting or duplicate track has
                    // no detection under it, so the count is never allowed above this.
                    visibleOnTray = pillsAfterNms.count { it.confidence >= PillTracker.KEEP_SCORE && onTray(it) }
                    offTray = pillsAfterNms.size - visibleOnTray
                }
            }

            // Displayed count is smoothed twice: median over the recent window,
            // then a latch requiring consecutive agreement. Markers stay live.
            // Per-frame count = confirmed tracks on the tray, capped by the
            // detections visible on the tray: tracks add hysteresis, never pills.
            val countedPills = countStabilizer.update(min(pillsInTray.size, visibleOnTray))
            // Markers are what the user counts by eye, so never show more dots
            // than the number. Highest confidence survives the clip.
            val markers = pillsInTray.sortedByDescending { it.confidence }.take(countedPills)

            val totalMs = System.currentTimeMillis() - overallStart
            // Class breakdown is metadata only — all three classes count as one pill.
            val classBreakdown = pillsInTray
                .groupingBy { det -> Postprocessor.PILL_CLASS_NAMES.getOrElse(det.classId) { "cls${det.classId}" } }
                .eachCount()
            logger.i(
                "Frame ${originalWidth}x${originalHeight} | trays=${trayDetections.size} " +
                        "counted=$countedPills classes=$classBreakdown gloves=${gloveDetections.size} " +
                        "pillInput=${pillRegion?.let { "${it.width()}x${it.height()}" } ?: "full"} " +
                        "visible=$visibleOnTray offTray=$offTray tracked=${pillsInTray.size} " +
                        "motion=${cameraMotion?.let { "%.1f,%.1f r=%.2f".format(it.dx, it.dy, it.response) } ?: "n/a"} " +
                        "pill=${pillInferenceMs}ms trayGlove=${parallelMs}ms total=${totalMs}ms"
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

            // ── STEP 5: Callback ──────────────────────────────────────────────
            onResult(
                countedPills,
                markers,
                displayTrayDetections,
                gloveDetections,
                originalBitmap,
                Matrix(),
                originalWidth,
                originalHeight
            )

            // frame.letterboxed is owned and reused by Letterbox — do NOT recycle.

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
        incompleteFrames = 0
        countStabilizer.reset()
        motionEstimator.reset()
    }

    /** Median of sqrt(w·h) over [dets]; 0 when there are none. */
    private fun medianSide(dets: List<Detection>): Float {
        if (dets.isEmpty()) return 0f
        val sides = dets.map { sqrt(it.rect.width() * it.rect.height()) }.sorted()
        return sides[sides.size / 2]
    }

    /**
     * The frame region handed to the pill model: the TRAY bounding box (the
     * chute is a separate class and lies outside it), grown by [TRAY_CROP_MARGIN]
     * per side and clamped to the frame. Null when there is no tray or the
     * crop is too small to be worth upscaling.
     */
    internal fun trayCropRegion(trays: List<TrayDetection>, frameW: Int, frameH: Int): Rect? {
        val tray = trays
            .filter { it.cls == TrayClass.TRAY }
            .maxByOrNull { it.rect.width() * it.rect.height() } ?: return null
        val mx = tray.rect.width() * TRAY_CROP_MARGIN
        val my = tray.rect.height() * TRAY_CROP_MARGIN
        val region = Rect(
            floor(tray.rect.left - mx).toInt().coerceIn(0, frameW),
            floor(tray.rect.top - my).toInt().coerceIn(0, frameH),
            ceil(tray.rect.right + mx).toInt().coerceIn(0, frameW),
            ceil(tray.rect.bottom + my).toInt().coerceIn(0, frameH)
        )
        return if (region.width() >= TRAY_CROP_MIN_SIDE && region.height() >= TRAY_CROP_MIN_SIDE) region else null
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
}
