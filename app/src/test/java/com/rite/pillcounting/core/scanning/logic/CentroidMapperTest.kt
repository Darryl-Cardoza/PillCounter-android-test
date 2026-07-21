package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// RectF/PointF methods (centerX/centerY, etc.) are backed by the android.jar
// stub on the plain JVM; with this module's isReturnDefaultValues = true test
// option, calling them returns default values (0f) rather than performing the
// real field math, silently breaking every assertion below. RobolectricTestRunner
// provides real (shadowed) implementations so centerX()/centerY() behave as
// documented.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CentroidMapperTest {

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(left, top, right, bottom)

    private fun detection(left: Float, top: Float, right: Float, bottom: Float, confidence: Float = 0.9f) =
        Detection(rect = rectF(left, top, right, bottom), confidence = confidence)

    @Test
    fun `maps centroid to preview space with equal scale`() {
        val det = detection(0f, 0f, 100f, 100f)
        val result = CentroidMapper.toPreview(
            detection = det,
            imageWidth = 200,
            imageHeight = 200,
            previewWidth = 200,
            previewHeight = 200
        )
        // centerX = 50, centerY = 50, scale = 1 -> unchanged
        assertEquals(50f, result.x, 0.001f)
        assertEquals(50f, result.y, 0.001f)
    }

    @Test
    fun `scales centroid up when preview is larger than image`() {
        val det = detection(0f, 0f, 100f, 100f) // center (50, 50)
        val result = CentroidMapper.toPreview(
            detection = det,
            imageWidth = 100,
            imageHeight = 100,
            previewWidth = 200,
            previewHeight = 400
        )
        // scaleX = 2, scaleY = 4
        assertEquals(100f, result.x, 0.001f)
        assertEquals(200f, result.y, 0.001f)
    }

    @Test
    fun `scales centroid down when preview is smaller than image`() {
        val det = detection(0f, 0f, 100f, 100f) // center (50, 50)
        val result = CentroidMapper.toPreview(
            detection = det,
            imageWidth = 200,
            imageHeight = 200,
            previewWidth = 50,
            previewHeight = 50
        )
        // scaleX = scaleY = 0.25
        assertEquals(12.5f, result.x, 0.001f)
        assertEquals(12.5f, result.y, 0.001f)
    }

    @Test
    fun `handles asymmetric scale factors independently for x and y`() {
        val det = detection(10f, 20f, 30f, 60f) // center (20, 40)
        val result = CentroidMapper.toPreview(
            detection = det,
            imageWidth = 100,
            imageHeight = 200,
            previewWidth = 50,
            previewHeight = 800
        )
        // scaleX = 0.5, scaleY = 4
        assertEquals(10f, result.x, 0.001f)
        assertEquals(160f, result.y, 0.001f)
    }

    @Test
    fun `handles zero-size detection rect as a point`() {
        val det = detection(15f, 25f, 15f, 25f) // degenerate rect, center = (15, 25)
        val result = CentroidMapper.toPreview(
            detection = det,
            imageWidth = 150,
            imageHeight = 250,
            previewWidth = 300,
            previewHeight = 500
        )
        // scaleX = 2, scaleY = 2
        assertEquals(30f, result.x, 0.001f)
        assertEquals(50f, result.y, 0.001f)
    }

    @Test
    fun `handles negative rect coordinates`() {
        val det = detection(-100f, -50f, 0f, 0f) // center (-50, -25)
        val result = CentroidMapper.toPreview(
            detection = det,
            imageWidth = 100,
            imageHeight = 100,
            previewWidth = 100,
            previewHeight = 100
        )
        assertEquals(-50f, result.x, 0.001f)
        assertEquals(-25f, result.y, 0.001f)
    }

    @Test
    fun `handles preview size of zero producing zero scale`() {
        val det = detection(0f, 0f, 100f, 100f) // center (50, 50)
        val result = CentroidMapper.toPreview(
            detection = det,
            imageWidth = 100,
            imageHeight = 100,
            previewWidth = 0,
            previewHeight = 0
        )
        assertEquals(0f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `produces infinite scale when image dimension is zero`() {
        val det = detection(0f, 0f, 10f, 10f) // center (5, 5)
        val result = CentroidMapper.toPreview(
            detection = det,
            imageWidth = 0,
            imageHeight = 100,
            previewWidth = 200,
            previewHeight = 200
        )
        // scaleX = 200/0 = Infinity, cxImage(5) * Infinity = Infinity
        assertEquals(Float.POSITIVE_INFINITY, result.x)
        // scaleY = 200/100 = 2 -> 5*2 = 10
        assertEquals(10f, result.y, 0.001f)
    }
}
