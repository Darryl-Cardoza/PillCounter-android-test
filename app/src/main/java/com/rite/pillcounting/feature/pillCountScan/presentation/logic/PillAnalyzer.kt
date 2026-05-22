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
 *   1. Pre-process — letterbox camera frame to 640×640
 *   2. Tray, pill, (optional) glove inference — IN PARALLEL on Dispatchers.Default,
 *      each with its own ByteBuffer view of the same underlying memory.
 *      Each Interpreter has its own GpuDelegate so they don't interfere.
 *   3. Postprocess pill output and filter to pills inside any tray
 *   4. Callback to UI
 *
 * Parallelising the three inferences cuts steady-state per-frame time roughly to
 * max(tray, pill, glove) instead of their sum.
 */
class PillAnalyzer(
    private val pillInterpreter: Interpreter,
    private val trayInterpreter: Interpreter,
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

    // Glove cadence: run glove inference EVERY frame until we've seen at least one
    // glove-class detection (so the banner appears within one frame after the operator
    // puts their hands in view). After that, rate-limit to GLOVE_STEADY_INTERVAL_MS so
    // we don't burn weak-GPU budget on a state that changes slowly.
    private var lastGloveRunMs = 0L
    private var hasDetectedAnyGlove = false
    private var cachedGloveDetections: List<GloveDetection> = emptyList()

    // Reusable inference output buffers. Allocating these per-frame (~1MB of floats
    // for the tray detector alone) shows up as GC pressure on weak devices.
    private val pillOutputBuf: Array<Array<FloatArray>> by lazy {
        val s = pillInterpreter.getOutputTensor(0).shape()
        Array(s[0]) { Array(s[1]) { FloatArray(s[2]) } }
    }
    private val trayDetOutputBuf by lazy { TrayDetector.allocateDetOutput() }
    private val trayProtoOutputBuf by lazy { TrayDetector.allocateProtoOutput() }
    // Only allocated when gloveInterpreter is present; safe because gloveOutputBuf
    // is only accessed inside the runGloveThisFrame branch which guards on non-null.
    private val gloveOutputBuf by lazy { GloveDetector.allocateOutput(gloveInterpreter!!) }

    companion object {
        // Run every frame until first glove detection arrives; afterwards only every
        // GLOVE_STEADY_INTERVAL_MS so we keep the GPU free for pill + tray.
        private const val GLOVE_STEADY_INTERVAL_MS = 400L
    }

    suspend fun analyze(imageProxy: ImageProxy) {
        val overallStart = System.currentTimeMillis()
        var trackingBitmap: Bitmap? = null

        try {
            // ── STEP 1: Pre-process ───────────────────────────────────────────
            val (inputBuffer, bitmap640, originalBitmap) = ImagePreprocessor.preprocess(imageProxy)
            trackingBitmap = originalBitmap

            val scaleInfo = Letterbox.currentScaleInfo
                ?: throw IllegalStateException("Letterbox.currentScaleInfo missing after preprocess")

            val originalWidth = imageProxy.width
            val originalHeight = imageProxy.height

            // Independent ByteBuffer views over the same memory. duplicate() is cheap
            // (no memcpy) and lets each Interpreter advance its own read pointer.
            val trayBuf = inputBuffer.duplicateRewound()
            val pillBuf = inputBuffer.duplicateRewound()
            val gloveBuf = inputBuffer.duplicateRewound()

            // Glove cadence:
            //   • If session has been locked off (high-conf glove seen) — skip entirely.
            //   • Before the first detection — run every frame for fast initial pickup.
            //   • After first detection — rate-limit to GLOVE_STEADY_INTERVAL_MS.
            val now = System.currentTimeMillis()
            val interval = if (hasDetectedAnyGlove) GLOVE_STEADY_INTERVAL_MS else 0L
            val runGloveThisFrame = gloveInterpreter != null &&
                    shouldRunGloveDetection() &&
                    (now - lastGloveRunMs >= interval)
            if (runGloveThisFrame) lastGloveRunMs = now

            // ── STEP 2: Run models in parallel ────────────────────────────────
            val trayDetections: List<TrayDetection>
            val pillRaw: Array<FloatArray>?
            val gloveDetections: List<GloveDetection>

            val parallelStart = System.currentTimeMillis()
            coroutineScope {
                val trayDeferred = async(Dispatchers.Default) {
                    TrayDetector.detect(
                        interpreter = trayInterpreter,
                        inputBuffer = trayBuf,
                        scaleInfo = scaleInfo,
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                        detOutput = trayDetOutputBuf,
                        protoOutput = trayProtoOutputBuf
                    )
                }
                val pillDeferred = async(Dispatchers.Default) {
                    runPillInferenceRaw(pillBuf)
                }
                val gloveDeferred = if (runGloveThisFrame) async(Dispatchers.Default) {
                    GloveDetector.detect(
                        interpreter = gloveInterpreter!!,
                        inputBuffer = gloveBuf,
                        scaleInfo = scaleInfo,
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                        rawOutput = gloveOutputBuf
                    )
                } else null

                trayDetections = trayDeferred.await()
                pillRaw = pillDeferred.await()
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

            // ── STEP 3: Postprocess pill output (only if a tray was found) ────
            val pillsInTray: List<Detection>
            if (trayDetections.isEmpty() || pillRaw == null) {
                pillsInTray = emptyList()
            } else {
                val allPills = Postprocessor.decode(
                    raw = pillRaw,
                    confThreshold = 0.70f,
                    scale = scaleInfo.scale,
                    padX = scaleInfo.padX,
                    padY = scaleInfo.padY
                )
                val pillsAfterNms = NMS.run(allPills, iouThreshold = 0.80f)
                pillsInTray = pillsAfterNms.filter { pill ->
                    val cx = pill.rect.centerX().toInt()
                    val cy = pill.rect.centerY().toInt()
                    trayDetections.any { tray -> tray.containsPoint(cx, cy) }
                }
            }

            val totalMs = System.currentTimeMillis() - overallStart
            logger.i(
                "Frame ${originalWidth}x${originalHeight} | trays=${trayDetections.size} " +
                        "pills=${pillsInTray.size} gloves=${gloveDetections.size} " +
                        "parallel=${parallelMs}ms total=${totalMs}ms"
            )

            if (runGloveThisFrame) {
                performanceLogger?.logInference(
                    modelName = "Glove Detection (YOLOv11)",
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
                pillsInTray.size,
                pillsInTray,
                trayDetections,
                gloveDetections,
                originalBitmap,
                Matrix(),
                originalWidth,
                originalHeight
            )

            bitmap640.recycle()

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
    }

    /**
     * Runs the pill interpreter into the reusable [pillOutputBuf] and returns the
     * first batch (`[channels][anchors]`) view. Returns null on failure.
     */
    private fun runPillInferenceRaw(buf: ByteBuffer): Array<FloatArray>? {
        return try {
            pillInterpreter.run(buf, pillOutputBuf)
            pillOutputBuf[0]
        } catch (e: Exception) {
            logger.e("Pill inference failed", e)
            null
        }
    }

    private fun ByteBuffer.duplicateRewound(): ByteBuffer = duplicate().apply {
        order(this@duplicateRewound.order())
        rewind()
    }
}
