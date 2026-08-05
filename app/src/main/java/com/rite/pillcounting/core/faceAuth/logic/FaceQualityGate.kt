package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.Bitmap
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Rejects low-quality registration capture frames before they're embedded.
 *
 * Description:
 * Direct Kotlin port of `standalone_face_tf.py`'s `quality_gate()` (plus its
 * `face_crop()`/`sharpness()` helpers) — same three checks, same thresholds:
 * face too small ("move closer"), face too close/filling the frame ("move
 * back"), and blurry/low-light crops (Laplacian-variance sharpness).
 *
 * What it does:
 * - [evaluate] runs all three checks in order and returns the first failure
 *   reason, or null when the frame is good enough to embed.
 */
class FaceQualityGate @Inject constructor() {

    companion object {
        const val MIN_FACE_WIDTH_PX = 90f
        const val MAX_FACE_WIDTH_RATIO = 0.85f
        const val ENROLL_MIN_SHARPNESS = 45.0
        private const val CROP_MARGIN = 0.12f
        private const val CROP_SIZE = 128
        private const val MIN_CROP_SIDE_PX = 20
    }

    /**
     * Checks whether [face], detected in [frame], is good enough to embed.
     *
     * @param frame The full camera frame [face] was detected in.
     * @param face The detected face to quality-gate.
     * @return A rejection reason ("move closer" / "move back" / "hold still / more light"), or null if acceptable.
     *
     * Example Usage:
     * val reason = faceQualityGate.evaluate(frame, face)
     */
    fun evaluate(frame: Bitmap, face: FaceBox): String? {
        if (face.rect.width() < MIN_FACE_WIDTH_PX) return "move closer"
        if (face.rect.width() > frame.width * MAX_FACE_WIDTH_RATIO) return "move back"

        val crop = faceCrop(frame, face) ?: return "face crop failed"
        if (sharpness(crop) < ENROLL_MIN_SHARPNESS) return "hold still / more light"
        return null
    }

    /**
     * Reads [face]'s Laplacian-variance sharpness directly, for callers that need
     * to rank multiple already-accepted frames against each other (auto-capture
     * best-of-many selection) rather than just a pass/fail reason.
     *
     * @param frame The full camera frame [face] was detected in.
     * @param face The detected face to score.
     * @return The crop's sharpness value, or null if the crop itself failed (mirrors [evaluate]'s "face crop failed" case).
     *
     * Example Usage:
     * val sharpness = faceQualityGate.sharpnessScore(frame, face)
     */
    fun sharpnessScore(frame: Bitmap, face: FaceBox): Double? = faceCrop(frame, face)?.let { sharpness(it) }

    /** Crops [frame] around [face]'s box with a margin, resized to a fixed square — mirrors the script's `face_crop()`. */
    private fun faceCrop(frame: Bitmap, face: FaceBox): Bitmap? {
        val w = frame.width
        val h = frame.height
        val bw = face.rect.width()
        val bh = face.rect.height()
        val mx = bw * CROP_MARGIN
        val my = bh * CROP_MARGIN

        val x0 = max(0f, face.rect.left - mx).toInt()
        val y0 = max(0f, face.rect.top - my).toInt()
        val x1 = min(w.toFloat(), face.rect.right + mx).toInt()
        val y1 = min(h.toFloat(), face.rect.bottom + my).toInt()

        if (x1 - x0 < MIN_CROP_SIDE_PX || y1 - y0 < MIN_CROP_SIDE_PX) return null

        val cropped = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0)
        return Bitmap.createScaledBitmap(cropped, CROP_SIZE, CROP_SIZE, true)
    }

    /** Laplacian-variance sharpness of [bitmap] — mirrors the script's `sharpness()`. */
    private fun sharpness(bitmap: Bitmap): Double {
        val rgba = Mat()
        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, stddev)
            val sd = stddev.toArray()[0]
            return sd * sd
        } finally {
            rgba.release(); gray.release(); laplacian.release(); mean.release(); stddev.release()
        }
    }
}
