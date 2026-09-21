package com.rite.pillcounting.core.faceAuth.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseStabilityTrackerTest {

    @Test
    fun `a pose is not trusted until it has held for the required number of frames`() {
        val tracker = PoseStabilityTracker(requiredFrames = 3)
        assertFalse(tracker.record(posePassed = true))
        assertFalse(tracker.record(posePassed = true))
        assertTrue(tracker.record(posePassed = true))
    }

    @Test
    fun `one failing frame starts the count over`() {
        val tracker = PoseStabilityTracker(requiredFrames = 3)
        tracker.record(posePassed = true)
        tracker.record(posePassed = true)

        assertFalse(tracker.record(posePassed = false))
        assertEquals(0, tracker.streak)
        assertFalse(tracker.record(posePassed = true))
    }

    @Test
    fun `a single stray frame among hundreds of failures never satisfies the tracker`() {
        // Bug 1 in miniature: this is what holding still for a minute looked like —
        // ~400 sampled frames with the occasional jitter spike among them.
        val tracker = PoseStabilityTracker()
        repeat(400) { index ->
            assertFalse(tracker.record(posePassed = index == 200))
        }
    }

    @Test
    fun `it stays satisfied while the pose keeps holding`() {
        val tracker = PoseStabilityTracker(requiredFrames = 2)
        tracker.record(posePassed = true)

        assertTrue(tracker.record(posePassed = true))
        assertTrue(tracker.record(posePassed = true))
        assertEquals(3, tracker.streak)
    }
}
