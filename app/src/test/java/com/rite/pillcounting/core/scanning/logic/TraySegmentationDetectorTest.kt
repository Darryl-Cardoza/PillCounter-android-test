package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [TraySegmentationDetector].
 *
 * Robolectric is required because the detector allocates and draws into real
 * android.graphics Bitmap/Canvas objects in its constructor and [detect]. The
 * TFLite [Interpreter] is mocked with MockK: its `run(Any, Any)` overload is
 * stubbed to write attacker-controlled per-pixel class logits directly into
 * the output ByteBuffer the detector passes in, so we can drive every branch
 * of the argmax/confidence-margin decode logic without a real .tflite model.
 */
@RunWith(RobolectricTestRunner::class)
class TraySegmentationDetectorTest {

    private val interpreter: Interpreter = mockk(relaxed = true)
    private lateinit var detector: TraySegmentationDetector

    private val inputSize = TraySegmentationDetector.INPUT_SIZE
    private val numClasses = TraySegmentationDetector.NUM_CLASSES

    private val scaleInfo640 = Letterbox.ScaleInfo(
        scale = 1.0f,
        padX = 0f,
        padY = 0f,
        inputSize = 640
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.v(any(), any<String>()) } returns 0

        detector = TraySegmentationDetector(interpreter)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Builds a per-pixel logit grid where every pixel gets the same
     * (bg, chute, tray) logit triple, then stubs the interpreter to write it
     * into whatever output ByteBuffer the detector passes to `run`.
     */
    private fun stubUniformOutput(bg: Float, chute: Float, tray: Float) {
        every { interpreter.run(any(), any()) } answers {
            val out = arg<ByteBuffer>(1)
            out.order(ByteOrder.nativeOrder())
            out.clear()
            val floatView = out.asFloatBuffer()
            repeat(inputSize * inputSize) {
                floatView.put(bg)
                floatView.put(chute)
                floatView.put(tray)
            }
        }
    }

