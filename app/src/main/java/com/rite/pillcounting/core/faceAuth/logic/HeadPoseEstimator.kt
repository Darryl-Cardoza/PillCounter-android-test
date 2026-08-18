package com.rite.pillcounting.core.faceAuth.logic

import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.faceAuth.model.FaceGuidance
import javax.inject.Inject
import kotlin.math.abs

/**
 * Estimates left/right head yaw from a detected face's landmarks and checks it
 * against the pose a [FaceCaptureAngle] registration step is asking for.
 *
 * Description:
 * Registration today labels a captured frame with whichever angle the step
 * counter is on, with no check that the user's head is actually turned that
 * way. This estimates yaw cheaply from the 5 landmarks [FaceEngine] already
 * returns (no extra model), so auto-capture can reject a frame whose pose
 * doesn't match its step.
 *
 * What it does:
 * - [estimateYaw] reads the nose's horizontal position relative to the two
 *   eyes: centered = frontal, shifted toward one eye = turned that way.
 * - [matchesAngle] / [closeness] compare that yaw against a per-angle target
 *   and tolerance.
 *
 * Calibration note:
 * The sign of [estimateYaw] (which way a positive value means "turned") and
 * whether front-camera frames need mirroring correction are not verifiable
 * from source alone — they depend on this device/API's actual `ImageAnalysis`
 * output. Verify by logging [estimateYaw] while manually turning left/right
 * on-device, then adjust [MIRROR_FRONT_CAMERA_YAW] and the target/tolerance
 * constants below to match.
 */
class HeadPoseEstimator @Inject constructor() {

    companion object {
        /** Landmark index of the right-eye x coordinate (see [com.rite.pillcounting.core.faceAuth.model.FaceBox]). */
        private const val RIGHT_EYE_X = 0
        private const val LEFT_EYE_X = 2
        private const val NOSE_X = 4

        /** Target |yaw| for a fully-turned TILT_LEFT/TILT_RIGHT pose. Needs on-device calibration. */
        const val YAW_TILT_TARGET = 0.35f

        /** Allowed yaw deviation from center for FRONT. Needs on-device calibration. */
        const val YAW_TOLERANCE_FRONT = 0.12f

        /** Allowed yaw deviation from [YAW_TILT_TARGET] for TILT_LEFT/TILT_RIGHT. Needs on-device calibration. */
        const val YAW_TOLERANCE_TILT = 0.18f

        /** Whether a front-camera frame's yaw sign needs flipping. Needs on-device calibration. */
        const val MIRROR_FRONT_CAMERA_YAW = true
    }

    /**
     * Estimates signed head yaw from nose-to-eyes horizontal asymmetry.
     *
     * Description:
     * A face turned to its own left shows more of its left side, shifting the
     * nose (in image coordinates) away from the left eye and closer to the
     * right eye, and vice versa. Comparing those two distances gives a cheap
     * yaw proxy without a dedicated pose model.
     *
     * @param landmarks 10 floats from [com.rite.pillcounting.core.faceAuth.model.FaceBox.landmarks]:
     *   right-eye(x,y), left-eye(x,y), nose(x,y), right-mouth(x,y), left-mouth(x,y).
     * @return Signed yaw, roughly in `[-1, 1]`; `0` is frontal. `0` is also returned
     *   for a degenerate landmark set (nose exactly between both eyes at the same point).
     *
     * Example Usage:
     * val yaw = headPoseEstimator.estimateYaw(face.landmarks)
     */
    fun estimateYaw(landmarks: FloatArray): Float {
        val noseX = landmarks[NOSE_X]
        val distToRightEye = abs(noseX - landmarks[RIGHT_EYE_X])
        val distToLeftEye = abs(noseX - landmarks[LEFT_EYE_X])
        val denom = distToLeftEye + distToRightEye
        if (denom < 1e-3f) return 0f
        return (distToLeftEye - distToRightEye) / denom
    }

    /**
     * Checks whether [yaw] is close enough to what [angle] expects.
     *
     * @param yaw A value from [estimateYaw].
     * @param angle The registration step being captured for.
     * @param isFrontCamera Whether the frame came from the front camera (see [MIRROR_FRONT_CAMERA_YAW]).
     * @return true if [yaw] falls within that angle's tolerance of its target.
     *
     * Example Usage:
     * if (headPoseEstimator.matchesAngle(yaw, FaceCaptureAngle.TILT_LEFT, isFrontCamera = true)) { ... }
     */
    fun matchesAngle(yaw: Float, angle: FaceCaptureAngle, isFrontCamera: Boolean): Boolean =
        deviation(yaw, angle, isFrontCamera) <= toleranceFor(angle)

    /**
     * Scores how close [yaw] is to [angle]'s target, for ranking candidate frames.
     *
     * @param yaw A value from [estimateYaw].
     * @param angle The registration step being captured for.
     * @param isFrontCamera Whether the frame came from the front camera.
     * @return `1.0` for a perfect match, decreasing to `0.0` at the tolerance boundary or beyond.
     *
     * Example Usage:
     * val closeness = headPoseEstimator.closeness(yaw, angle, isFrontCamera)
     */
    fun closeness(yaw: Float, angle: FaceCaptureAngle, isFrontCamera: Boolean): Float {
        val tolerance = toleranceFor(angle)
        return 1f - (deviation(yaw, angle, isFrontCamera) / tolerance).coerceIn(0f, 1f)
    }

    /**
     * Hint for why [angle]'s pose check is currently failing.
     *
     * @param angle The registration step being captured for.
     * @return The [FaceGuidance] the UI should show for that angle.
     *
     * Example Usage:
     * val hint = headPoseEstimator.guidanceFor(FaceCaptureAngle.TILT_LEFT)
     */
    fun guidanceFor(angle: FaceCaptureAngle): FaceGuidance = when (angle) {
        FaceCaptureAngle.FRONT -> FaceGuidance.LOOK_STRAIGHT
        FaceCaptureAngle.TILT_LEFT -> FaceGuidance.TILT_MORE_LEFT
        FaceCaptureAngle.TILT_RIGHT -> FaceGuidance.TILT_MORE_RIGHT
    }

    private fun deviation(yaw: Float, angle: FaceCaptureAngle, isFrontCamera: Boolean): Float {
        val effectiveYaw = if (isFrontCamera && MIRROR_FRONT_CAMERA_YAW) -yaw else yaw
        return abs(effectiveYaw - idealYawFor(angle))
    }

    private fun idealYawFor(angle: FaceCaptureAngle): Float = when (angle) {
        FaceCaptureAngle.FRONT -> 0f
        FaceCaptureAngle.TILT_LEFT -> -YAW_TILT_TARGET
        FaceCaptureAngle.TILT_RIGHT -> YAW_TILT_TARGET
    }

    private fun toleranceFor(angle: FaceCaptureAngle): Float = when (angle) {
        FaceCaptureAngle.FRONT -> YAW_TOLERANCE_FRONT
        FaceCaptureAngle.TILT_LEFT, FaceCaptureAngle.TILT_RIGHT -> YAW_TOLERANCE_TILT
    }
}
