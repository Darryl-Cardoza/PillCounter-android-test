package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TrayColorDetector].
 *
 * The core HSV-analysis path (`detectInternal` → `dominantColor`) requires the real
 * OpenCV native library (Mat, Utils.bitmapToMat, Imgproc, Core), which is not available
 * on the plain JVM unit-test classpath for this project (no `testImplementation` OpenCV
 * artifact / native libs, no Robolectric). Attempting to exercise that path here would
 * either crash with UnsatisfiedLinkError or require mocking OpenCV's native-backed `Mat`
 * class down to the point where the test would just assert "mocks were called" rather
 * than any real HSV logic — not meaningful per project conventions.
 *
 * What IS pure-Kotlin and safely testable without OpenCV:
 *  - The tray-rect bounds/degenerate-rect guard in `detectInternal`, which short-circuits
 *    to [TrayColor.UNKNOWN] *before* any OpenCV call is made, for rectangles that coerce
 *    to an empty or inverted crop region.
 *  - The top-level `detect()` try/catch fallback to [TrayColor.UNKNOWN] on any exception
 *    (exercised here via a mocked [Bitmap] that throws when OpenCV/Bitmap APIs are touched).
 *
 * android.util.Log is mocked statically because [TrayColorDetector] logs via [AppLogger]
 * on every call, mirroring the existing AppLoggerTest convention.
 */
class TrayColorDetectorTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns "stack trace"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun mockBitmap(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = mockk<Bitmap>()
        every { bitmap.width } returns width
        every { bitmap.height } returns height
        return bitmap
    }

    // -------------------------------------------------------------------------
    // Degenerate-rect guard (right <= left / bottom <= top) — returns UNKNOWN
    // without ever touching OpenCV or Bitmap.createBitmap.
    // -------------------------------------------------------------------------

    @Test
    fun `returns UNKNOWN when rect right equals left`() {
        val bitmap = mockBitmap()
        val rect = RectF(50f, 10f, 50f, 90f)

        val result = TrayColorDetector.detect(bitmap, rect)

        assertEquals(TrayColor.UNKNOWN, result)
    }

    @Test
    fun `returns UNKNOWN when rect right is less than left`() {
        val bitmap = mockBitmap()
        val rect = RectF(80f, 10f, 20f, 90f)

        val result = TrayColorDetector.detect(bitmap, rect)

        assertEquals(TrayColor.UNKNOWN, result)
    }

    @Test
    fun `returns UNKNOWN when rect bottom equals top`() {
        val bitmap = mockBitmap()
        val rect = RectF(10f, 40f, 90f, 40f)

        val result = TrayColorDetector.detect(bitmap, rect)

        assertEquals(TrayColor.UNKNOWN, result)
    }

    @Test
    fun `returns UNKNOWN when rect bottom is less than top`() {
        val bitmap = mockBitmap()
        val rect = RectF(10f, 90f, 90f, 10f)

        val result = TrayColorDetector.detect(bitmap, rect)

        assertEquals(TrayColor.UNKNOWN, result)
    }

    @Test
    fun `returns UNKNOWN when rect lies entirely outside bitmap bounds and coerces to a point`() {
        // A rect fully to the right of a 100x100 bitmap coerces both left and right to
        // bw (100), collapsing the crop width to zero -> degenerate rect guard triggers.
        val bitmap = mockBitmap(width = 100, height = 100)
        val rect = RectF(150f, 10f, 200f, 90f)

        val result = TrayColorDetector.detect(bitmap, rect)

        assertEquals(TrayColor.UNKNOWN, result)
    }

    @Test
    fun `returns UNKNOWN when rect has negative coordinates that coerce to zero width`() {
        val bitmap = mockBitmap(width = 100, height = 100)
        val rect = RectF(-50f, -50f, -10f, -10f)

        val result = TrayColorDetector.detect(bitmap, rect)

        assertEquals(TrayColor.UNKNOWN, result)
    }

    // -------------------------------------------------------------------------
    // Exception handling in detect() — any exception thrown while processing
    // (e.g. from Bitmap.createBitmap once past the guard) is caught and mapped
    // to TrayColor.UNKNOWN rather than propagating.
    // -------------------------------------------------------------------------

    @Test
    fun `returns UNKNOWN and does not throw when bitmap cropping throws an exception`() {
        // Valid, non-degenerate rect so we get past the bounds guard and into the
        // real Bitmap.createBitmap(bitmap, left, top, w, h) call, which we can't
        // mock (it's a static factory on the real Bitmap class) — but on the JVM
        // unit-test stub, calling any unstubbed method on a mockk() Bitmap other
        // than the ones we stubbed will throw, which detect() must catch.
        val bitmap = mockBitmap(width = 100, height = 100)
        val rect = RectF(10f, 10f, 90f, 90f)

        val result = TrayColorDetector.detect(bitmap, rect)

        assertEquals(TrayColor.UNKNOWN, result)
    }

    @Test
    fun `boundary rect exactly matching bitmap dimensions does not throw and yields UNKNOWN on failure`() {
        val bitmap = mockBitmap(width = 64, height = 64)
        val rect = RectF(0f, 0f, 64f, 64f)

        val result = TrayColorDetector.detect(bitmap, rect)

        assertEquals(TrayColor.UNKNOWN, result)
    }
}
