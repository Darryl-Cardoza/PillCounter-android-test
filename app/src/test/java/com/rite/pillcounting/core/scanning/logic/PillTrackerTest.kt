package com.rite.pillcounting.core.scanning.logic

import android.graphics.PointF
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

    // ── one pill, one track ────────────────────────────────────────────

    @Test
    fun `a second box on an already tracked pill does not start a second track`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        tracker.update(listOf(pill(0.90f)))
        // IoU of [0,0,10,10] vs [4,0,14,10] = 60 / 140 = 0.43: past NMS at 0.5,
        // but at the association threshold, so it is the same pill seen twice.
        repeat(3) {
            val result = tracker.update(listOf(pill(0.90f), det(4f, 0f, 14f, 10f, 0.80f)))
            assertEquals(1, result.size)
        }
    }

    @Test
    fun `a small partial box inside a tracked pill does not start a second track`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        tracker.update(listOf(pill(0.90f)))
        // [0,0,4,10] is 40% of the pill: IoU 0.40 with the full box but fully
        // contained in it — a class-flip fragment, not another pill.
        repeat(3) {
            val result = tracker.update(listOf(pill(0.90f), det(0f, 0f, 4f, 10f, 0.85f, classId = 2)))
            assertEquals(1, result.size)
        }
    }

    @Test
    fun `two leftover boxes on one new pill start a single track`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f), det(4f, 0f, 14f, 10f, 0.80f)))
        val result = tracker.update(listOf(pill(0.90f), det(4f, 0f, 14f, 10f, 0.80f)))
        assertEquals(1, result.size)
    }

    @Test
    fun `a track superseded by a larger box on the same pill is retired instead of coasting`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        tracker.update(listOf(pill(0.90f)))
        // The box doubles in size (e.g. the input switched from the full frame to
        // the tray crop): IoU 100/400 = 0.25 fails association, so a new track starts.
        tracker.update(listOf(det(0f, 0f, 20f, 20f, 0.90f)))
        // Next frame the new track confirms and the old one, fully inside it, is a
        // ghost: it is retired instead of being counted for two more frames.
        val result = tracker.update(listOf(det(0f, 0f, 20f, 20f, 0.90f)))
        assertEquals(1, result.size)
    }

    @Test
    fun `two distinct touching pills are both tracked`() {
        val tracker = PillTracker()
        // Adjacent boxes with a 1 px overlap: IoU 10/190 = 0.05, containment 0.1.
        val frame = listOf(det(0f, 0f, 10f, 10f, 0.90f), det(9f, 0f, 19f, 10f, 0.90f))
        tracker.update(frame)
        assertEquals(2, tracker.update(frame).size)
        // One goes missing for a frame: it coasts, it is not a ghost of its neighbour.
        assertEquals(2, tracker.update(listOf(det(0f, 0f, 10f, 10f, 0.90f))).size)
    }

    // ── camera motion ─────────────────────────────────────────────────

    /** [count] pills in a row, 10 px boxes 30 px apart, all shifted right by [shift]. */
    private fun row(count: Int, shift: Float) =
        (0 until count).map { i -> det(i * 30f + shift, 0f, i * 30f + 10f + shift, 10f, 0.90f) }

    @Test
    fun `a camera pan that moves every pill keeps the existing tracks instead of spawning new ones`() {
        val tracker = PillTracker()
        tracker.update(row(5, 0f))
        assertEquals(5, tracker.update(row(5, 0f)).size)
        // Every box jumps 9 px per frame: IoU 1/19 with its own old box, far below
        // TRACK_IOU, yet the count must stay 5 — not ~10 from coasting + spawned
        // tracks, and not 0 from every track missing.
        assertEquals(5, tracker.update(row(5, 9f)).size)
        val result = tracker.update(row(5, 18f))
        assertEquals(5, result.size)
        // The tracks moved with the pan.
        assertEquals(18f, result.minOf { it.rect.left }, 0.01f)
    }

    @Test
    fun `a measured camera shift is applied before association even with a single pill`() {
        val tracker = PillTracker()
        tracker.update(listOf(pill(0.90f)))
        tracker.update(listOf(pill(0.90f)))
        // One pill is too few to vote for a shift on its own, and its box jumped
        // 9 px (IoU 1/19). Image registration says the whole frame moved 9 px, so
        // this is the same pill: the track follows it instead of coasting at 0.
        val result = tracker.update(listOf(det(9f, 0f, 19f, 10f, 0.90f)), PointF(9f, 0f))
        assertEquals(1, result.size)
        assertEquals(9f, result[0].rect.left, 0.01f)
    }

    @Test
    fun `a single pill moved by hand does not drag the steady tracks with it`() {
        val tracker = PillTracker()
        val steady = row(4, 0f)
        val fifthAt = { left: Float -> det(left, 0f, left + 10f, 10f, 0.90f) }
        tracker.update(steady + fifthAt(120f))
        assertEquals(5, tracker.update(steady + fifthAt(120f)).size)
        // The fifth pill slides 12 px while the other four stay put: the median
        // camera shift is zero, so the four steady tracks stay exactly in place.
        tracker.update(steady + fifthAt(132f))
        val result = tracker.update(steady + fifthAt(132f))
        val steadyTracks = result.filter { it.rect.left < 100f }
        assertEquals(4, steadyTracks.size)
        assertEquals(listOf(0f, 30f, 60f, 90f), steadyTracks.map { it.rect.left }.sorted())
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
