package com.dispensesure.retail.core.faceAuth.logic

import android.graphics.RectF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for a real RectF: the plain-JUnit android.jar stubs (returnDefaultValues)
// no-op its constructor, leaving every box 0,0,0,0.
@RunWith(RobolectricTestRunner::class)
class FaceTrackContinuityGateTest {

    private fun box(left: Float, top: Float) = RectF(left, top, left + 200f, top + 260f)

    @Test
    fun `a face that stays put keeps the track`() {
        val gate = FaceTrackContinuityGate()
        gate.observe(box(100f, 100f))
        gate.arm()

        // Ordinary movement between frames 150ms apart.
        gate.observe(box(104f, 98f))
        gate.observe(box(110f, 103f))
        gate.observe(box(107f, 101f))

        assertFalse(gate.isBroken)
    }

    @Test
    fun `a box that jumps across the frame breaks the track`() {
        val gate = FaceTrackContinuityGate()
        gate.observe(box(100f, 100f))
        gate.arm()

        gate.observe(box(700f, 100f))

        assertTrue(gate.isBroken)
    }

    @Test
    fun `a brief dropout is forgiven`() {
        val gate = FaceTrackContinuityGate()
        gate.observe(box(100f, 100f))
        gate.arm()

        repeat(FaceTrackContinuityGate.MAX_TRACK_GAP_FRAMES) { gate.observe(null) }

        assertFalse(gate.isBroken)
    }

    @Test
    fun `a longer absence breaks the track`() {
        val gate = FaceTrackContinuityGate()
        gate.observe(box(100f, 100f))
        gate.arm()

        repeat(FaceTrackContinuityGate.MAX_TRACK_GAP_FRAMES + 1) { gate.observe(null) }

        assertTrue(gate.isBroken)
    }

    @Test
    fun `a face returning after the gap does not repair a broken track`() {
        val gate = FaceTrackContinuityGate()
        gate.observe(box(100f, 100f))
        gate.arm()
        repeat(FaceTrackContinuityGate.MAX_TRACK_GAP_FRAMES + 1) { gate.observe(null) }

        gate.observe(box(100f, 100f))

        // Whoever is here now cannot be shown to be whoever did the straight-on capture.
        assertTrue(gate.isBroken)
    }

    @Test
    fun `losing the face before arming is harmless`() {
        val gate = FaceTrackContinuityGate()

        // The straight-on step hasn't committed yet — there is nothing to protect.
        repeat(20) { gate.observe(null) }
        gate.observe(box(600f, 400f))

        assertFalse(gate.isBroken)
    }

    @Test
    fun `reset clears a broken track so the scan can start over`() {
        val gate = FaceTrackContinuityGate()
        gate.observe(box(100f, 100f))
        gate.arm()
        gate.observe(box(700f, 100f))
        assertTrue(gate.isBroken)

        gate.reset()

        assertFalse(gate.isBroken)
    }

    @Test
    fun `a person swap is what this catches`() {
        // Person A leaves, a beat of nothing, person B steps in somewhere else.
        val gate = FaceTrackContinuityGate()
        gate.observe(box(100f, 120f))
        gate.arm()
        gate.observe(box(102f, 118f))

        repeat(6) { gate.observe(null) }
        gate.observe(box(480f, 160f))

        assertTrue(gate.isBroken)
    }
}
