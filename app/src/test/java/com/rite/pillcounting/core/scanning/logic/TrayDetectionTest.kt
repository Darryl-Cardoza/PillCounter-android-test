package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF
import java.util.BitSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// RectF's real methods (contains(), used by TrayDetection.containsPoint's rect
// fallback, plus field reads used by its equals()) are backed by the android.jar
// stub on the plain JVM; with this module's isReturnDefaultValues = true test
// option, those calls return default values (0f/false) instead of doing the
// real math, silently breaking every assertion below. RobolectricTestRunner
// provides real (shadowed) RectF behavior.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrayDetectionTest {

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(left, top, right, bottom)

    private fun scaleInfo(scale: Float = 1f, padX: Float = 0f, padY: Float = 0f, inputSize: Int = 384) =
        Letterbox.ScaleInfo(scale = scale, padX = padX, padY = padY, inputSize = inputSize)

    // ---- containsPoint: rect-based fallback (no mask) ----

    @Test
    fun `containsPoint uses rect bounds when mask is null`() {
        val det = TrayDetection(rect = rectF(10f, 10f, 100f, 100f), confidence = 0.9f)
        assertTrue(det.containsPoint(50, 50))
        assertTrue(det.containsPoint(10, 10)) // inclusive left/top boundary
        assertTrue(det.containsPoint(100, 100)) // inclusive right/bottom boundary
        assertFalse(det.containsPoint(9, 50))
        assertFalse(det.containsPoint(50, 101))
    }

    @Test
    fun `containsPoint falls back to rect when scaleInfo is null even with mask`() {
        val mask = BitSet(16)
        mask.set(0)
        val det = TrayDetection(
            rect = rectF(0f, 0f, 5f, 5f),
            confidence = 0.5f,
            mask = mask,
            maskSize = 4,
            scaleInfo = null
        )
        assertTrue(det.containsPoint(1, 1))
        assertFalse(det.containsPoint(20, 20))
    }

    @Test
    fun `containsPoint falls back to rect when maskSize is zero`() {
        val mask = BitSet(16)
        mask.set(0)
        val det = TrayDetection(
            rect = rectF(0f, 0f, 5f, 5f),
            confidence = 0.5f,
            mask = mask,
            maskSize = 0,
            scaleInfo = scaleInfo()
        )
        assertTrue(det.containsPoint(1, 1))
    }

    // ---- containsPoint: mask-based lookup ----

    @Test
    fun `containsPoint returns true when mapped mask bit is set`() {
        val maskSize = 4
        val mask = BitSet(maskSize * maskSize)
        // pixel (2,1) at scale=1, pad=0 maps to mask index (1*4 + 2) = 6
        mask.set(1 * maskSize + 2)
        val det = TrayDetection(
            rect = rectF(0f, 0f, 0f, 0f),
            confidence = 0.5f,
            mask = mask,
            maskSize = maskSize,
            scaleInfo = scaleInfo(scale = 1f, padX = 0f, padY = 0f)
        )
        assertTrue(det.containsPoint(2, 1))
    }

    @Test
    fun `containsPoint returns false when mapped mask bit is clear`() {
        val maskSize = 4
        val mask = BitSet(maskSize * maskSize)
        val det = TrayDetection(
            rect = rectF(0f, 0f, 0f, 0f),
            confidence = 0.5f,
            mask = mask,
            maskSize = maskSize,
            scaleInfo = scaleInfo()
        )
        assertFalse(det.containsPoint(0, 0))
    }

    @Test
    fun `containsPoint applies scale and padding when mapping to mask space`() {
        val maskSize = 8
        val mask = BitSet(maskSize * maskSize)
        // original point (10, 20), scale=0.5, padX=2, padY=1
        // xMask = 10*0.5+2 = 7, yMask = 20*0.5+1 = 11 -> out of bounds (>= 8) for y
        // choose values that land in bounds instead:
        // point (2, 4): xMask = 2*0.5+2=3, yMask=4*0.5+1=3 -> index 3*8+3=27
        mask.set(3 * maskSize + 3)
        val det = TrayDetection(
            rect = rectF(0f, 0f, 0f, 0f),
            confidence = 0.5f,
            mask = mask,
            maskSize = maskSize,
            scaleInfo = scaleInfo(scale = 0.5f, padX = 2f, padY = 1f)
        )
        assertTrue(det.containsPoint(2, 4))
    }

    @Test
    fun `containsPoint returns false when mapped coordinates are negative (out of bounds)`() {
        val maskSize = 4
        val mask = BitSet(maskSize * maskSize)
        mask.set(0, maskSize * maskSize) // all set, to prove bounds check short-circuits
        val det = TrayDetection(
            rect = rectF(0f, 0f, 100f, 100f),
            confidence = 0.5f,
            mask = mask,
            maskSize = maskSize,
            scaleInfo = scaleInfo(scale = 1f, padX = -10f, padY = 0f)
        )
        // xMask = 0*1 + (-10) = -10 -> out of bounds
        assertFalse(det.containsPoint(0, 0))
    }

    @Test
    fun `containsPoint returns false when mapped coordinates exceed maskSize`() {
        val maskSize = 4
        val mask = BitSet(maskSize * maskSize)
        mask.set(0, maskSize * maskSize)
        val det = TrayDetection(
            rect = rectF(0f, 0f, 100f, 100f),
            confidence = 0.5f,
            mask = mask,
            maskSize = maskSize,
            scaleInfo = scaleInfo(scale = 1f, padX = 0f, padY = 0f)
        )
        // xMask = 10 (>= maskSize 4) -> out of bounds
        assertFalse(det.containsPoint(10, 0))
    }

    @Test
    fun `containsPoint boundary at exact maskSize is out of bounds`() {
        val maskSize = 4
        val mask = BitSet(maskSize * maskSize)
        mask.set(0, maskSize * maskSize)
        val det = TrayDetection(
            rect = rectF(0f, 0f, 100f, 100f),
            confidence = 0.5f,
            mask = mask,
            maskSize = maskSize,
            scaleInfo = scaleInfo(scale = 1f, padX = 0f, padY = 0f)
        )
        assertFalse(det.containsPoint(4, 0)) // xMask == maskSize, excluded
        assertFalse(det.containsPoint(0, 4)) // yMask == maskSize, excluded
        assertTrue(det.containsPoint(3, 3)) // last valid index
    }

    // ---- default values ----

    @Test
    fun `default cls and trayColor and mask values apply when not specified`() {
        val det = TrayDetection(rect = rectF(0f, 0f, 1f, 1f), confidence = 0.1f)
        assertEquals(TrayClass.TRAY, det.cls)
        assertEquals(TrayColor.UNKNOWN, det.trayColor)
        assertEquals(0, det.maskSize)
    }

    @Test
    fun `cls can be set to CHUTE and trayColor can be customized`() {
        val det = TrayDetection(
            rect = rectF(0f, 0f, 1f, 1f),
            confidence = 0.7f,
            cls = TrayClass.CHUTE,
            trayColor = TrayColor.RED
        )
        assertEquals(TrayClass.CHUTE, det.cls)
        assertEquals(TrayColor.RED, det.trayColor)
    }

    // ---- equals / hashCode (custom overrides ignore mask/trayColor) ----

    @Test
    fun `equals returns true for same rect confidence and cls regardless of mask or trayColor`() {
        val rect = rectF(1f, 2f, 3f, 4f)
        val a = TrayDetection(rect = rect, confidence = 0.5f, cls = TrayClass.TRAY, trayColor = TrayColor.BLUE)
        val b = TrayDetection(rect = rect, confidence = 0.5f, cls = TrayClass.TRAY, trayColor = TrayColor.RED, mask = BitSet(4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals returns false when confidence differs`() {
        val rect = rectF(1f, 2f, 3f, 4f)
        val a = TrayDetection(rect = rect, confidence = 0.5f)
        val b = TrayDetection(rect = rect, confidence = 0.6f)
        assertNotEquals(a, b)
    }

    @Test
    fun `equals returns false when cls differs`() {
        val rect = rectF(1f, 2f, 3f, 4f)
        val a = TrayDetection(rect = rect, confidence = 0.5f, cls = TrayClass.TRAY)
        val b = TrayDetection(rect = rect, confidence = 0.5f, cls = TrayClass.CHUTE)
        assertNotEquals(a, b)
    }

    @Test
    fun `equals returns false when rect differs`() {
        val a = TrayDetection(rect = rectF(1f, 2f, 3f, 4f), confidence = 0.5f)
        val b = TrayDetection(rect = rectF(9f, 9f, 9f, 9f), confidence = 0.5f)
        assertNotEquals(a, b)
    }

    @Test
    fun `equals returns false when compared to a different type`() {
        val a = TrayDetection(rect = rectF(1f, 2f, 3f, 4f), confidence = 0.5f)
        assertFalse(a.equals("not a TrayDetection"))
    }

    @Test
    fun `equals is reflexive for the same instance`() {
        val a = TrayDetection(rect = rectF(1f, 2f, 3f, 4f), confidence = 0.5f)
        assertTrue(a.equals(a))
    }
}
