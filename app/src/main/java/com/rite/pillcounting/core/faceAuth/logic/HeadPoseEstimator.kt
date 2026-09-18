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
 * - [matchesAngle] / [closeness] compare that yaw against what the step is
 *   asking for: an absolute tolerance for FRONT, and for the tilts a required
 *   change from the user's own FRONT reading, so one number means the same
 *   turn on every face at every camera height.
 *
 * Calibration note:
 * The sign of [estimateYaw] and [MIRROR_FRONT_CAMERA_YAW] were confirmed on
 * device — a turn the way the prompt asks is the turn that gets accepted. The
 * three delta constants come from a logged calibration pass; the working and
 * the numbers are in plans/face-detection-optimisation/.
 */
class HeadPoseEstimator @Inject constructor() {

    companion object {
        /** Landmark index of the right-eye x coordinate (see [com.rite.pillcounting.core.faceAuth.model.FaceBox]). */
        private const val RIGHT_EYE_X = 0
        private const val LEFT_EYE_X = 2
        private const val NOSE_X = 4

        /** Allowed yaw deviation from center for FRONT. */
        const val YAW_TOLERANCE_FRONT = 0.12f

        /**
         * Smallest change from the user's own FRONT reading that counts as a real turn.
         * Measured: failing frames reach 0.135 at the 75th percentile, with 16% of
         * samples in 0.10-0.17, so the old 0.17 floor sat inside the noise.
         */
        const val MIN_TILT_DELTA = 0.20f

        /** The turn the frame scoring prefers — a frame at this delta scores 1.0. Median measured turn. */
        const val IDEAL_TILT_DELTA = 0.34f

        /** Past this the landmarks stop being trustworthy. Largest genuine turn measured was 0.459. */
        const val MAX_TILT_DELTA = 0.65f

        /** Whether a front-camera frame's yaw sign needs flipping. Confirmed on device. */
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
     * Checks whether [yaw] is what [angle] is asking for.
     *
     * @param yaw A value from [estimateYaw].
     * @param angle The registration step being captured for.
     * @param isFrontCamera Whether the frame came from the front camera (see [MIRROR_FRONT_CAMERA_YAW]).
     * @param baselineYaw The user's own FRONT reading, from [effectiveYaw]; ignored for FRONT itself.
     * @return true when FRONT is centered enough, or when a tilt has turned far enough the right way without overshooting.
     *
     * Example Usage:
     * if (headPoseEstimator.matchesAngle(yaw, FaceCaptureAngle.TILT_LEFT, isFrontCamera = true, baselineYaw = frontYaw)) { ... }
     */
    fun matchesAngle(yaw: Float, angle: FaceCaptureAngle, isFrontCamera: Boolean, baselineYaw: Float = 0f): Boolean {
        if (angle == FaceCaptureAngle.FRONT) {
            return abs(effectiveYaw(yaw, isFrontCamera)) <= YAW_TOLERANCE_FRONT
        }
        val delta = signedDelta(yaw, isFrontCamera, baselineYaw)
        if (!turnedTowards(delta, angle)) return false
        return abs(delta) in MIN_TILT_DELTA..MAX_TILT_DELTA
    }

    /**
     * Scores how good [yaw] is for [angle], for ranking candidate frames.
     *
     * @param yaw A value from [estimateYaw].
     * @param angle The registration step being captured for.
     * @param isFrontCamera Whether the frame came from the front camera.
     * @param baselineYaw The user's own FRONT reading, from [effectiveYaw]; ignored for FRONT itself.
     * @return `1.0` at the ideal pose, falling to `0.0` at either edge of what [matchesAngle] accepts.
     *
     * Example Usage:
     * val closeness = headPoseEstimator.closeness(yaw, angle, isFrontCamera, baselineYaw)
     */
    fun closeness(yaw: Float, angle: FaceCaptureAngle, isFrontCamera: Boolean, baselineYaw: Float = 0f): Float {
        if (angle == FaceCaptureAngle.FRONT) {
            return 1f - (abs(effectiveYaw(yaw, isFrontCamera)) / YAW_TOLERANCE_FRONT).coerceIn(0f, 1f)
        }
        val delta = signedDelta(yaw, isFrontCamera, baselineYaw)
        if (!turnedTowards(delta, angle)) return 0f
        val magnitude = abs(delta)
        // Ramps up to the ideal turn then back down — an overshoot is as poor a frame as a shortfall.
        val ratio = if (magnitude <= IDEAL_TILT_DELTA) {
            (magnitude - MIN_TILT_DELTA) / (IDEAL_TILT_DELTA - MIN_TILT_DELTA)
        } else {
            (MAX_TILT_DELTA - magnitude) / (MAX_TILT_DELTA - IDEAL_TILT_DELTA)
        }
        return ratio.coerceIn(0f, 1f)
    }

    /**
     * Hint for why [angle]'s pose check is currently failing.
     *
     * @param angle The registration step being captured for.
     * @param escalated Whether the user has been stuck on this angle long enough to deserve a firmer instruction.
     * @return The [FaceGuidance] the UI should show for that angle.
     *
     * Example Usage:
     * val hint = headPoseEstimator.guidanceFor(FaceCaptureAngle.TILT_LEFT, escalated = true)
     */
    fun guidanceFor(angle: FaceCaptureAngle, escalated: Boolean = false): FaceGuidance = when (angle) {
        FaceCaptureAngle.FRONT -> FaceGuidance.LOOK_STRAIGHT
        FaceCaptureAngle.TILT_LEFT ->
            if (escalated) FaceGuidance.TILT_FURTHER_LEFT else FaceGuidance.TILT_MORE_LEFT
        FaceCaptureAngle.TILT_RIGHT ->
            if (escalated) FaceGuidance.TILT_FURTHER_RIGHT else FaceGuidance.TILT_MORE_RIGHT
    }

    /** TILT_LEFT is a negative change, TILT_RIGHT a positive one — the convention confirmed on device. */
    private fun turnedTowards(delta: Float, angle: FaceCaptureAngle): Boolean = when (angle) {
        FaceCaptureAngle.TILT_LEFT -> delta < 0f
        FaceCaptureAngle.TILT_RIGHT -> delta > 0f
        FaceCaptureAngle.FRONT -> true
    }

    /**
     * How far [yaw] has moved from a reference reading, in the frame's own orientation.
     *
     * Description:
     * A fixed yaw target means a different physical turn on every face and at
     * every camera height. Measuring the change from the user's own FRONT
     * reading takes that bias out.
     *
     * @param yaw A value from [estimateYaw].
     * @param isFrontCamera Whether the frame came from the front camera (see [MIRROR_FRONT_CAMERA_YAW]).
     * @param baselineYaw The reference reading — the user's own FRONT yaw.
     * @return Signed change from [baselineYaw]; negative is a turn toward TILT_LEFT.
     *
     * Example Usage:
     * val delta = headPoseEstimator.signedDelta(yaw, isFrontCamera = true, baselineYaw = frontYaw)
     */
    fun signedDelta(yaw: Float, isFrontCamera: Boolean, baselineYaw: Float): Float =
        effectiveYaw(yaw, isFrontCamera) - baselineYaw

    /**
     * Converts a raw [estimateYaw] reading into the frame's own orientation.
     *
     * Description:
     * Public because a baseline stored for later comparison must be stored in
     * this space, not raw — on the front camera the two have opposite signs, so
     * mixing them subtracts the baseline the wrong way round.
     *
     * @param yaw A value from [estimateYaw].
     * @param isFrontCamera Whether the frame came from the front camera (see [MIRROR_FRONT_CAMERA_YAW]).
     * @return The yaw as [signedDelta] and [matchesAngle] compare it.
     *
     * Example Usage:
     * baselineYaw = headPoseEstimator.effectiveYaw(frontCommitYaw, isFrontCamera = true)
     */
    fun effectiveYaw(yaw: Float, isFrontCamera: Boolean): Float =
        if (isFrontCamera && MIRROR_FRONT_CAMERA_YAW) -yaw else yaw

}
