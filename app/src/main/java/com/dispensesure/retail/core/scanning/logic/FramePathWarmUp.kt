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
 * Runs the parts of the per-frame pipeline that a bare interpreter warm-up
 * leaves cold, so the first real camera frame is not the one that pays for
 * them. Measured on the first frame after a cold start: pill inference took
 * 72-106 ms against 29 ms on every later frame, and the whole frame 185-253 ms
 * against ~100 ms.
 *
 * What this touches, in the order [PillAnalyzer.analyze] uses it:
 *  - the tray-crop and full-frame [Letterbox] paths,
 *  - [ImagePreprocessor.pillInput] and the pill interpreter driven exactly as
 *    the analyzer drives it (direct-ByteBuffer outputs from
 *    [Postprocessor.allocateOutputs]), which the nested-array warm-up in
 *    [PillDetectionModelLoader] does not exercise,
 *  - [Postprocessor.decode], [NMS], [PillTracker], [CountStabilizer],
 *  - OpenCV through [CameraMotionEstimator] and [TrayColorDetector].
 *
 * Everything runs on throwaway instances and a synthetic frame, so no state
 * reaches a real analyzer. The shared [Letterbox] and [ImagePreprocessor]
 * scratch is rewritten by the first real frame before it is read.
 *
 * Not thread-safe with respect to the pill [Interpreter]: call it only while
 * holding the loader's lock, before the interpreter is published.
 */
internal object FramePathWarmUp {

    private const val TAG = "LoadModel"
    private const val FRAME_W = 1440
    private const val FRAME_H = 1080
    private const val RUNS = 3

    // Mirrors PillAnalyzer.PRE_NMS_SCORE_FLOOR / PILL_NMS_IOU; the exact values do
    // not matter here, only that the same code runs.
    private const val SCORE_FLOOR = PillTracker.KEEP_SCORE
    private const val NMS_IOU = 0.50f

    fun run(pill: Interpreter) {
        val t0 = System.currentTimeMillis()
        val frame = syntheticFrame()
        try {
            val cropRect = Rect(FRAME_W / 8, FRAME_H / 8, FRAME_W * 7 / 8, FRAME_H * 7 / 8)
            val crop = Letterbox.preprocess(frame, srcRect = cropRect)
            val cropInfo = Letterbox.currentScaleInfo

            warmPillInference(pill, crop, cropInfo)
            warmTracking()

            // Full-frame letterbox is the path used until a complete tray is seen,
            // and the motion estimator reads it.
            val full = Letterbox.preprocess(frame)
            warmOpenCv(frame, full)
        } finally {
            frame.recycle()
        }
        Log.i(TAG, "Frame-path warm-up done in ${System.currentTimeMillis() - t0}ms")
    }

    /** A blue "tray" on a lighter surface, so the colour detector has something to classify. */
    private fun syntheticFrame(): Bitmap {
        val bitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(200, 190, 170))
        val paint = Paint().apply { color = Color.rgb(30, 70, 200) }
        canvas.drawRect(FRAME_W / 8f, FRAME_H / 8f, FRAME_W * 7f / 8f, FRAME_H * 7f / 8f, paint)
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
        repeat(RUNS) { idx ->
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
                confThreshold = SCORE_FLOOR,
                scale = info?.scale ?: 1f,
                padX = info?.padX ?: 0f,
                padY = info?.padY ?: 0f,
                offsetX = info?.offsetX ?: 0f,
                offsetY = info?.offsetY ?: 0f
            )
            Log.i(TAG, "Pill frame-path warm-up run ${idx + 1}/$RUNS: ${System.currentTimeMillis() - t}ms")
        }
    }

    /** NMS, tracker and stabilizer on a handful of fake pills, so their code is loaded and compiled. */
    private fun warmTracking() {
        val pills = List(12) { i ->
            val x = 40f + (i % 4) * 60f
            val y = 40f + (i / 4) * 60f
            Detection(RectF(x, y, x + 35f, y + 35f), confidence = 0.9f)
        }
        val kept = NMS.run(pills, iouThreshold = NMS_IOU)
        val tracker = PillTracker()
        val stabilizer = CountStabilizer()
        repeat(3) {
            val confirmed = tracker.update(kept, null)
            stabilizer.update(confirmed.size)
        }
    }

    /** Motion registration and colour classification both go through OpenCV. */
    private fun warmOpenCv(frame: Bitmap, letterboxed: Bitmap) {
        val motion = CameraMotionEstimator()
        try {
            motion.estimate(letterboxed)
            motion.estimate(letterboxed)
        } finally {
            motion.reset()
        }
        TrayColorDetector.detect(
            frame,
            RectF(FRAME_W / 8f, FRAME_H / 8f, FRAME_W * 7f / 8f, FRAME_H * 7f / 8f)
        )
    }
}
