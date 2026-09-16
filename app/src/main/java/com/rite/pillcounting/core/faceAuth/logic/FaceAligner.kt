package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * Aligns a detected face to the canonical ArcFace/SFace 112×112 pose.
 *
 * Description:
 * Direct Kotlin port of `standalone_face_tf.py`'s `similarity_transform()` +
 * `align_crop()`. SFace was trained on faces warped into this exact pose, so
 * every detection must go through this before [FaceEngine.embed] — a
 * misaligned crop produces a garbage embedding.
 *
 * What it does:
 * - [similarityTransform] computes the 2×3 affine (rotate+scale+translate,
 *   no skew) that best maps 5 source points onto 5 destination points, via a
 *   closed-form 2×2 SVD (Umeyama's method specialized to 2D, where the
 *   covariance matrix is always 2×2 so a general SVD library isn't needed).
 * - [alignCrop] applies that warp with OpenCV's `Imgproc.warpAffine`.
 */
object FaceAligner {

    /** Canonical ArcFace/InsightFace 112×112 destination template, in YuNet's own landmark order: right eye, left eye, nose, right mouth, left mouth. */
    val ARCFACE_TEMPLATE_112: Array<FloatArray> = arrayOf(
        floatArrayOf(38.2946f, 51.6963f),
        floatArrayOf(73.5318f, 51.5014f),
        floatArrayOf(56.0252f, 71.7366f),
        floatArrayOf(41.5493f, 92.3655f),
        floatArrayOf(70.7299f, 92.2041f),
    )

    /**
     * Computes the 2×3 similarity transform mapping [src] points onto [dst] points.
     *
     * @param src 5 (x,y) source points (detector landmarks).
     * @param dst 5 (x,y) destination points (template, possibly scaled).
     * @return 6 floats, row-major 2×3: `[a, b, tx, c, d, ty]` such that `x' = a*x + b*y + tx`, `y' = c*x + d*y + ty`.
     *
     * Example Usage:
     * val m = FaceAligner.similarityTransform(landmarks, FaceAligner.ARCFACE_TEMPLATE_112)
     */
    fun similarityTransform(src: Array<FloatArray>, dst: Array<FloatArray>): FloatArray {
        require(src.size == dst.size && src.size >= 2) { "need matching point sets of >=2 points" }
        val n = src.size

        val srcMeanX = src.sumOf { it[0].toDouble() } / n
        val srcMeanY = src.sumOf { it[1].toDouble() } / n
        val dstMeanX = dst.sumOf { it[0].toDouble() } / n
        val dstMeanY = dst.sumOf { it[1].toDouble() } / n

        // covariance = (dst_c^T @ src_c) / n, a 2x2 matrix: [[c00,c01],[c10,c11]]
        var c00 = 0.0; var c01 = 0.0; var c10 = 0.0; var c11 = 0.0
        var varSrc = 0.0
        for (i in 0 until n) {
            val sx = src[i][0] - srcMeanX
            val sy = src[i][1] - srcMeanY
            val dx = dst[i][0] - dstMeanX
            val dy = dst[i][1] - dstMeanY
            c00 += dx * sx; c01 += dx * sy
            c10 += dy * sx; c11 += dy * sy
            varSrc += sx * sx + sy * sy
        }
        c00 /= n; c01 /= n; c10 /= n; c11 /= n
        varSrc /= n

        // Closed-form 2x2 SVD via eigen-decomposition of C^T*C, per Umeyama (1991) specialized to 2D.
        val e = (c00 + c11) / 2.0
        val f = (c00 - c11) / 2.0
        val g = (c10 + c01) / 2.0
        val h = (c10 - c01) / 2.0
        val q = sqrt(e * e + h * h)
        val r = sqrt(f * f + g * g)
        val sx1 = q + r
        val sy1 = q - r
        val a1 = kotlin.math.atan2(g, f)
        val a2 = kotlin.math.atan2(h, e)
        val theta = (a2 - a1) / 2.0
        val phi = (a2 + a1) / 2.0

        val det = c00 * c11 - c01 * c10
        val d1 = if (sx1 >= 0) 1.0 else -1.0
        val d2 = if (det < 0) -1.0 else 1.0

        val cosT = kotlin.math.cos(theta); val sinT = kotlin.math.sin(theta)
        val cosP = kotlin.math.cos(phi); val sinP = kotlin.math.sin(phi)

        // U = [[cosP,-sinP],[sinP,cosP]], V^T = [[cosT,sinT],[-sinT,cosT]], both refined so R = U*diag(d1,d2)*V^T is a proper rotation.
        val u00 = cosP; val u01 = -sinP * d2
        val u10 = sinP; val u11 = cosP * d2
        val v00 = cosT; val v01 = -sinT
        val v10 = sinT; val v11 = cosT

        val r00 = u00 * v00 + u01 * v10
        val r01 = u00 * v01 + u01 * v11
        val r10 = u10 * v00 + u11 * v10
        val r11 = u10 * v01 + u11 * v11

        val singularSum = sx1 * d1 + sy1 * d2
        val scale = if (varSrc > 1e-12) singularSum / varSrc else 1.0

        val a = (scale * r00).toFloat()
        val b = (scale * r01).toFloat()
        val c = (scale * r10).toFloat()
        val d = (scale * r11).toFloat()
        val tx = (dstMeanX - scale * (r00 * srcMeanX + r01 * srcMeanY)).toFloat()
        val ty = (dstMeanY - scale * (r10 * srcMeanX + r11 * srcMeanY)).toFloat()

        return floatArrayOf(a, b, tx, c, d, ty)
    }

    /**
     * Warps [bitmap] so [landmarks] land on the canonical template, cropped to [size]×[size].
     *
     * @param bitmap Source frame (or a crop containing the face) in Android [Bitmap] form.
     * @param landmarks 10 floats (5 x,y pairs) in [bitmap]'s pixel coordinates, YuNet's own order.
     * @param size Output square size in pixels; SFace expects 112.
     * @return A new [size]×[size] [Bitmap] with the face aligned to the canonical pose.
     *
     * Example Usage:
     * val aligned = FaceAligner.alignCrop(frame, face.landmarks)
     */
    fun alignCrop(bitmap: Bitmap, landmarks: FloatArray, size: Int = 112): Bitmap {
        val src = Array(5) { i -> floatArrayOf(landmarks[2 * i], landmarks[2 * i + 1]) }
        val dst = Array(5) { i ->
            floatArrayOf(
                ARCFACE_TEMPLATE_112[i][0] * (size / 112f),
                ARCFACE_TEMPLATE_112[i][1] * (size / 112f)
            )
        }
        val m = similarityTransform(src, dst)

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val warpMat = Mat(2, 3, CvType.CV_32F)
        warpMat.put(0, 0, m[0].toDouble(), m[1].toDouble(), m[2].toDouble())
        warpMat.put(1, 0, m[3].toDouble(), m[4].toDouble(), m[5].toDouble())

        val dstMat = Mat()
        Imgproc.warpAffine(srcMat, dstMat, warpMat, Size(size.toDouble(), size.toDouble()))
        srcMat.release(); warpMat.release()

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, out)
        dstMat.release()
        return out
    }
}
