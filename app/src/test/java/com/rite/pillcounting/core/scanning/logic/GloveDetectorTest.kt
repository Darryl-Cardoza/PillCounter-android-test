package com.rite.pillcounting.core.scanning.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * [GloveDetector] is a singleton `object` whose public [GloveDetector.detect] entry point
 * requires a real TFLite [org.tensorflow.lite.Interpreter], real [android.graphics.Bitmap]/
 * [android.graphics.Canvas] drawing, and internal lazily-allocated scratch buffers tied to a
 * real GPU/CPU TFLite runtime — none of which is available on the plain JVM, and none of
 * which can be faked with MockK without reimplementing the native TFLite delegate. Driving
 * `detect()` here would not exercise real behavior, only a mock's canned answers.
 *
 * Instead, this test exercises the pure, deterministic math helpers (`sigmoid`, `iou`,
 * `nmsClassWise`) via reflection — no production code was modified to do this; the helpers
 * are called as-is through their existing private visibility. These are the only pieces of
 * decode/NMS logic in the file that don't require a live model or Android framework classes,
 * and they carry the real numerical behavior (confidence scoring, box overlap, per-class NMS)
 * that downstream detection quality depends on.
 */
class GloveDetectorTest {

    private fun sigmoidMethod(): Method =
        GloveDetector::class.java.getDeclaredMethod("sigmoid", Float::class.java)
            .apply { isAccessible = true }

    private fun iouMethod(): Method {
        val decodedBoxClass = Class.forName(
            "com.rite.pillcounting.core.scanning.logic.GloveDetector\$DecodedBox"
        )
        return GloveDetector::class.java.getDeclaredMethod("iou", decodedBoxClass, decodedBoxClass)
            .apply { isAccessible = true }
    }

    private fun nmsMethod(): Method =
        GloveDetector::class.java.getDeclaredMethod(
            "nmsClassWise", MutableList::class.java, Float::class.java
        ).apply { isAccessible = true }

    private fun decodedBoxClass() =
        Class.forName("com.rite.pillcounting.core.scanning.logic.GloveDetector\$DecodedBox")

