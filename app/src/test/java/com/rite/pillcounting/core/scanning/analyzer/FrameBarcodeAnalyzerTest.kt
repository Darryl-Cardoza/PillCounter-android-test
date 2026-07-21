package com.rite.pillcounting.core.scanning.analyzer

import android.content.Context
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FrameBarcodeAnalyzer].
 *
 * The class's core decode path runs through MLKit's [com.google.mlkit.vision.barcode.BarcodeScanner],
 * which is constructed lazily inside the class and returns Google Play Services Tasks that cannot
 * be driven from a plain JVM test without an on-device/emulator environment. These tests therefore
 * cover everything reachable without invoking the scanner: the pause/resume/close state machine,
 * and the early-return guard branches in [FrameBarcodeAnalyzer.analyze] (released, paused, throttled)
 * which are checked BEFORE the frame ever reaches MLKit — i.e. before `imageProxy.toBitmap()` /
 * `scanner.process()` would be invoked. This is verified by asserting the callback is never invoked
 * and the ImageProxy's frame data is never touched in those cases.
 */
class FrameBarcodeAnalyzerTest {

    private val appContext: Context = mockk(relaxed = true)

    private fun newAnalyzer(
        enableFocusChangeDebounce: Boolean = false,
        minIntervalMs: Long = 250L,
    ) = FrameBarcodeAnalyzer(
        appContext = appContext,
        enableFocusChangeDebounce = enableFocusChangeDebounce,
        minIntervalMs = minIntervalMs,
    )

    private fun mockImageProxy(): ImageProxy {
        val proxy: ImageProxy = mockk(relaxed = true)
        val imageInfo = mockk<androidx.camera.core.ImageInfo>(relaxed = true)
        every { proxy.imageInfo } returns imageInfo
        every { imageInfo.rotationDegrees } returns 0
        // A relaxed mock's toBitmap() would otherwise return a non-null mock Bitmap
        // (relaxed mocks never return null for object-returning methods), which lets
        // execution reach InputImage.fromBitmap() -> MLKit, which isn't initialized on
        // the plain JVM and throws IllegalStateException. Production code's analyze()
        // already catches any toBitmap() exception and treats it as a null snapshot
        // (see the try/catch around imageProxy.toBitmap()), so forcing it to throw here
        // deterministically reproduces the "no MLKit available" path these tests need.
        every { proxy.toBitmap() } throws IllegalStateException("toBitmap unavailable on plain JVM")
        return proxy
    }

    // ---------- pause / resume ----------

    @Test
    fun `analyze drops frame and never fires callback when paused`() {
        val analyzer = newAnalyzer()
        analyzer.pause()
        val proxy = mockImageProxy()
        var fired = false

        analyzer.analyze(proxy) { _, _ -> fired = true }

        assertFalse(fired)
        // Guard branch returns before touching imageInfo/rotation at all.
        verify(exactly = 0) { proxy.imageInfo }
    }

    @Test
    fun `resume clears paused state so a subsequent frame is not dropped for that reason`() {
        val analyzer = newAnalyzer()
        analyzer.pause()
        analyzer.resume()
        val proxy = mockImageProxy()
        var fired = false

        analyzer.analyze(proxy) { _, _ -> fired = true }

        // Frame proceeds past the paused-check (imageInfo IS read once throttle/processing
        // gates are passed and the (mocked, thus null) bitmap snapshot path is reached).
        // We only assert it did not take the "paused" early return, i.e. rotation was read.
        verify(atLeast = 1) { proxy.imageInfo }
        assertFalse(fired) // toBitmap() isn't mocked/available -> falls back to null bitmap, no fire.
    }

    @Test
    fun `resume is safe to call when analyzer was never paused`() {
        val analyzer = newAnalyzer()

        analyzer.resume()

        // No exception; state remains usable for a following analyze() call.
        val proxy = mockImageProxy()
        var fired = false
        analyzer.analyze(proxy) { _, _ -> fired = true }
        assertFalse(fired)
    }

    // ---------- throttle ----------

    @Test
    fun `second frame within minIntervalMs is dropped by throttle without firing`() {
        val analyzer = newAnalyzer(minIntervalMs = 250L)
        val proxy1 = mockImageProxy()
        val proxy2 = mockImageProxy()
        var fireCount = 0

        analyzer.analyze(proxy1) { _, _ -> fireCount++ }
        // Immediately-following frame is within the 250ms throttle window.
        analyzer.analyze(proxy2) { _, _ -> fireCount++ }

        // The second call must be dropped before reading imageInfo (throttle check
        // happens prior to the bitmap snapshot / imageInfo access).
        verify(exactly = 0) { proxy2.imageInfo }
        assertFalse(fireCount > 0)
    }

    // ---------- released / close ----------

    @Test
    fun `analyze is a no-op after close`() {
        val analyzer = newAnalyzer()
        analyzer.close()
        val proxy = mockImageProxy()
        var fired = false

        analyzer.analyze(proxy) { _, _ -> fired = true }

        assertFalse(fired)
        verify(exactly = 0) { proxy.imageInfo }
    }

    @Test
    fun `close is idempotent and safe to call multiple times`() {
        val analyzer = newAnalyzer()

        analyzer.close()
        analyzer.close() // must not throw on second call

        val proxy = mockImageProxy()
        var fired = false
        analyzer.analyze(proxy) { _, _ -> fired = true }
        assertFalse(fired)
    }

    @Test
    fun `pause after close still allows close to remain idempotent`() {
        val analyzer = newAnalyzer()
        analyzer.close()

        // pause()/resume() after close should not throw even though released.
        analyzer.pause()
        analyzer.resume()

        assertTrue(true) // reaching here means no exception was thrown
    }

    // ---------- construction with focus-change debounce ----------

    @Test
    fun `analyzer constructed with focus-change debounce enabled still drops paused frames`() {
        val analyzer = newAnalyzer(enableFocusChangeDebounce = true, minIntervalMs = 100L)
        analyzer.pause()
        val proxy = mockImageProxy()
        var fired = false

        analyzer.analyze(proxy) { _, _ -> fired = true }

        assertFalse(fired)
        verify(exactly = 0) { proxy.imageInfo }
    }
}
