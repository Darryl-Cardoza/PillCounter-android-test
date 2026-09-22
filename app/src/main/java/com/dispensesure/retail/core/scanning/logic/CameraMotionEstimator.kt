package com.dispensesure.retail.core.scanning.logic

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Global camera motion between consecutive frames, by phase correlation of the
 * 640 letterbox downscaled to [SIDE]². The result is the displacement, in
 * letterbox pixels, of the scene from the previous frame to this one: a pan
 * that moves every pill 12 px to the right yields dx ≈ +12.
 *
 * PillTracker moves its tracks by this before association, so a hand-held pan
 * keeps the same tracks instead of spawning a second set. Phase correlation is
 * insensitive to the repeating pill pattern that defeats nearest-neighbour
 * voting in a dense tray, because the tray edges, chute and background carry
 * the low frequencies that fix the peak.
 *
 * Not thread-safe; the analyzer calls it once per frame.
 */
class CameraMotionEstimator {

    data class Shift(val dx: Float, val dy: Float, val response: Float)

    // OpenCV objects are created on first use, inside estimate()'s try/catch:
    // constructing a Mat needs the native library, which plain-JVM unit tests
    // of the analyzer do not have.
    private val size by lazy { Size(SIDE.toDouble(), SIDE.toDouble()) }
    private val rgba by lazy { Mat() }
    private val gray by lazy { Mat() }
    private val small by lazy { Mat() }
    private val current by lazy { Mat() }
    private val window by lazy { Mat() }
    private var previous: Mat? = null
    private var disabled = false

    /** Null on the first frame, when the correlation peak is too weak, or if OpenCV is unavailable. */
    fun estimate(letterboxed: Bitmap): Shift? {
        if (disabled) return null
        return try {
            Utils.bitmapToMat(letterboxed, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, small, size, 0.0, 0.0, Imgproc.INTER_AREA)
            small.convertTo(current, CvType.CV_32F)
            val prev = previous
            if (prev == null) {
                previous = current.clone()
                return null
            }
            if (window.empty()) Imgproc.createHanningWindow(window, size, CvType.CV_32F)
            val response = DoubleArray(1)
            val shift = Imgproc.phaseCorrelate(prev, current, window, response)
            current.copyTo(prev)
            if (response[0] < MIN_RESPONSE) return null
            val k = letterboxed.width.toFloat() / SIDE
            Shift(dx = (shift.x * k).toFloat(), dy = (shift.y * k).toFloat(), response = response[0].toFloat())
        } catch (t: Throwable) {
            disabled = true
            Log.w(TAG, "camera motion estimation disabled: ${t.message}")
            null
        }
    }

    /** Forget the previous frame (new scene). */
    fun reset() {
        previous?.release()
        previous = null
    }

    companion object {
        private const val TAG = "CameraMotion"

        /** Working resolution. 640 → 256 keeps the estimate well under a pill in error. */
        private const val SIDE = 256

        /**
         * Correlation peak below which the estimate is noise (the scene changed
         * entirely, or nothing but the flat pad is visible). The tracker then
         * falls back to its own displacement vote.
         */
        private const val MIN_RESPONSE = 0.05
    }
}
