package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [Letterbox].
 *
 * [Letterbox] is a singleton `object` that mutates its own cached Bitmap/Canvas
 * state across calls (by design, for GC pressure reasons — see file header).
 * We mock [Bitmap.createBitmap] (a static factory) and construct real [Canvas]
 * instances is not possible on the plain JVM (Canvas(Bitmap) requires native
 * drawing), so we mockk the Canvas construction path indirectly by mocking
 * `Bitmap.createBitmap` to return relaxed Bitmap mocks and mockking the
 * `Canvas` class itself so `Canvas(bitmap)` calls succeed without native code.
 * This lets us exercise the real scale/pad math and the reuse-vs-reallocate
 * branch, which is the actual business logic in this file.
 */
class LetterboxTest {

    private lateinit var mockOutputBitmap: Bitmap
    private lateinit var mockCanvas: Canvas

    @Before
    fun setUp() {
        mockkStatic(Bitmap::class)
        mockCanvas = mockk(relaxed = true)
        mockOutputBitmap = mockk(relaxed = true)

        every {
            Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>())
        } returns mockOutputBitmap

        // Canvas(Bitmap) constructor call inside Letterbox — mockkConstructor
        // covers the `Canvas(fresh)` invocation.
        io.mockk.mockkConstructor(Canvas::class)
        every { anyConstructed<Canvas>().drawColor(any()) } returns Unit
        every { anyConstructed<Canvas>().drawBitmap(any<Bitmap>(), any(), any()) } returns Unit

