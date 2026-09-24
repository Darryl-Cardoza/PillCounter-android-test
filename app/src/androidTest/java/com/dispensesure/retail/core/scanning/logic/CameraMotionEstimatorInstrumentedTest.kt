package com.dispensesure.retail.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * Pins the sign convention of the phase-correlation estimate. PillTracker offsets
 * every track by this shift before association, so an inverted sign would double
 * the tracks on a pan — the exact bug the tracker exists to stop.
 *
 * Instrumented, not JVM: Mat, cvtColor and phaseCorrelate need the OpenCV native
 * library, which is why CameraMotionEstimator builds its Mats lazily inside a
 * try/catch in the first place.
 */
@RunWith(AndroidJUnit4::class)
class CameraMotionEstimatorInstrumentedTest {

    @Before
    fun loadOpenCv() {
        assertTrue("OpenCV native library failed to load", OpenCVLoader.initLocal())
    }

    /**
     * A 640x640 scene translated by (shiftX, shiftY). The bar breaks the repeating
     * dot grid so the correlation peak is unambiguous.
     */
    private fun scene(shiftX: Int, shiftY: Int): Bitmap {
        val bmp = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE }
        for (row in 0 until 6) {
            for (col in 0 until 6) {
                canvas.drawCircle(
                    80f + col * 96f + shiftX,
                    80f + row * 96f + shiftY,
                    18f + ((row + col) % 3) * 4f,
                    paint
                )
            }
        }
        canvas.drawRect(20f + shiftX, 560f + shiftY, 300f + shiftX, 620f + shiftY, paint)
        return bmp
    }

    @Test
    fun sceneMovingRightAndDownYieldsAPositiveShift() {
        val estimator = CameraMotionEstimator()
        assertNull("first frame has nothing to register against", estimator.estimate(scene(0, 0)))

        val shift = estimator.estimate(scene(24, 12))

        assertNotNull("expected a registration result on the second frame", shift)
        assertEquals(24f, shift!!.dx, 3f)
        assertEquals(12f, shift.dy, 3f)
    }

    @Test
    fun sceneMovingLeftYieldsANegativeDx() {
        val estimator = CameraMotionEstimator()
        estimator.estimate(scene(0, 0))

        val shift = estimator.estimate(scene(-24, 0))

        assertNotNull(shift)
        assertEquals(-24f, shift!!.dx, 3f)
    }

    @Test
    fun resetForgetsThePreviousFrame() {
        val estimator = CameraMotionEstimator()
        estimator.estimate(scene(0, 0))

        estimator.reset()

        assertNull("after reset the next frame is a first frame again", estimator.estimate(scene(24, 0)))
    }
}
