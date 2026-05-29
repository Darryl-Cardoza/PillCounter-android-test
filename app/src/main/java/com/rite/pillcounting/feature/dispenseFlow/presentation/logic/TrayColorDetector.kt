package com.rite.pillcounting.feature.dispenseFlow.presentation.logic

import android.graphics.Bitmap
import android.graphics.RectF
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Detects the dominant color of a tray region using OpenCV HSV analysis.
 *
 * Algorithm:
 *  1. Crop the tray bounding box from the camera frame bitmap.
 *  2. Downsample to 64×64 for speed.
 *  3. Convert RGBA → RGB → HSV (OpenCV H: 0–180, S: 0–255, V: 0–255).
 *  4. Count pixels matching each [TrayColor]'s HSV range.
 *  5. Return the color whose mask has the most matching pixels.
 *
 * Called once per detected tray per frame on Dispatchers.Default, so it must
 * be fast and allocation-light. The two Mat objects used are local and released
 * before returning.
 */
object TrayColorDetector {

    private val logger = AppLogger("TrayColorDetector")

    private const val SAMPLE_SIZE = 64.0

    fun detect(bitmap: Bitmap, trayRect: RectF): TrayColor {
        return try {
            val result = detectInternal(bitmap, trayRect)
            logger.d("TrayColorDetector.detect(): rect=$trayRect → result=${result.label}")
            result
        } catch (e: Exception) {
            logger.e("Color detection error", e)
            logger.e("TrayColorDetector.detect() FAILED: ${e.message}")
            TrayColor.UNKNOWN
        }
    }

    private fun detectInternal(bitmap: Bitmap, trayRect: RectF): TrayColor {
        val bw = bitmap.width
        val bh = bitmap.height

        val left   = trayRect.left.coerceIn(0f, bw.toFloat()).toInt()
        val top    = trayRect.top.coerceIn(0f, bh.toFloat()).toInt()
        val right  = trayRect.right.coerceIn(0f, bw.toFloat()).toInt()
        val bottom = trayRect.bottom.coerceIn(0f, bh.toFloat()).toInt()

        if (right <= left || bottom <= top) return TrayColor.UNKNOWN

        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)

        val rgba = Mat()
        Utils.bitmapToMat(cropped, rgba)
        cropped.recycle()

        // Downsample → RGB → HSV
        val small = Mat()
        Imgproc.resize(rgba, small, Size(SAMPLE_SIZE, SAMPLE_SIZE))
        rgba.release()

        val rgb = Mat()
        Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
        small.release()

        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        rgb.release()

        val result = dominantColor(hsv)
        hsv.release()

        return result
    }

    /**
     * Counts pixels in [hsv] matching each named color range, returns the winner.
     *
     * OpenCV HSV convention:
     *   H: 0–180  (degrees / 2)
     *   S: 0–255
     *   V: 0–255
     *
     * Red wraps around 0°/180°, so it needs two separate inRange calls combined.
     */
    private fun dominantColor(hsv: Mat): TrayColor {
        var bestColor = TrayColor.UNKNOWN
        var bestCount = 0

        fun countMask(lower: Scalar, upper: Scalar): Int {
            val mask = Mat()
            Core.inRange(hsv, lower, upper, mask)
            val count = Core.countNonZero(mask)
            mask.release()
            return count
        }

        // White: low saturation, high value
        val white = countMask(Scalar(0.0, 0.0, 185.0), Scalar(180.0, 50.0, 255.0))

        // Black: any hue/sat, very low value
        val black = countMask(Scalar(0.0, 0.0, 0.0), Scalar(180.0, 255.0, 55.0))

        // Gray: low saturation, mid value
        val gray = countMask(Scalar(0.0, 0.0, 55.0), Scalar(180.0, 50.0, 185.0))

        // Yellow: H 22–35, high saturation
        val yellow = countMask(Scalar(22.0, 100.0, 80.0), Scalar(35.0, 255.0, 255.0))

        // Orange: H 10–22, high saturation
        val orange = countMask(Scalar(10.0, 120.0, 80.0), Scalar(22.0, 255.0, 255.0))

        // Red wraps at 0° — combine low-H and high-H masks
        val redLow  = countMask(Scalar(0.0,   100.0, 50.0), Scalar(10.0,  255.0, 255.0))
        val redHigh = countMask(Scalar(165.0, 100.0, 50.0), Scalar(180.0, 255.0, 255.0))
        val red = redLow + redHigh

        // Blue: H 100–130
        val blue = countMask(Scalar(100.0, 80.0, 50.0), Scalar(130.0, 255.0, 255.0))

        // Green: H 40–80
        val green = countMask(Scalar(40.0, 80.0, 50.0), Scalar(80.0, 255.0, 255.0))

        // Purple: H 130–165
        val purple = countMask(Scalar(130.0, 80.0, 50.0), Scalar(165.0, 255.0, 255.0))

        val candidates = listOf(
            TrayColor.WHITE  to white,
            TrayColor.BLACK  to black,
            TrayColor.GRAY   to gray,
            TrayColor.YELLOW to yellow,
            TrayColor.ORANGE to orange,
            TrayColor.RED    to red,
            TrayColor.BLUE   to blue,
            TrayColor.GREEN  to green,
            TrayColor.PURPLE to purple,
        )

        for ((color, count) in candidates) {
            if (count > bestCount) {
                bestCount = count
                bestColor = color
            }
        }

        val minPixels = (SAMPLE_SIZE * SAMPLE_SIZE * 0.05).toInt()
        val finalColor = if (bestCount >= minPixels) bestColor else TrayColor.UNKNOWN

        // Log every candidate's pixel count so we can tune HSV ranges if needed
        logger.d(
            "HSV pixel counts — " +
            "white=$white black=$black gray=$gray yellow=$yellow orange=$orange " +
            "red=$red blue=$blue green=$green purple=$purple | " +
            "winner=${bestColor.label}($bestCount) minRequired=$minPixels → ${finalColor.label}"
        )

        return finalColor
    }
}
