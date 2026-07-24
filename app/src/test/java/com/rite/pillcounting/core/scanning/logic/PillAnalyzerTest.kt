package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tensorflow.lite.Interpreter
import java.lang.reflect.Method

/**
 * Unit tests for [PillAnalyzer].
 *
 * The `analyze()` entry point drives TFLite [Interpreter]s, [ImagePreprocessor],
 * [TraySegmentationDetector], [GloveDetector], [TrayColorDetector] and static
 * [Letterbox] state — none of which can be meaningfully exercised on the plain
 * JVM without an instrumented/Robolectric TFLite stack, so it is out of scope
 * here (see final report). This suite covers the analyzer's pure, side-effect
 * free private logic (`applyHysteresis`, `iouRect`) via reflection plus every
 * public member that doesn't require running the model pipeline:
 * `hasGloveInterpreter` and `resetGloveCadence()`.
 *
 * Run under Robolectric: `iouRect`/`applyHysteresis` call real RectF methods
 * (width/height/intersects-style field math) which are stubbed to return
 * default values (0f) under this module's isReturnDefaultValues=true test
 * option on the plain JVM, silently breaking every IoU-based assertion below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PillAnalyzerTest {

    private val pillInterpreter: Interpreter = mockk(relaxed = true)
    private val gloveInterpreter: Interpreter = mockk(relaxed = true)
    private val performanceLogger: PerformanceLogger = mockk(relaxed = true)

    private lateinit var onResultCalls: MutableList<Any>

    private fun newAnalyzer(
        glove: Interpreter? = gloveInterpreter,
        tray: TraySegmentationDetector? = null
    ): PillAnalyzer {
        onResultCalls = mutableListOf()
        return PillAnalyzer(
            pillInterpreter = pillInterpreter,
            traySegDetector = tray,
            gloveInterpreter = glove,
            performanceLogger = performanceLogger,
            shouldRunGloveDetection = { true },
            shouldDetectTrayColor = { false },
            onResult = { pillCount, pills, trayRects, gloveDetections, debugBitmap, transformMatrix, w, h ->
                onResultCalls.add(listOf(pillCount, pills, trayRects, gloveDetections, debugBitmap, transformMatrix, w, h))
            }
        )
    }

    // android.graphics.RectF has pure Kotlin/Java field math (no native calls),
    // so a real instance can be constructed directly on the plain JVM.
    private fun realRect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(left, top, right, bottom)

    private fun applyHysteresis(analyzer: PillAnalyzer, candidates: List<Detection>): List<Detection> {
        val m: Method = PillAnalyzer::class.java.getDeclaredMethod(
            "applyHysteresis",
            List::class.java
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(analyzer, candidates) as List<Detection>
    }

    private fun setPreviousFrameRects(analyzer: PillAnalyzer, rects: List<RectF>) {
        val f = PillAnalyzer::class.java.getDeclaredField("previousFramePillRects")
        f.isAccessible = true
        f.set(analyzer, rects)
    }

    private fun iouRect(analyzer: PillAnalyzer, a: RectF, b: RectF): Float {
        val m: Method = PillAnalyzer::class.java.getDeclaredMethod(
            "iouRect",
            RectF::class.java,
            RectF::class.java
        )
        m.isAccessible = true
        return m.invoke(analyzer, a, b) as Float
    }

    // ── hasGloveInterpreter ──────────────────────────────────────────────

    @Test
    fun `hasGloveInterpreter is true when glove interpreter provided`() {
        val analyzer = newAnalyzer(glove = gloveInterpreter)
        assertTrue(analyzer.hasGloveInterpreter)
    }

    @Test
    fun `hasGloveInterpreter is false when glove interpreter is null`() {
        val analyzer = newAnalyzer(glove = null)
        assertFalse(analyzer.hasGloveInterpreter)
    }

    // ── resetGloveCadence ────────────────────────────────────────────────

    @Test
    fun `resetGloveCadence clears hysteresis history so next hysteresis call requires full confidence`() {
        val analyzer = newAnalyzer()
        // Seed "previous frame" state as if a pill had been tracked.
        setPreviousFrameRects(analyzer, listOf(realRect(0f, 0f, 10f, 10f)))

        val borderline = Detection(rect = realRect(0f, 0f, 10f, 10f), confidence = 0.40f)
        // Before reset: borderline candidate overlapping previous frame is kept.
        val beforeReset = applyHysteresis(analyzer, listOf(borderline))
        assertEquals(1, beforeReset.size)

        analyzer.resetGloveCadence()

        // After reset, previousFramePillRects is cleared, so the same borderline
        // candidate (below PILL_CONF_ENTER, no previous overlap) is dropped.
        val afterReset = applyHysteresis(analyzer, listOf(borderline))
        assertTrue(afterReset.isEmpty())
    }

    @Test
    fun `resetGloveCadence resets internal cadence fields to initial values`() {
        val analyzer = newAnalyzer()
        val hasDetectedField = PillAnalyzer::class.java.getDeclaredField("hasDetectedAnyGlove")
        hasDetectedField.isAccessible = true
        hasDetectedField.set(analyzer, true)

        val lastRunField = PillAnalyzer::class.java.getDeclaredField("lastGloveRunMs")
        lastRunField.isAccessible = true
        lastRunField.set(analyzer, 12345L)

        val cachedField = PillAnalyzer::class.java.getDeclaredField("cachedGloveDetections")
        cachedField.isAccessible = true
        cachedField.set(analyzer, listOf(mockk<GloveDetection>(relaxed = true)))

        analyzer.resetGloveCadence()

        assertEquals(false, hasDetectedField.get(analyzer))
        assertEquals(0L, lastRunField.get(analyzer))
        @Suppress("UNCHECKED_CAST")
        assertTrue((cachedField.get(analyzer) as List<GloveDetection>).isEmpty())
    }

    // ── applyHysteresis ──────────────────────────────────────────────────

    @Test
    fun `applyHysteresis returns empty list unchanged for empty candidates`() {
        val analyzer = newAnalyzer()
        assertTrue(applyHysteresis(analyzer, emptyList()).isEmpty())
    }

    @Test
    fun `applyHysteresis keeps high confidence candidate regardless of overlap`() {
        val analyzer = newAnalyzer()
        setPreviousFrameRects(analyzer, emptyList())
        val highConf = Detection(rect = realRect(0f, 0f, 5f, 5f), confidence = 0.50f)
        val result = applyHysteresis(analyzer, listOf(highConf))
        assertEquals(listOf(highConf), result)
    }

    @Test
    fun `applyHysteresis drops borderline candidate with no previous-frame overlap`() {
        val analyzer = newAnalyzer()
        setPreviousFrameRects(analyzer, emptyList())
        val borderline = Detection(rect = realRect(0f, 0f, 5f, 5f), confidence = 0.40f)
        val result = applyHysteresis(analyzer, listOf(borderline))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `applyHysteresis keeps borderline candidate overlapping previous frame pill above IoU threshold`() {
        val analyzer = newAnalyzer()
        // Identical rect -> IoU = 1.0, well above HYSTERESIS_IOU (0.40).
        setPreviousFrameRects(analyzer, listOf(realRect(0f, 0f, 10f, 10f)))
        val borderline = Detection(rect = realRect(0f, 0f, 10f, 10f), confidence = 0.40f)
        val result = applyHysteresis(analyzer, listOf(borderline))
        assertEquals(1, result.size)
    }

    @Test
    fun `applyHysteresis drops borderline candidate whose overlap is below IoU threshold`() {
        val analyzer = newAnalyzer()
        // Prev rect only slightly overlaps -> IoU well under 0.40.
        setPreviousFrameRects(analyzer, listOf(realRect(9f, 9f, 20f, 20f)))
        val borderline = Detection(rect = realRect(0f, 0f, 10f, 10f), confidence = 0.40f)
        val result = applyHysteresis(analyzer, listOf(borderline))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `applyHysteresis filters mixed candidates keeping only qualifying ones`() {
        val analyzer = newAnalyzer()
        setPreviousFrameRects(analyzer, listOf(realRect(0f, 0f, 10f, 10f)))
        val highConf = Detection(rect = realRect(100f, 100f, 110f, 110f), confidence = 0.9f)
        val borderlineOverlap = Detection(rect = realRect(0f, 0f, 10f, 10f), confidence = 0.40f)
        val borderlineNoOverlap = Detection(rect = realRect(200f, 200f, 210f, 210f), confidence = 0.40f)
        val tooLowEvenWithOverlap = Detection(rect = realRect(0f, 0f, 10f, 10f), confidence = 0.10f)

        val result = applyHysteresis(
            analyzer,
            listOf(highConf, borderlineOverlap, borderlineNoOverlap, tooLowEvenWithOverlap)
        )

        // tooLowEvenWithOverlap: conf 0.10 is below PILL_CONF_STAY decode threshold in
        // real pipeline, but applyHysteresis itself only checks ENTER/overlap, so a
        // low-conf candidate with overlap is still kept by this function in isolation.
        assertEquals(3, result.size)
        assertTrue(result.contains(highConf))
        assertTrue(result.contains(borderlineOverlap))
        assertFalse(result.contains(borderlineNoOverlap))
    }

    // ── iouRect ──────────────────────────────────────────────────────────

    @Test
    fun `iouRect returns 1 for identical rects`() {
        val analyzer = newAnalyzer()
        val a = realRect(0f, 0f, 10f, 10f)
        val b = realRect(0f, 0f, 10f, 10f)
        assertEquals(1.0f, iouRect(analyzer, a, b), 0.0001f)
    }

    @Test
    fun `iouRect returns 0 for non-overlapping rects`() {
        val analyzer = newAnalyzer()
        val a = realRect(0f, 0f, 10f, 10f)
        val b = realRect(20f, 20f, 30f, 30f)
        assertEquals(0.0f, iouRect(analyzer, a, b), 0.0001f)
    }

    @Test
    fun `iouRect computes partial overlap correctly`() {
        val analyzer = newAnalyzer()
        // a: 10x10 = 100 area. b: 10x10 = 100 area, offset by 5 in both axes.
        // Intersection: 5x5 = 25. Union = 100 + 100 - 25 = 175. IoU = 25/175.
        val a = realRect(0f, 0f, 10f, 10f)
        val b = realRect(5f, 5f, 15f, 15f)
        val expected = 25f / 175f
        assertEquals(expected, iouRect(analyzer, a, b), 0.0001f)
    }

    @Test
    fun `iouRect returns 0 when rects touch at edge only (zero-area intersection)`() {
        val analyzer = newAnalyzer()
        val a = realRect(0f, 0f, 10f, 10f)
        val b = realRect(10f, 0f, 20f, 10f)
        assertEquals(0.0f, iouRect(analyzer, a, b), 0.0001f)
    }
}
