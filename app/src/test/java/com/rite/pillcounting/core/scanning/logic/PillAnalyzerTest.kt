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

/**
 * Unit tests for [PillAnalyzer].
 *
 * The `analyze()` entry point drives TFLite [Interpreter]s, [ImagePreprocessor],
 * [TraySegmentationDetector], [GloveDetector], [TrayColorDetector] and static
 * [Letterbox] state — none of which can be meaningfully exercised on the plain
 * JVM without an instrumented/Robolectric TFLite stack, so it is out of scope
 * here (see final report). This suite covers every public member that doesn't
 * require running the model pipeline: `hasGloveInterpreter` and
 * `resetGloveCadence()`. The cross-frame pill logic itself lives in
 * [PillTracker] and [CountStabilizer] and is tested there.
 *
 * Run under Robolectric: the tracker reached through `resetGloveCadence` calls
 * real RectF methods (width/height field math) which are stubbed to return
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

    private fun trackerOf(analyzer: PillAnalyzer): PillTracker {
        val f = PillAnalyzer::class.java.getDeclaredField("pillTracker")
        f.isAccessible = true
        return f.get(analyzer) as PillTracker
    }

    private fun stabilizerOf(analyzer: PillAnalyzer): CountStabilizer {
        val f = PillAnalyzer::class.java.getDeclaredField("countStabilizer")
        f.isAccessible = true
        return f.get(analyzer) as CountStabilizer
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
    fun `resetGloveCadence clears the pill tracker so confirmation starts over`() {
        val analyzer = newAnalyzer()
        val tracker = trackerOf(analyzer)
        val pill = Detection(rect = realRect(0f, 0f, 10f, 10f), confidence = 0.90f)

        tracker.update(listOf(pill))
        // Second consecutive high-confidence frame would normally confirm.
        analyzer.resetGloveCadence()

        assertTrue(tracker.update(listOf(pill)).isEmpty())
    }

    @Test
    fun `resetGloveCadence clears the count stabilizer back to zero`() {
        val analyzer = newAnalyzer()
        val stabilizer = stabilizerOf(analyzer)
        stabilizer.update(6)
        stabilizer.update(6)
        assertEquals(6, stabilizer.update(6))

        analyzer.resetGloveCadence()

        assertEquals(0, stabilizer.update(6))
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
}
