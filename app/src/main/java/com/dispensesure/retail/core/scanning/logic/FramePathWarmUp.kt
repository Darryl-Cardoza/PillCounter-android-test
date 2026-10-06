package com.dispensesure.retail.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

/**
 * Runs the analyzer's per-frame path on a synthetic frame so the first camera frame starts warm.
 * Call only under the loader's lock, before the pill interpreter is published.
 */
internal object FramePathWarmUp {

    private const val TAG = "LoadModel"
    private const val FRAME_W = 1440
    private const val FRAME_H = 1080

    fun run(pill: Interpreter) {
        val t0 = System.currentTimeMillis()
        val trayRect = Rect(FRAME_W / 8, FRAME_H / 8, FRAME_W * 7 / 8, FRAME_H * 7 / 8)
        val frame = syntheticFrame(trayRect)
        val openCvWarmed = try {
            val crop = Letterbox.preprocess(frame, srcRect = trayRect)
            val cropInfo = Letterbox.currentScaleInfo

            warmPillInference(pill, crop, cropInfo)
            warmTracking()

            // Full-frame letterbox is the path used until a complete tray is seen,
            // and the motion estimator reads it.
            val full = Letterbox.preprocess(frame)
            warmOpenCv(frame, full, trayRect)
        } finally {
            frame.recycle()
        }
        Log.i(
            TAG,
            "Frame-path warm-up done in ${System.currentTimeMillis() - t0}ms " +
                "(OpenCV ${if (openCvWarmed) "warmed" else "failed"})"
        )
    }

    /** A blue "tray" on a lighter surface, so the colour detector has something to classify. */
    private fun syntheticFrame(trayRect: Rect): Bitmap {
        val bitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(200, 190, 170))
        val paint = Paint().apply { color = Color.rgb(30, 70, 200) }
        canvas.drawRect(trayRect, paint)
        return bitmap
    }

    private fun warmPillInference(
        pill: Interpreter,
        crop: Bitmap,
        info: Letterbox.ScaleInfo?
    ) {
        val outputs = Postprocessor.allocateOutputs(pill)
        val outMap = HashMap<Int, Any>(6)
        for (i in outputs.clsIdx.indices) {
            outMap[outputs.clsIdx[i]] = outputs.clsBuffers[i]
            outMap[outputs.regIdx[i]] = outputs.regBuffers[i]
        }
        repeat(PillDetectionModelLoader.WARMUP_RUNS) { idx ->
            val t = System.currentTimeMillis()
            val input: ByteBuffer = ImagePreprocessor.pillInput(crop)
            input.rewind()
            for (i in outputs.clsIdx.indices) {
                outputs.clsBuffers[i].rewind()
                outputs.regBuffers[i].rewind()
            }
            pill.runForMultipleInputsOutputs(arrayOf(input), outMap)
            Postprocessor.decode(
                outputs = outputs,
                confThreshold = PillAnalyzer.PRE_NMS_SCORE_FLOOR,
                scale = info?.scale ?: 1f,
                padX = info?.padX ?: 0f,
                padY = info?.padY ?: 0f,
                offsetX = info?.offsetX ?: 0f,
                offsetY = info?.offsetY ?: 0f
            )
            Log.i(
                TAG,
                "Pill frame-path warm-up run ${idx + 1}/${PillDetectionModelLoader.WARMUP_RUNS}: " +
                    "${System.currentTimeMillis() - t}ms"
            )
        }
    }

    /** NMS, tracker and stabilizer on a handful of fake pills, so their code is loaded and compiled. */
    private fun warmTracking() {
        val pills = List(12) { i ->
            val x = 40f + (i % 4) * 60f
            val y = 40f + (i / 4) * 60f
            Detection(RectF(x, y, x + 35f, y + 35f), confidence = 0.9f)
        }
        val kept = NMS.run(pills, iouThreshold = PillAnalyzer.PILL_NMS_IOU)
        val tracker = PillTracker()
        val stabilizer = CountStabilizer()
        repeat(3) {
            val confirmed = tracker.update(kept, null)
            stabilizer.update(confirmed.size)
        }
    }

    /** Motion registration and colour classification both go through OpenCV. True only if both ran. */
    private fun warmOpenCv(frame: Bitmap, letterboxed: Bitmap, trayRect: Rect): Boolean {
        val motion = CameraMotionEstimator()
        val motionWarmed = try {
            motion.estimate(letterboxed)
            motion.estimate(letterboxed)
            motion.isAvailable
        } finally {
            motion.release()
        }
        // The synthetic tray is blue, so any other answer means detection failed.
        val colourWarmed = TrayColorDetector.detect(frame, RectF(trayRect)) == TrayColor.BLUE
        return motionWarmed && colourWarmed
    }
}
