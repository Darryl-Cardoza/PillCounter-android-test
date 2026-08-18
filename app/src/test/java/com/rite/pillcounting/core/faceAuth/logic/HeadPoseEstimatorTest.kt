package com.rite.pillcounting.core.faceAuth.logic

import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `matchesAngle accepts yaw at the TILT_LEFT target on the back camera`() {
        val yaw = -HeadPoseEstimator.YAW_TILT_TARGET
        assertTrue(estimator.matchesAngle(yaw, FaceCaptureAngle.TILT_LEFT, isFrontCamera = false))
        assertEquals(1f, estimator.closeness(yaw, FaceCaptureAngle.TILT_LEFT, isFrontCamera = false), 1e-4f)
    }

    @Test
    fun `matchesAngle mirrors yaw for the front camera before comparing`() {
        // Raw yaw is the TILT_LEFT target's mirror image; on the front camera this
        // should read as a perfect TILT_RIGHT match once mirroring is applied.
        val rawYaw = -HeadPoseEstimator.YAW_TILT_TARGET
        assertTrue(estimator.matchesAngle(rawYaw, FaceCaptureAngle.TILT_RIGHT, isFrontCamera = true))
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