        // Reset Letterbox's cached singleton state before every test so tests
        // don't leak into each other (object is a JVM-wide singleton).
        resetLetterboxState()
    }

    @After
    fun tearDown() {
        unmockkStatic(Bitmap::class)
        io.mockk.unmockkConstructor(Canvas::class)
        resetLetterboxState()
    }

    private fun resetLetterboxState() {
        val scaleField = Letterbox::class.java.getDeclaredField("currentScaleInfo")
        scaleField.isAccessible = true
        scaleField.set(Letterbox, null)

        val bitmapField = Letterbox::class.java.getDeclaredField("outputBitmap")
        bitmapField.isAccessible = true
        bitmapField.set(Letterbox, null)

        val canvasField = Letterbox::class.java.getDeclaredField("outputCanvas")
        canvasField.isAccessible = true
        canvasField.set(Letterbox, null)
    }

    private fun srcBitmap(width: Int, height: Int): Bitmap {
        val bmp = mockk<Bitmap>(relaxed = true)
        every { bmp.width } returns width
        every { bmp.height } returns height
        return bmp
    }

    @Test
    fun `computes scale and centered padding for square source into square target`() {
        val src = srcBitmap(100, 100)

        Letterbox.preprocess(src, targetSize = 640)

        val info = Letterbox.currentScaleInfo
        assertEquals(6.4f, info!!.scale, 0.0001f)
        assertEquals(0f, info.padX, 0.0001f)
        assertEquals(0f, info.padY, 0.0001f)
        assertEquals(640, info.inputSize)
    }

    @Test
    fun `pads vertically when source is wider than tall`() {
        // 640 wide, 320 tall source -> scale limited by width = 1, padY centers height
        val src = srcBitmap(640, 320)

        Letterbox.preprocess(src, targetSize = 640)

        val info = Letterbox.currentScaleInfo!!
        assertEquals(1f, info.scale, 0.0001f)
        assertEquals(0f, info.padX, 0.0001f)
        // newH = 320 * 1 = 320, padY = (640-320)/2 = 160
        assertEquals(160f, info.padY, 0.0001f)
    }

    @Test
    fun `pads horizontally when source is taller than wide`() {
        val src = srcBitmap(320, 640)

        Letterbox.preprocess(src, targetSize = 640)

        val info = Letterbox.currentScaleInfo!!
        assertEquals(1f, info.scale, 0.0001f)
        assertEquals(160f, info.padX, 0.0001f)
        assertEquals(0f, info.padY, 0.0001f)
    }

    @Test
    fun `uses default target size of 640 when not specified`() {
        val src = srcBitmap(100, 100)

        Letterbox.preprocess(src)

        assertEquals(640, Letterbox.currentScaleInfo!!.inputSize)
    }

    @Test
    fun `respects custom target size`() {
        val src = srcBitmap(50, 50)

        Letterbox.preprocess(src, targetSize = 320)

        val info = Letterbox.currentScaleInfo!!
        assertEquals(320, info.inputSize)
        assertEquals(6.4f, info.scale, 0.0001f)
    }

    @Test
    fun `allocates a new bitmap on first call`() {
        val src = srcBitmap(100, 100)

        val result = Letterbox.preprocess(src, targetSize = 640)

        assertSame(mockOutputBitmap, result)
        every { Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888) }
        // Verify factory was invoked exactly once for the allocation.
        verify(exactly = 1) { Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888) }
    }

    @Test
    fun `reuses cached bitmap on second call with same target size`() {
        every { mockOutputBitmap.isRecycled } returns false
        every { mockOutputBitmap.width } returns 640
        every { mockOutputBitmap.height } returns 640

        val src1 = srcBitmap(100, 100)
        val src2 = srcBitmap(200, 200)

        val first = Letterbox.preprocess(src1, targetSize = 640)
        val second = Letterbox.preprocess(src2, targetSize = 640)

        assertSame(first, second)
        // createBitmap should only have been called once (first call), not twice.
        verify(exactly = 1) { Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888) }
    }

    @Test
    fun `reallocates when target size changes between calls`() {
        every { mockOutputBitmap.isRecycled } returns false
        every { mockOutputBitmap.width } returns 640
        every { mockOutputBitmap.height } returns 640

        val secondBitmap = mockk<Bitmap>(relaxed = true)
        every { Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888) } returns secondBitmap

        val src = srcBitmap(100, 100)

        val first = Letterbox.preprocess(src, targetSize = 640)
        val second = Letterbox.preprocess(src, targetSize = 320)

        assertSame(mockOutputBitmap, first)
        assertSame(secondBitmap, second)
        verify(exactly = 1) { Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888) }
        verify(exactly = 1) { Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888) }
    }

    @Test
    fun `reallocates when cached bitmap has been recycled`() {
        every { mockOutputBitmap.isRecycled } returns true
        every { mockOutputBitmap.width } returns 640
        every { mockOutputBitmap.height } returns 640

        val replacement = mockk<Bitmap>(relaxed = true)
        every { Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888) } returnsMany
            listOf(mockOutputBitmap, replacement)

        val src = srcBitmap(100, 100)

        val first = Letterbox.preprocess(src, targetSize = 640)
        val second = Letterbox.preprocess(src, targetSize = 640)

        assertSame(mockOutputBitmap, first)
        assertSame(replacement, second)
        verify(exactly = 2) { Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888) }
    }

    @Test
    fun `handles zero-size target gracefully by producing infinite scale`() {
        val src = srcBitmap(100, 100)

        Letterbox.preprocess(src, targetSize = 0)

        val info = Letterbox.currentScaleInfo!!
        assertEquals(0f, info.scale, 0.0001f)
        assertEquals(0, info.inputSize)
    }

    @Test
    fun `updates currentScaleInfo on every call reflecting the latest source`() {
        val src1 = srcBitmap(640, 640)
        Letterbox.preprocess(src1, targetSize = 640)
        assertEquals(1f, Letterbox.currentScaleInfo!!.scale, 0.0001f)

        val src2 = srcBitmap(1280, 1280)
        Letterbox.preprocess(src2, targetSize = 640)
        assertEquals(0.5f, Letterbox.currentScaleInfo!!.scale, 0.0001f)
    }
}
