package com.rite.pillcounting.core.scanning.logic

import android.graphics.Rect
import android.graphics.RectF
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tensorflow.lite.Interpreter
import java.util.BitSet

/**
 * Unit tests for [PillAnalyzer].
 *
 * The `analyze()` entry point drives TFLite [Interpreter]s, [ImagePreprocessor],
 * [TraySegmentationDetector], [GloveDetector], [TrayColorDetector] and static
 * [Letterbox] state — none of which can be meaningfully exercised on the plain
 * JVM without an instrumented/Robolectric TFLite stack, so it is out of scope
 * here (see final report). This suite covers the members that don't require
 * running the model pipeline: `hasGloveInterpreter`, `resetGloveCadence()` and
 * `trayCropRegion()`. The cross-frame pill logic itself lives in
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

    // ── isOnTray: the deploy-contract mask rule ──────────────────────────

    /**
     * An 8×8 mask in frame pixels (scale 1, no pad): columns 0–3 are CHUTE,
     * columns 4–7 are TRAY, so the chute wall runs between x=3 and x=4.
     */
    private fun trayAndChute(): List<TrayDetection> {
        val size = 8
        val tray = BitSet(size * size)
        val chute = BitSet(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            if (x >= 4) tray.set(y * size + x) else chute.set(y * size + x)
        }
        val info = Letterbox.ScaleInfo(scale = 1f, padX = 0f, padY = 0f, inputSize = size)
        return listOf(
            TrayDetection(realRect(4f, 0f, 8f, 8f), 1f, TrayClass.TRAY, tray, size, info),
            TrayDetection(realRect(0f, 0f, 4f, 8f), 1f, TrayClass.CHUTE, chute, size, info)
        )
    }

    @Test
    fun `isOnTray counts a pill resting on the tray side of the chute wall`() {
        val trays = trayAndChute()
        // Centre on tray pixel (4, 3), hard against the wall: six of the nine
        // samples are tray, three are chute, so it counts — and it keeps counting
        // however the argmax boundary wanders, because a wander moves one vote.
        assertTrue(PillAnalyzer.isOnTray(4.5f, 3.5f, trays, dilate = 1f))
    }

    @Test
    fun `isOnTray rejects a pill sitting in the chute against the wall`() {
        val trays = trayAndChute()
        // Centre on chute pixel (3, 3), one pixel the other side of the wall.
        // The dilation reaches the tray, but six of nine samples are chute, so
        // the chute wins. This is the pill the old dilation-only rule counted.
        assertFalse(PillAnalyzer.isOnTray(3.5f, 3.5f, trays, dilate = 1f))
        assertFalse(PillAnalyzer.isOnTray(3.5f, 3.5f, trays, dilate = 0f))
    }

    @Test
    fun `isOnTray rejects a pill deep in the chute`() {
        val trays = trayAndChute()
        assertFalse(PillAnalyzer.isOnTray(1.5f, 3.5f, trays, dilate = 1f))
    }

    @Test
    fun `isOnTray keeps the dilation where the tray meets background`() {
        val trays = trayAndChute()
        // Centre on the tray's outer column (7, 3): three samples fall off the
        // mask entirely. No chute votes, so tray still wins and a pill on the
        // rim is not dropped.
        assertTrue(PillAnalyzer.isOnTray(7.5f, 3.5f, trays, dilate = 1f))
    }

    @Test
    fun `isOnTray accepts a centre on the tray and rejects one off every mask`() {
        val trays = trayAndChute()
        assertTrue(PillAnalyzer.isOnTray(6.5f, 3.5f, trays, dilate = 0f))
        assertFalse(PillAnalyzer.isOnTray(20f, 20f, trays, dilate = 1f))
    }

    // ── trayCropRegion ───────────────────────────────────────────────────
    // TRAY_CROP_MARGIN is 0.05 per side, TRAY_CROP_MIN_SIDE is 64.

    private fun tray(
        left: Float, top: Float, right: Float, bottom: Float,
        cls: TrayClass = TrayClass.TRAY
    ) = TrayDetection(rect = realRect(left, top, right, bottom), confidence = 1f, cls = cls)

    @Test
    fun `trayCropRegion grows the tray box by the margin on every side`() {
        val analyzer = newAnalyzer()
        val region = analyzer.trayCropRegion(listOf(tray(100f, 100f, 300f, 300f)), 640, 480)
        assertEquals(Rect(90, 90, 310, 310), region)
    }

    @Test
    fun `trayCropRegion clamps the grown box to the frame`() {
        val analyzer = newAnalyzer()
        val region = analyzer.trayCropRegion(listOf(tray(-20f, -20f, 100f, 100f)), 640, 480)
        assertEquals(Rect(0, 0, 106, 106), region)
    }

    @Test
    fun `trayCropRegion returns null for a tray too small to be worth upscaling`() {
        val analyzer = newAnalyzer()
        assertNull(analyzer.trayCropRegion(listOf(tray(0f, 0f, 50f, 50f)), 640, 480))
    }

    @Test
    fun `trayCropRegion picks the largest tray when several are detected`() {
        val analyzer = newAnalyzer()
        val region = analyzer.trayCropRegion(
            listOf(tray(0f, 0f, 120f, 120f), tray(200f, 100f, 400f, 300f)),
            640, 480
        )
        assertEquals(Rect(190, 90, 410, 310), region)
    }

    @Test
    fun `trayCropRegion ignores chute detections`() {
        val analyzer = newAnalyzer()
        val region = analyzer.trayCropRegion(
            listOf(
                tray(100f, 100f, 300f, 300f),
                tray(400f, 100f, 600f, 300f, cls = TrayClass.CHUTE)
            ),
            640, 480
        )
        assertEquals(Rect(90, 90, 310, 310), region)
    }

    @Test
    fun `trayCropRegion returns null when no tray class detection is present`() {
        val analyzer = newAnalyzer()
        assertNull(
            analyzer.trayCropRegion(
                listOf(tray(100f, 100f, 300f, 300f, cls = TrayClass.CHUTE)),
                640, 480
            )
        )
    }
}
