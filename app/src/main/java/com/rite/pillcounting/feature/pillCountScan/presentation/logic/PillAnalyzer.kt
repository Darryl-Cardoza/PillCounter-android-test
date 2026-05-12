package com.rite.pillcounting.feature.pillCountScan.presentation.logic

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import org.tensorflow.lite.Interpreter

/**
 * Runs the dual-model pipeline on every camera frame:
 *
 *   1. Pre-process  — letterbox camera frame to 640×640
 *   2. Tray model   — segmentation → per-tray boolean pixel masks
 *   3. Pill model   —detection    → pill bounding boxes
 *   4. Filter       — keep pills whose centre pixel is inside any tray mask
 *                     (mirrors Python: tray_mask[cy][cx] > 0.5)
 *   5. Callback     — emit filtered pills + tray detections to the UI
 */
class PillAnalyzer(
    private val pillInterpreter: Interpreter,
    private val trayInterpreter: Interpreter,
    private val gloveInterpreter: Interpreter,
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

    private val logger = AppLogger("PillAnalyzer")

    fun analyze(imageProxy: ImageProxy) {
        val overallStart = System.currentTimeMillis()
        var trackingBitmap: Bitmap? = null

        logger.i(
            "Frame — ${imageProxy.width}×${imageProxy.height} " +
                    "rot=${imageProxy.imageInfo.rotationDegrees}"
        )

        try {
            // ── STEP 1: Pre-process ───────────────────────────────────────────
            val (inputBuffer, bitmap640, originalBitmap) = ImagePreprocessor.preprocess(imageProxy)
            trackingBitmap = originalBitmap

            val scaleInfo = Letterbox.currentScaleInfo
                ?: throw IllegalStateException("Letterbox.currentScaleInfo missing after preprocess")

            // Original camera frame dimensions (before letterboxing)
            val originalWidth  = imageProxy.width
            val originalHeight = imageProxy.height

            logger.i(
                "[Letterbox] scale=${scaleInfo.scale} " +
                        "padX=${scaleInfo.padX} padY=${scaleInfo.padY} " +
                        "original=${originalWidth}x${originalHeight}"
            )

            // ── STEP 2: Tray segmentation ─────────────────────────────────────
            val trayStart = System.currentTimeMillis()

            val trayDetections = TrayDetector.detect(
                interpreter    = trayInterpreter,
                inputBuffer    = inputBuffer,   // reuse buffer — avoids duplicate getPixels+float conversion
                scaleInfo      = scaleInfo,
                originalWidth  = originalWidth,
                originalHeight = originalHeight
            )

            logger.i(
                "[Tray] ${trayDetections.size} tray(s) | " +
                        "${System.currentTimeMillis() - trayStart} ms"
            )

            // No tray visible → report zero pills, but still emit tray list
            // (empty) so the UI clears its overlay cleanly.
            if (trayDetections.isEmpty()) {
                logger.i("[Tray] No tray detected — skipping pill inference")

                // Still run glove detection even when no tray is visible (if enabled)
                val gloveDetections = if (shouldRunGloveDetection()) {
                    inputBuffer.rewind()
                    GloveDetector.detect(
                        interpreter    = gloveInterpreter,
                        inputBuffer    = inputBuffer,
                        scaleInfo      = scaleInfo,
                        originalWidth  = originalWidth,
                        originalHeight = originalHeight
                    )
                } else {
                    logger.i("[Glove] Skipping glove detection (already detected)")
                    emptyList()
                }

                onResult(
                    0, emptyList(), emptyList(), gloveDetections,
                    originalBitmap, Matrix(), originalWidth, originalHeight
                )
                bitmap640.recycle()
                return
            }

            // ── STEP 3: Pill detection ────────────────────────────────────────
            val pillStart = System.currentTimeMillis()

            // Rewind: TFLite advances the buffer position during tray inference.
            inputBuffer.rewind()

            val outputShape = pillInterpreter.getOutputTensor(0).shape()
            val output = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
            pillInterpreter.run(inputBuffer, output)

            logger.i("[Pill inference] ${System.currentTimeMillis() - pillStart} ms")

            // Decode transposed YOLO layout [1, 5, N]
            val raw        = output[0]
            val numAnchors = raw[0].size

            val coords = Array(numAnchors) { FloatArray(4) }
            val conf   = Array(numAnchors) { FloatArray(1) }

            for (i in 0 until numAnchors) {
                coords[i][0] = raw[0][i] // cx (normalised)
                coords[i][1] = raw[1][i] // cy
                coords[i][2] = raw[2][i] // w
                coords[i][3] = raw[3][i] // h
                conf[i][0]   = raw[4][i] // confidence
            }

            val allPills = Postprocessor.decode(
                coords        = coords,
                conf          = conf,
                confThreshold = 0.70f,
                scale         = scaleInfo.scale,
                padX          = scaleInfo.padX,
                padY          = scaleInfo.padY
            )

            // ── STEP 4: NMS ───────────────────────────────────────────────────
            val pillsAfterNms = NMS.run(allPills, iouThreshold = 0.80f)

            logger.i("[Pills After NMS] ${pillsAfterNms.size} pills detected:")
            pillsAfterNms.take(10).forEachIndexed { idx, pill ->
                logger.i("  Pill[$idx] center=(${pill.rect.centerX().toInt()}, ${pill.rect.centerY().toInt()}) " +
                        "rect=${pill.rect} conf=${pill.confidence}")
            }

            // Log tray info
            trayDetections.forEachIndexed { idx, tray ->
                logger.i("[Tray[$idx]] rect=${tray.rect}")
                logger.i("[Tray[$idx]] ${tray.getMaskStats()}")
            }

            val pillsInTray = pillsAfterNms.filter { pill ->
                val cx = pill.rect.centerX().toInt()
                val cy = pill.rect.centerY().toInt()
                val isInside = trayDetections.any { tray -> tray.containsPoint(cx, cy) }

                // Log first 10 pills to debug filtering
                if (pillsAfterNms.indexOf(pill) < 10) {
                    logger.i("  Pill at ($cx, $cy) inside tray? $isInside")
                }

                isInside
            }

            logger.i(
                "[Filter] ${pillsAfterNms.size} pills → " +
                        "${pillsInTray.size} inside tray | " +
                        "total=${System.currentTimeMillis() - overallStart} ms"
            )

            // ── STEP 5: Glove detection (conditional based on state) ─────────────
            val gloveDetections = if (shouldRunGloveDetection()) {
                val gloveStart = System.currentTimeMillis()

                inputBuffer.rewind()
                val detections = GloveDetector.detect(
                    interpreter    = gloveInterpreter,
                    inputBuffer    = inputBuffer,
                    scaleInfo      = scaleInfo,
                    originalWidth  = originalWidth,
                    originalHeight = originalHeight
                )

                val gloveTime = System.currentTimeMillis() - gloveStart

                logger.i(
                    "[Glove] ${detections.size} detection(s) | " +
                            "$gloveTime ms"
                )

                // Log glove model inference
                performanceLogger?.logInference(
                    modelName = "Glove Detection (YOLOv11)",
                    inferenceTimeMs = gloveTime,
                    preprocessTimeMs = 0,
                    postprocessTimeMs = 0,
                    detectionCount = detections.size
                )

                // High-visibility log for the user
                if (detections.isNotEmpty()) {
                    val summary = detections.joinToString { "${it.className}(${(it.confidence * 100).toInt()}%)" }
                    android.util.Log.e("GLOVE_DETECTION", "🎯 FOUND: $summary")
                } else {
                    android.util.Log.d("GLOVE_DETECTION", "⚪ No gloves detected in this frame")
                }

                detections
            } else {
                logger.i("[Glove] Skipping glove detection (gloves already detected)")
                emptyList()
            }

            // ── STEP 6: Callback ──────────────────────────────────────────────
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
}