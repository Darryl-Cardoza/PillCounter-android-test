package com.rite.pillcounting.core.faceAuth.logic

import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private fun landmarksOf(rightEyeX: Float, leftEyeX: Float, noseX: Float): FloatArray =
    floatArrayOf(rightEyeX, 0f, leftEyeX, 0f, noseX, 0f, 0f, 0f, 0f, 0f)

class HeadPoseEstimatorTest {

    private val estimator = HeadPoseEstimator()

    @Test
    fun `estimateYaw is zero when nose is centered between both eyes`() {
        val yaw = estimator.estimateYaw(landmarksOf(rightEyeX = 30f, leftEyeX = 70f, noseX = 50f))
        assertEquals(0f, yaw, 1e-4f)
    }

    @Test
    fun `estimateYaw is negative when nose shifts toward the left eye`() {
        val yaw = estimator.estimateYaw(landmarksOf(rightEyeX = 30f, leftEyeX = 70f, noseX = 65f))
        assertEquals(-0.75f, yaw, 1e-4f)
    }

    @Test
    fun `estimateYaw is positive when nose shifts toward the right eye`() {
        val yaw = estimator.estimateYaw(landmarksOf(rightEyeX = 30f, leftEyeX = 70f, noseX = 35f))
        assertEquals(0.75f, yaw, 1e-4f)
    }

    @Test
    fun `estimateYaw is zero for a degenerate landmark set`() {
        val yaw = estimator.estimateYaw(landmarksOf(rightEyeX = 50f, leftEyeX = 50f, noseX = 50f))
        assertEquals(0f, yaw, 1e-4f)
    }

    @Test
    fun `matchesAngle accepts a centered yaw for FRONT`() {
        assertTrue(estimator.matchesAngle(yaw = 0f, angle = FaceCaptureAngle.FRONT, isFrontCamera = false))
        assertEquals(1f, estimator.closeness(0f, FaceCaptureAngle.FRONT, isFrontCamera = false), 1e-4f)
    }

    @Test
    fun `matchesAngle rejects a strongly turned yaw for FRONT`() {
        assertFalse(estimator.matchesAngle(yaw = 0.5f, angle = FaceCaptureAngle.FRONT, isFrontCamera = false))
        assertEquals(0f, estimator.closeness(0.5f, FaceCaptureAngle.FRONT, isFrontCamera = false), 1e-4f)
    }

    @Test
    fun `matchesAngle accepts a full turn toward TILT_LEFT on the back camera`() {
        val yaw = -HeadPoseEstimator.IDEAL_TILT_DELTA
        assertTrue(estimator.matchesAngle(yaw, FaceCaptureAngle.TILT_LEFT, isFrontCamera = false))
        assertEquals(1f, estimator.closeness(yaw, FaceCaptureAngle.TILT_LEFT, isFrontCamera = false), 1e-4f)
    }

    @Test
    fun `matchesAngle mirrors yaw for the front camera before comparing`() {
        // Raw yaw is the TILT_LEFT turn's mirror image; on the front camera this
        // should read as a TILT_RIGHT match once mirroring is applied.
        val rawYaw = -HeadPoseEstimator.IDEAL_TILT_DELTA
        assertTrue(estimator.matchesAngle(rawYaw, FaceCaptureAngle.TILT_RIGHT, isFrontCamera = true))
        assertFalse(estimator.matchesAngle(rawYaw, FaceCaptureAngle.TILT_RIGHT, isFrontCamera = false))
    }

    @Test
    fun `a tilt is judged by how far the head moved from this person's own straight reading`() {
        // The same absolute yaw: a turn for someone whose forward reading is 0,
        // but no movement at all for someone whose forward reading is already there.
        val turned = HeadPoseEstimator.IDEAL_TILT_DELTA
        assertTrue(estimator.matchesAngle(turned, FaceCaptureAngle.TILT_RIGHT, isFrontCamera = false, baselineYaw = 0f))
        assertFalse(estimator.matchesAngle(turned, FaceCaptureAngle.TILT_RIGHT, isFrontCamera = false, baselineYaw = turned))
    }

    @Test
    fun `a turn short of the minimum is refused`() {
        val barely = HeadPoseEstimator.MIN_TILT_DELTA / 2f
        assertFalse(estimator.matchesAngle(barely, FaceCaptureAngle.TILT_RIGHT, isFrontCamera = false))
    }

    @Test
    fun `a turn past the maximum is refused`() {
        val tooFar = HeadPoseEstimator.MAX_TILT_DELTA * 1.5f
        assertFalse(estimator.matchesAngle(tooFar, FaceCaptureAngle.TILT_RIGHT, isFrontCamera = false))
    }

    @Test
    fun `a turn the wrong way never satisfies an angle`() {
        val rightward = HeadPoseEstimator.IDEAL_TILT_DELTA
        assertFalse(estimator.matchesAngle(rightward, FaceCaptureAngle.TILT_LEFT, isFrontCamera = false))
        assertTrue(estimator.matchesAngle(-rightward, FaceCaptureAngle.TILT_LEFT, isFrontCamera = false))
    }

    @Test
    fun `closeness peaks at the ideal turn and falls off on both sides`() {
        val ideal = estimator.closeness(HeadPoseEstimator.IDEAL_TILT_DELTA, FaceCaptureAngle.TILT_RIGHT, isFrontCamera = false)
        val shallow = estimator.closeness(
            (HeadPoseEstimator.MIN_TILT_DELTA + HeadPoseEstimator.IDEAL_TILT_DELTA) / 2f,
            FaceCaptureAngle.TILT_RIGHT, isFrontCamera = false
        )
        val deep = estimator.closeness(
            (HeadPoseEstimator.IDEAL_TILT_DELTA + HeadPoseEstimator.MAX_TILT_DELTA) / 2f,
            FaceCaptureAngle.TILT_RIGHT, isFrontCamera = false
        )
        assertEquals(1f, ideal, 1e-4f)
        assertTrue(shallow < ideal)
        assertTrue(deep < ideal)
    }

    @Test
    fun `signedDelta measures the change from the baseline`() {
        assertEquals(0.2f, estimator.signedDelta(0.3f, isFrontCamera = false, baselineYaw = 0.1f), 1e-4f)
        assertEquals(-0.4f, estimator.signedDelta(0.3f, isFrontCamera = true, baselineYaw = 0.1f), 1e-4f)
    }

    @Test
    fun `escalated guidance is a distinct hint from the gentle one`() {
        FaceCaptureAngle.entries.filter { it != FaceCaptureAngle.FRONT }.forEach { angle ->
            assertNotEquals(estimator.guidanceFor(angle), estimator.guidanceFor(angle, escalated = true))
        }
    }

    @Test
    fun `guidanceFor returns a distinct hint per angle`() {
        val hints = FaceCaptureAngle.entries.map { estimator.guidanceFor(it) }
        assertEquals(hints.size, hints.toSet().size)
    }

    @Test
    fun `closeness decreases monotonically as yaw moves away from target`() {
        val near = estimator.closeness(0.02f, FaceCaptureAngle.FRONT, isFrontCamera = false)
        val far = estimator.closeness(0.10f, FaceCaptureAngle.FRONT, isFrontCamera = false)
        assertTrue(near > far)
        assertTrue(abs(near - 1f) < abs(far - 1f))
    }
}