    private fun newDecodedBox(
        x1: Float, y1: Float, x2: Float, y2: Float, score: Float, classId: Int
    ): Any {
        val ctor = decodedBoxClass().getDeclaredConstructor(
            Float::class.java, Float::class.java, Float::class.java, Float::class.java,
            Float::class.java, Int::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(x1, y1, x2, y2, score, classId)
    }

    private fun invokeSigmoid(x: Float): Float =
        sigmoidMethod().invoke(GloveDetector, x) as Float

    private fun invokeIou(a: Any, b: Any): Float =
        iouMethod().invoke(GloveDetector, a, b) as Float

    @Suppress("UNCHECKED_CAST")
    private fun invokeNms(boxes: MutableList<Any>, iouThreshold: Float): List<Any> =
        nmsMethod().invoke(GloveDetector, boxes, iouThreshold) as List<Any>

    // ── sigmoid ─────────────────────────────────────────────────────────

    @Test
    fun `sigmoid of zero is exactly one half`() {
        assertEquals(0.5f, invokeSigmoid(0f), 1e-6f)
    }

    @Test
    fun `sigmoid approaches one for large positive input`() {
        val result = invokeSigmoid(20f)
        assertTrue("expected near 1.0 but was $result", abs(result - 1f) < 1e-6f)
    }

    @Test
    fun `sigmoid approaches zero for large negative input`() {
        val result = invokeSigmoid(-20f)
        assertTrue("expected near 0.0 but was $result", result < 1e-6f)
    }

    @Test
    fun `sigmoid is symmetric around zero point five`() {
        val pos = invokeSigmoid(3.2f)
        val neg = invokeSigmoid(-3.2f)
        assertEquals(1f, pos + neg, 1e-5f)
    }

    @Test
    fun `sigmoid stays within zero one bounds for extreme values`() {
        val hi = invokeSigmoid(1000f)
        val lo = invokeSigmoid(-1000f)
        assertTrue(hi in 0f..1f)
        assertTrue(lo in 0f..1f)
    }

    // ── iou ─────────────────────────────────────────────────────────────

    @Test
    fun `iou of identical boxes is one`() {
        val a = newDecodedBox(0f, 0f, 10f, 10f, 0.9f, 0)
        val b = newDecodedBox(0f, 0f, 10f, 10f, 0.8f, 0)
        assertEquals(1f, invokeIou(a, b), 1e-6f)
    }

    @Test
    fun `iou of non overlapping boxes is zero`() {
        val a = newDecodedBox(0f, 0f, 10f, 10f, 0.9f, 0)
        val b = newDecodedBox(20f, 20f, 30f, 30f, 0.8f, 0)
        assertEquals(0f, invokeIou(a, b))
    }

    @Test
    fun `iou of partially overlapping boxes matches expected ratio`() {
        // a: [0,0]-[10,10] area=100; b: [5,5]-[15,15] area=100
        // intersection: [5,5]-[10,10] area=25; union = 100+100-25=175
        val a = newDecodedBox(0f, 0f, 10f, 10f, 0.9f, 0)
        val b = newDecodedBox(5f, 5f, 15f, 15f, 0.8f, 0)
        val expected = 25f / 175f
        assertEquals(expected, invokeIou(a, b), 1e-5f)
    }

    @Test
    fun `iou of degenerate zero area box is zero`() {
        val a = newDecodedBox(0f, 0f, 0f, 0f, 0.9f, 0)
        val b = newDecodedBox(0f, 0f, 10f, 10f, 0.8f, 0)
        assertEquals(0f, invokeIou(a, b))
    }

    @Test
    fun `iou of touching but not overlapping boxes is zero`() {
        val a = newDecodedBox(0f, 0f, 10f, 10f, 0.9f, 0)
        val b = newDecodedBox(10f, 0f, 20f, 10f, 0.8f, 0)
        assertEquals(0f, invokeIou(a, b))
    }

    // ── nmsClassWise ─────────────────────────────────────────────────────

    @Test
    fun `nms returns empty list for empty input`() {
        val result = invokeNms(mutableListOf(), 0.45f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `nms suppresses lower score overlapping box of same class`() {
        val high = newDecodedBox(0f, 0f, 10f, 10f, 0.9f, 0)
        val low = newDecodedBox(1f, 1f, 11f, 11f, 0.5f, 0)  // heavy overlap, same class
        val result = invokeNms(mutableListOf(high, low), 0.45f)
        assertEquals(1, result.size)
        assertEquals(high, result[0])
    }

    @Test
    fun `nms keeps both boxes of different classes even if overlapping`() {
        val classA = newDecodedBox(0f, 0f, 10f, 10f, 0.9f, 0)
        val classB = newDecodedBox(1f, 1f, 11f, 11f, 0.85f, 1)  // overlaps heavily but different class
        val result = invokeNms(mutableListOf(classA, classB), 0.45f)
        assertEquals(2, result.size)
    }

    @Test
    fun `nms keeps both non overlapping boxes of same class`() {
        val a = newDecodedBox(0f, 0f, 10f, 10f, 0.9f, 0)
        val b = newDecodedBox(50f, 50f, 60f, 60f, 0.7f, 0)
        val result = invokeNms(mutableListOf(a, b), 0.45f)
        assertEquals(2, result.size)
    }

    @Test
    fun `nms result is sorted descending by score`() {
        val low = newDecodedBox(50f, 50f, 60f, 60f, 0.5f, 1)
        val high = newDecodedBox(0f, 0f, 10f, 10f, 0.95f, 0)
        val mid = newDecodedBox(100f, 100f, 110f, 110f, 0.7f, 0)
        val result = invokeNms(mutableListOf(low, high, mid), 0.45f)
        assertEquals(3, result.size)
        val scoreField = decodedBoxClass().getDeclaredField("score").apply { isAccessible = true }
        val scores = result.map { scoreField.get(it) as Float }
        assertEquals(listOf(0.95f, 0.7f, 0.5f), scores)
    }
}