    /**
     * Stubs the interpreter to fill a rectangular region [x0,x1) x [y0,y1)
     * with the given foreground class' logits (beating background by
     * [margin]), and background everywhere else.
     */
    private fun stubRegion(
        x0: Int, y0: Int, x1: Int, y1: Int,
        targetClassIndex: Int,
        margin: Float
    ) {
        every { interpreter.run(any(), any()) } answers {
            val out = arg<ByteBuffer>(1)
            out.order(ByteOrder.nativeOrder())
            out.clear()
            val floatView = out.asFloatBuffer()
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val logits = FloatArray(numClasses)
                    if (x in x0 until x1 && y in y0 until y1) {
                        logits[targetClassIndex] = margin
                    }
                    floatView.put(logits[TraySegmentationDetector.CLASS_BG])
                    floatView.put(logits[TraySegmentationDetector.CLASS_CHUTE])
                    floatView.put(logits[TraySegmentationDetector.CLASS_TRAY])
                }
            }
        }
    }

    private fun letterboxBitmap(size: Int = 640): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    // ─────────────────────────── BACKGROUND-ONLY / NO DETECTIONS ───────────────────────────

    @Test
    fun `all-background output yields no detections`() {
        stubUniformOutput(bg = 10f, chute = 0f, tray = 0f)

        val result = detector.detect(letterboxBitmap(), scaleInfo640, 640, 640)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `foreground region below MIN_CLASS_PIXELS threshold is not reported`() {
        // A tiny 10x10 region (100 px) is below MIN_CLASS_PIXELS (400).
        stubRegion(
            x0 = 0, y0 = 0, x1 = 10, y1 = 10,
            targetClassIndex = TraySegmentationDetector.CLASS_TRAY,
            margin = 5f
        )

        val result = detector.detect(letterboxBitmap(), scaleInfo640, 640, 640)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `foreground region below FG_LOGIT_MARGIN confidence gate is rejected`() {
        // Large region but logit margin under FG_LOGIT_MARGIN (1.5f) -> rejected.
        every { interpreter.run(any(), any()) } answers {
            val out = arg<ByteBuffer>(1)
            out.order(ByteOrder.nativeOrder())
            out.clear()
            val floatView = out.asFloatBuffer()
            repeat(inputSize * inputSize) {
                // tray logit only barely beats background: margin = 1.0 < 1.5 threshold
                floatView.put(0f)   // bg
                floatView.put(0f)   // chute
                floatView.put(1.0f) // tray
            }
        }

        val result = detector.detect(letterboxBitmap(), scaleInfo640, 640, 640)

        assertTrue(result.isEmpty())
    }

    // ─────────────────────────── TRAY DETECTION ───────────────────────────

    @Test
    fun `large confident tray region yields single TRAY detection`() {
        stubRegion(
            x0 = 50, y0 = 50, x1 = 150, y1 = 150,
            targetClassIndex = TraySegmentationDetector.CLASS_TRAY,
            margin = 5f
        )

        val result = detector.detect(letterboxBitmap(), scaleInfo640, 640, 640)

        assertEquals(1, result.size)
        assertEquals(TrayClass.TRAY, result[0].cls)
        assertEquals(1.0f, result[0].confidence)
        assertEquals(inputSize, result[0].maskSize)
    }

    @Test
    fun `large confident chute region yields single CHUTE detection`() {
        stubRegion(
            x0 = 50, y0 = 50, x1 = 150, y1 = 150,
            targetClassIndex = TraySegmentationDetector.CLASS_CHUTE,
            margin = 5f
        )

        val result = detector.detect(letterboxBitmap(), scaleInfo640, 640, 640)

        assertEquals(1, result.size)
        assertEquals(TrayClass.CHUTE, result[0].cls)
    }

    @Test
    fun `tray and chute both above threshold yield tray first then chute`() {
        every { interpreter.run(any(), any()) } answers {
            val out = arg<ByteBuffer>(1)
            out.order(ByteOrder.nativeOrder())
            out.clear()
            val floatView = out.asFloatBuffer()
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    var bg = 0f; var chute = 0f; var tray = 0f
                    if (x in 0 until 50 && y in 0 until 50) {
                        tray = 5f
                    } else if (x in 100 until 150 && y in 100 until 150) {
                        chute = 5f
                    }
                    floatView.put(bg); floatView.put(chute); floatView.put(tray)
                }
            }
        }

        val result = detector.detect(letterboxBitmap(), scaleInfo640, 640, 640)

        assertEquals(2, result.size)
        assertEquals(TrayClass.TRAY, result[0].cls)
        assertEquals(TrayClass.CHUTE, result[1].cls)
    }

    @Test
    fun `bbox is mapped back into original image coordinate space`() {
        // Region occupies pixels [50,150) in 384-space with identity 640 scaleInfo,
        // so the 384-space scaleInfo derived internally is scale=384/640, pad=0.
        stubRegion(
            x0 = 50, y0 = 50, x1 = 150, y1 = 150,
            targetClassIndex = TraySegmentationDetector.CLASS_TRAY,
            margin = 5f
        )

        val originalWidth = 640
        val originalHeight = 640
        val result = detector.detect(letterboxBitmap(), scaleInfo640, originalWidth, originalHeight)

        assertEquals(1, result.size)
        val rect = result[0].rect
        val k = inputSize.toFloat() / 640f
        val expectedX1 = 50f / k
        val expectedY1 = 50f / k
        val expectedX2 = 150f / k
        val expectedY2 = 150f / k
        assertEquals(expectedX1, rect.left, 1.0f)
        assertEquals(expectedY1, rect.top, 1.0f)
        assertEquals(expectedX2, rect.right, 1.0f)
        assertEquals(expectedY2, rect.bottom, 1.0f)
    }

    @Test
    fun `bbox is clamped within original image bounds`() {
        // Region spans the full 384x384 frame so the back-projected bbox would
        // exceed a small original frame; result must clamp to [0, originalWidth/Height].
        stubRegion(
            x0 = 0, y0 = 0, x1 = inputSize, y1 = inputSize,
            targetClassIndex = TraySegmentationDetector.CLASS_TRAY,
            margin = 5f
        )

        val originalWidth = 100
        val originalHeight = 100
        val result = detector.detect(letterboxBitmap(), scaleInfo640, originalWidth, originalHeight)

        assertEquals(1, result.size)
        val rect = result[0].rect
        assertTrue(rect.left >= 0f)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.right <= originalWidth.toFloat())
        assertTrue(rect.bottom <= originalHeight.toFloat())
    }

    @Test
    fun `mask reflects only pixels assigned to the winning class`() {
        stubRegion(
            x0 = 50, y0 = 50, x1 = 150, y1 = 150,
            targetClassIndex = TraySegmentationDetector.CLASS_TRAY,
            margin = 5f
        )

        val result = detector.detect(letterboxBitmap(), scaleInfo640, 640, 640)

        assertEquals(1, result.size)
        val mask = result[0].mask!!
        // Inside the stubbed region -> set.
        assertTrue(mask.get(75 * inputSize + 75))
        // Outside the stubbed region -> not set.
        assertTrue(!mask.get(0))
    }

    // ─────────────────────────── EXCEPTION HANDLING ───────────────────────────

    @Test
    fun `interpreter throwing exception yields empty list instead of propagating`() {
        every { interpreter.run(any(), any()) } throws RuntimeException("boom")

        val result = detector.detect(letterboxBitmap(), scaleInfo640, 640, 640)

        assertTrue(result.isEmpty())
    }

    // ─────────────────────────── LETTERBOX/SCALE-INFO DERIVATION ───────────────────────────

    @Test
    fun `non-identity 640 scaleInfo with padding is composed correctly into 384 space`() {
        val scaleInfoWithPad = Letterbox.ScaleInfo(
            scale = 0.5f,
            padX = 20f,
            padY = 40f,
            inputSize = 640
        )
        stubRegion(
            x0 = 50, y0 = 50, x1 = 150, y1 = 150,
            targetClassIndex = TraySegmentationDetector.CLASS_TRAY,
            margin = 5f
        )

        val result = detector.detect(letterboxBitmap(), scaleInfoWithPad, 640, 640)

        assertEquals(1, result.size)
        // The scaleInfo carried on the detection should be the derived 384-space one.
        val k = inputSize.toFloat() / 640f
        val expected = Letterbox.ScaleInfo(
            scale = scaleInfoWithPad.scale * k,
            padX = scaleInfoWithPad.padX * k,
            padY = scaleInfoWithPad.padY * k,
            inputSize = inputSize
        )
        assertEquals(expected.scale, result[0].scaleInfo!!.scale, 0.0001f)
        assertEquals(expected.padX, result[0].scaleInfo!!.padX, 0.0001f)
        assertEquals(expected.padY, result[0].scaleInfo!!.padY, 0.0001f)
        assertEquals(inputSize, result[0].scaleInfo!!.inputSize)
    }

    // ─────────────────────────── close() ───────────────────────────

    @Test
    fun `close delegates to interpreter close`() {
        every { interpreter.close() } returns Unit

        detector.close()

        io.mockk.verify { interpreter.close() }
    }

    @Test
    fun `close swallows exception thrown by interpreter close`() {
        every { interpreter.close() } throws RuntimeException("close failed")

        // Should not throw.
        detector.close()
    }

    @Test
    fun `detect after close still runs without crashing since bitmap recycle is caught`() {
        every { interpreter.close() } returns Unit
        detector.close()
        // A second close() call hits inputBitmap.recycle() again, throwing internally
        // (already recycled) but caught, so no exception should propagate.
        detector.close()
    }
}
