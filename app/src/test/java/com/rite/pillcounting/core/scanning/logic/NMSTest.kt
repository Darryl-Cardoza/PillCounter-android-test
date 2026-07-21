package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// RectF's real methods (width/height/intersect math used inside NMS.iou()) are
// backed by the android.jar stub on the plain JVM; with this module's
// isReturnDefaultValues = true test option, calling them returns default
// values (0f) instead of doing real field math, which silently breaks IoU
// computation. RobolectricTestRunner provides real (shadowed) RectF behavior.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NMSTest {

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(left, top, right, bottom)

    private fun detection(rect: RectF, confidence: Float): Detection =
        Detection(rect = rect, confidence = confidence)

    @Test
    fun `run returns empty list when input is empty`() {
        val result = NMS.run(emptyList(), iouThreshold = 0.5f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `run keeps single detection unchanged`() {
        val det = detection(rectF(0f, 0f, 10f, 10f), 0.9f)
        val result = NMS.run(listOf(det), iouThreshold = 0.5f)
        assertEquals(listOf(det), result)
    }

    @Test
    fun `run suppresses lower confidence overlapping detection`() {
        val high = detection(rectF(0f, 0f, 10f, 10f), 0.9f)
        val low = detection(rectF(0f, 0f, 10f, 10f), 0.5f) // identical rect, IoU = 1.0
        val result = NMS.run(listOf(low, high), iouThreshold = 0.5f)
        assertEquals(listOf(high), result)
    }

    @Test
    fun `run keeps both detections when non-overlapping`() {
        val a = detection(rectF(0f, 0f, 10f, 10f), 0.9f)
        val b = detection(rectF(100f, 100f, 110f, 110f), 0.8f)
        val result = NMS.run(listOf(a, b), iouThreshold = 0.5f)
        assertEquals(2, result.size)
        assertTrue(result.containsAll(listOf(a, b)))
    }

    @Test
    fun `run keeps both detections when IoU exactly equals threshold`() {
        // Two rects of area 100 overlapping in a 50x100 region => intersection 5000?
        // build so IoU is exactly 1/3: a=[0,0,10,10] area100; b=[5,0,15,10] area100
        // intersection width=5,height=10 -> area=50; union=100+100-50=150; IoU=1/3
        val a = detection(rectF(0f, 0f, 10f, 10f), 0.9f)
        val b = detection(rectF(5f, 0f, 15f, 10f), 0.8f)
        val iouValue = 50f / 150f
        val result = NMS.run(listOf(a, b), iouThreshold = iouValue)
        // condition is strictly ">", so equal-to-threshold IoU must NOT suppress
        assertEquals(2, result.size)
    }

    @Test
    fun `run suppresses when IoU exceeds threshold by a small margin`() {
        val a = detection(rectF(0f, 0f, 10f, 10f), 0.9f)
        val b = detection(rectF(5f, 0f, 15f, 10f), 0.8f)
        val iouValue = 50f / 150f
        val result = NMS.run(listOf(a, b), iouThreshold = iouValue - 0.01f)
        assertEquals(listOf(a), result)
    }

    @Test
    fun `run returns zero IoU for rects that only touch at an edge`() {
        val a = detection(rectF(0f, 0f, 10f, 10f), 0.9f)
        val b = detection(rectF(10f, 0f, 20f, 10f), 0.8f) // touches at x=10, zero-width intersection
        val result = NMS.run(listOf(a, b), iouThreshold = 0f)
        // intersectionWidth = 0 <= 0f -> iou returns 0f, which is NOT > 0f threshold, so both kept
        assertEquals(2, result.size)
    }

    @Test
    fun `run sorts by confidence descending before suppression so highest confidence survives`() {
        val low = detection(rectF(0f, 0f, 10f, 10f), 0.1f)
        val mid = detection(rectF(0f, 0f, 10f, 10f), 0.5f)
        val high = detection(rectF(0f, 0f, 10f, 10f), 0.95f)
        val result = NMS.run(listOf(low, high, mid), iouThreshold = 0.1f)
        assertEquals(listOf(high), result)
    }

    @Test
    fun `run handles three overlapping detections keeping only the highest confidence`() {
        val a = detection(rectF(0f, 0f, 10f, 10f), 0.3f)
        val b = detection(rectF(1f, 1f, 11f, 11f), 0.95f)
        val c = detection(rectF(2f, 2f, 12f, 12f), 0.6f)
        val result = NMS.run(listOf(a, b, c), iouThreshold = 0.1f)
        assertEquals(listOf(b), result)
    }

    @Test
    fun `run with threshold of 1 keeps all detections unless fully identical overlap exceeds it`() {
        val a = detection(rectF(0f, 0f, 10f, 10f), 0.9f)
        val b = detection(rectF(0f, 0f, 10f, 10f), 0.5f) // IoU = 1.0, not > 1.0
        val result = NMS.run(listOf(a, b), iouThreshold = 1.0f)
        assertEquals(2, result.size)
    }

    @Test
    fun `run with zero area rect produces zero IoU and does not crash on division`() {
        val zeroArea = detection(rectF(5f, 5f, 5f, 5f), 0.9f)
        val normal = detection(rectF(0f, 0f, 10f, 10f), 0.8f)
        val result = NMS.run(listOf(zeroArea, normal), iouThreshold = 0.0f)
        // zero-width/height intersection -> iou() returns 0f early, not > 0f, both kept
        assertEquals(2, result.size)
    }
}
