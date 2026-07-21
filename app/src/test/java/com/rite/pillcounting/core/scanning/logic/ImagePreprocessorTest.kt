package com.rite.pillcounting.core.scanning.logic

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * [ImagePreprocessor.preprocess] takes an [ImageProxy] and internally calls its
 * `toBitmap()` member function, which performs a real YUV->RGB conversion backed by
 * native code. That method cannot be exercised meaningfully on the JVM (Robolectric
 * does not shadow it, and there is no seam to inject a fake YUV frame), so
 * [imageProxy] is a plain relaxed mockk whose `toBitmap()` is stubbed directly per
 * test — it's a real member of the [ImageProxy] interface, not a top-level Kotlin
 * extension function, so no `mockkStatic`/file-facade interception is needed. This
 * lets every other real code path in [ImagePreprocessor] (letterboxing, pixel
 * unpacking, float normalization, buffer reuse) run unmodified against real Android
 * [Bitmap]s supplied by Robolectric.
 */
// GraphicsMode.NATIVE is required (not just Robolectric's nominal default) for
// Canvas.drawBitmap(Bitmap, Matrix, Paint) to actually blit source pixels — under
// this project's test setup, without the explicit annotation, drawBitmap with a
// Matrix silently no-ops and leaves the destination bitmap untouched (still black
// from drawColor), which breaks every pixel-content assertion below even though
// dimension/reference/capacity assertions still pass.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePreprocessorTest {

    private lateinit var imageProxy: ImageProxy

    @Before
    fun setUp() {
        imageProxy = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun stubOriginal(bitmap: Bitmap) {
        every { imageProxy.toBitmap() } returns bitmap
    }

    @Test
    fun `preprocess returns 640x640 letterboxed bitmap and original bitmap unchanged`() {
        val src = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        stubOriginal(src)

        val result = ImagePreprocessor.preprocess(imageProxy)

        assertEquals(640, result.letterboxed.width)
        assertEquals(640, result.letterboxed.height)
        assertTrue(result.original === src)
        assertEquals(320, result.original.width)
        assertEquals(240, result.original.height)
    }

    @Test
    fun `preprocess normalizes rgb channels to 0 to 1 range for pure red image`() {
        val src = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        stubOriginal(src)

        val result = ImagePreprocessor.preprocess(imageProxy)
        val floatBuf = result.rgbNormalized.asFloatBuffer()

        // Center pixel (no letterbox padding since src already fills 640x640) —
        // pure red should normalize to (1.0, 0.0, 0.0).
        val centerPixelIndex = (320 * 640 + 320) * 3
        val r = floatBuf.get(centerPixelIndex)
        val g = floatBuf.get(centerPixelIndex + 1)
        val b = floatBuf.get(centerPixelIndex + 2)

        assertTrue("red channel expected ~1.0 but was $r", abs(r - 1.0f) < 0.01f)
        assertTrue("green channel expected ~0.0 but was $g", abs(g) < 0.01f)
        assertTrue("blue channel expected ~0.0 but was $b", abs(b) < 0.01f)
    }

    @Test
    fun `preprocess produces black padding pixels for non-square source via letterbox`() {
        // A tall narrow image forces horizontal letterbox padding (black bars).
        val src = Bitmap.createBitmap(100, 640, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.WHITE)
        stubOriginal(src)

        val result = ImagePreprocessor.preprocess(imageProxy)
        val floatBuf = result.rgbNormalized.asFloatBuffer()

        // Top-left corner pixel (0,0) must fall in the black letterbox padding
        // since scale = 640/640=1 for height, width scaled down leaving pad on x.
        val cornerIndex = 0
        val r = floatBuf.get(cornerIndex)
        val g = floatBuf.get(cornerIndex + 1)
        val b = floatBuf.get(cornerIndex + 2)

        assertTrue("expected black padding at corner, got r=$r g=$g b=$b", r < 0.05f && g < 0.05f && b < 0.05f)
    }

    @Test
    fun `preprocess buffer has capacity for exactly 640x640x3 floats`() {
        val src = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.BLUE)
        stubOriginal(src)

        val result = ImagePreprocessor.preprocess(imageProxy)

        // 640*640*3 floats * 4 bytes = 4,915,200 bytes.
        assertEquals(640 * 640 * 3 * 4, result.rgbNormalized.capacity())
    }

    @Test
    fun `preprocess rewinds buffer position to zero for downstream readers`() {
        val src = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.GREEN)
        stubOriginal(src)

        val result = ImagePreprocessor.preprocess(imageProxy)

        assertEquals(0, result.rgbNormalized.position())
    }

    @Test
    fun `preprocess reuses the same scratch buffer instance across frames`() {
        val src1 = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        src1.eraseColor(Color.RED)
        stubOriginal(src1)
        val result1 = ImagePreprocessor.preprocess(imageProxy)

        val src2 = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        src2.eraseColor(Color.BLUE)
        stubOriginal(src2)
        val result2 = ImagePreprocessor.preprocess(imageProxy)

        // Documented behavior: scratch ByteBuffer/Bitmap are reused, not
        // reallocated, across frames.
        assertTrue(result1.rgbNormalized === result2.rgbNormalized)
    }

    @Test
    fun `preprocess overwrites previous frame data with new frame values`() {
        val redSrc = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        redSrc.eraseColor(Color.RED)
        stubOriginal(redSrc)
        ImagePreprocessor.preprocess(imageProxy)

        val blueSrc = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        blueSrc.eraseColor(Color.BLUE)
        stubOriginal(blueSrc)
        val result = ImagePreprocessor.preprocess(imageProxy)

        val floatBuf = result.rgbNormalized.asFloatBuffer()
        val centerIndex = (320 * 640 + 320) * 3
        val r = floatBuf.get(centerIndex)
        val g = floatBuf.get(centerIndex + 1)
        val b = floatBuf.get(centerIndex + 2)

        assertTrue("expected blue frame data, got r=$r g=$g b=$b", r < 0.01f && g < 0.01f && abs(b - 1.0f) < 0.01f)
    }

    @Test
    fun `preprocess handles a 1x1 source bitmap without crashing`() {
        val src = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.MAGENTA)
        stubOriginal(src)

        val result = ImagePreprocessor.preprocess(imageProxy)

        assertEquals(640, result.letterboxed.width)
        assertEquals(640, result.letterboxed.height)
    }
}
