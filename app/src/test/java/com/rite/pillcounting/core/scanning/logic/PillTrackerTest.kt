package com.rite.pillcounting.core.scanning.logic

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// PillTracker's association uses real RectF width/height math inside iou(); on the
// plain JVM this module's isReturnDefaultValues=true returns 0f and breaks it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PillTrackerTest {

    private fun det(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float,
        classId: Int = 0
    ): Detection = Detection(RectF(left, top, right, bottom), confidence, classId)

    private fun pill(confidence: Float, classId: Int = 0) =
        det(0f, 0f, 10f, 10f, confidence, classId)

    @Test
    fun `update confirms nothing on the first frame`() {
        val tracker = PillTracker()
        assertTrue(tracker.update(listOf(pill(0.90f))).isEmpty())
    }

    @Test
    fun `update confirms a track after two consecutive frames above enter score`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        val confirmed = tracker.update(listOf(pill(0.90f)))
        assertEquals(1, confirmed.size)
        assertEquals(0.90f, confirmed[0].confidence, 1e-6f)
    }

    @Test
    fun `update does not confirm when the second frame is below enter score`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        // 0.40 is above keep score, so the track lives, but the enter streak resets.
        assertTrue(tracker.update(listOf(pill(0.40f))).isEmpty())
        // One more high frame is only streak 1 again, still unconfirmed.
        assertTrue(tracker.update(listOf(pill(0.90f))).isEmpty())
        assertEquals(1, tracker.update(listOf(pill(0.90f))).size)
    }

    @Test
    fun `update keeps a confirmed track alive at keep score`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        tracker.update(listOf(pill(0.90f)))
        val stillThere = tracker.update(listOf(pill(0.36f)))
        assertEquals(1, stillThere.size)
    }

    @Test
    fun `update coasts a confirmed track for two missed frames and drops it on the third`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        tracker.update(listOf(pill(0.90f)))
        assertEquals(1, tracker.update(emptyList()).size)
        assertEquals(1, tracker.update(emptyList()).size)
        assertTrue(tracker.update(emptyList()).isEmpty())
    }

    @Test
    fun `update associates a detection to the track with the highest IoU`() {
        val tracker = PillTracker()
        // Two well-separated tracks, both confirmed.
        tracker.update(listOf(det(0f, 0f, 10f, 10f, 0.90f), det(100f, 0f, 110f, 10f, 0.90f)))
        tracker.update(listOf(det(0f, 0f, 10f, 10f, 0.90f), det(100f, 0f, 110f, 10f, 0.90f)))
        // A single nudged detection near the first track must not spawn a third.
        val result = tracker.update(listOf(det(1f, 1f, 11f, 11f, 0.90f)))
        assertEquals(2, result.size)
    }

    @Test
    fun `update spawns a new track when IoU is below the association threshold`() {
        val tracker = PillTracker()
        tracker.update(listOf(det(0f, 0f, 10f, 10f, 0.90f)))
        tracker.update(listOf(det(0f, 0f, 10f, 10f, 0.90f)))
        // IoU of [0,0,10,10] vs [8,0,18,10] = 20 / 180 = 0.11, below TRACK_IOU 0.30.
        tracker.update(listOf(det(8f, 0f, 18f, 10f, 0.90f)))
        val result = tracker.update(listOf(det(8f, 0f, 18f, 10f, 0.90f)))
        // Original track has now missed 2 frames so it still coasts: 2 confirmed.
        assertEquals(2, result.size)
    }

    @Test
    fun `update ignores detections below keep score`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.34f)))
        assertTrue(tracker.update(listOf(pill(0.34f))).isEmpty())
    }

    @Test
    fun `update carries the latest classId on a confirmed track`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f, classId = 0)))
        val confirmed = tracker.update(listOf(pill(0.90f, classId = 2)))
        assertEquals(1, confirmed.size)
        assertEquals(2, confirmed[0].classId)
    }

    @Test
    fun `update requires the two enter frames to be consecutive`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        // A missed frame breaks the streak, so the next hit is streak 1 again.
        assertTrue(tracker.update(emptyList()).isEmpty())
        assertTrue(tracker.update(listOf(pill(0.90f))).isEmpty())
        assertEquals(1, tracker.update(listOf(pill(0.90f))).size)
    }

    @Test
    fun `update does not start a track for a detection below enter score`() {
        val tracker = PillTracker()
        // Two borderline frames must leave no track behind at all.
        tracker.update(listOf(pill(0.40f)))
        tracker.update(listOf(pill(0.40f)))
        // So the first solid frame is streak 1, not streak 2.
        assertTrue(tracker.update(listOf(pill(0.90f))).isEmpty())
        assertEquals(1, tracker.update(listOf(pill(0.90f))).size)
    }

    @Test
    fun `update ages out a candidate that keeps matching below enter score`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        // Three borderline matches take the candidate to the exit threshold.
        tracker.update(listOf(pill(0.40f)))
        tracker.update(listOf(pill(0.40f)))
        tracker.update(listOf(pill(0.40f)))
        // The candidate is gone, so a single solid frame starts over at streak 1.
        assertTrue(tracker.update(listOf(pill(0.90f))).isEmpty())
        assertEquals(1, tracker.update(listOf(pill(0.90f))).size)
    }

    @Test
    fun `reset drops every track so confirmation starts over`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        tracker.update(listOf(pill(0.90f)))
        tracker.reset()
        assertTrue(tracker.update(listOf(pill(0.90f))).isEmpty())
    }
}
