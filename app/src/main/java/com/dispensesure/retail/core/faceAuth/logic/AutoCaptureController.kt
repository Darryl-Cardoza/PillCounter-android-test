package com.dispensesure.retail.core.faceAuth.logic

import android.graphics.Bitmap
import com.dispensesure.retail.core.faceAuth.model.FaceCaptureAngle
import com.dispensesure.retail.core.faceAuth.model.FaceGuidance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import javax.inject.Inject

/**
 * Watches a live frame stream for one registration angle and picks the best
 * frame to embed, instead of trusting a single manually-tapped frame.
 *
 * Description:
 * Runs each incoming frame through the same detect + quality-gate checks the
 * manual tap flow already uses, plus a [HeadPoseEstimator] check that the face
 * is actually turned the way [angle] expects. A frame clearing all three must
 * then hold that pose for several frames in a row — one stray frame is not a
 * pose — before it is eligible at all. Survivors are scored (pose-closeness +
 * sharpness) and the best one seen is kept. Once
 * a candidate exists, [run] keeps sampling for a short "settle window" in case
 * a better frame shows up; once that window passes with no improvement, it
 * commits the best candidate and the returned flow completes. If nothing ever
 * clears the gates, no window ever starts and the flow just keeps emitting
 * [CaptureEvent.Guidance] from whatever's currently failing — it never fails
 * outright.
 *
 * What it does:
 * - [run] is the single entry point: one call per angle, against a fresh
 *   controller state (call it again for the next angle).
 */
class AutoCaptureController @Inject constructor(
    private val faceEngine: FaceEngine,
    private val faceQualityGate: FaceQualityGate,
    private val headPoseEstimator: HeadPoseEstimator
) {
    companion object {
        /** Minimum spacing between processed frames — the "frame limit" that keeps this off the full camera frame rate. */
        const val MIN_FRAME_INTERVAL_MS = 150L

        /** How long to keep sampling for a better frame after the first candidate, before committing the best one. */
        const val SETTLE_WINDOW_MS = 900L

        private const val SHARPNESS_WEIGHT = 0.4f
        private const val CLOSENESS_WEIGHT = 0.6f

        /** How long a user may struggle with one angle before the hint gets firmer. */
        const val ESCALATE_AFTER_MS = 8_000L
    }

    /** Overridable for tests; defaults to the real wall clock. */
    var clock: () -> Long = System::currentTimeMillis

    /** What [run] emits while watching for a good frame. */
    sealed interface CaptureEvent {
        /** A live hint for what's currently wrong (move closer, tilt more, ...). */
        data class Guidance(val guidance: FaceGuidance) : CaptureEvent

        /**
         * The best frame found has been selected; its embedding and source bitmap are ready.
         *
         * @param steadyYaw Mirrored reading averaged over every frame that passed this step.
         *   One frame is a noisy sample of where someone's head rests; the average is not.
         */
        data class Committed(
            val embedding: FloatArray,
            val bitmap: Bitmap,
            val steadyYaw: Float = 0f
        ) : CaptureEvent

        /** The tracked face was lost, so nothing captured so far can be trusted to be one person. */
        data object TrackBroken : CaptureEvent
    }

    private data class Candidate(val embedding: FloatArray, val bitmap: Bitmap, val score: Float)

    /**
     * Samples [frames] for [angle] until a best frame is committed.
     *
     * @param angle Which registration step this is capturing for.
     * @param frames A live bitmap stream (e.g. from the camera's frame analysis).
     * @param isFrontCamera Whether [frames] comes from the front camera (affects pose matching).
     * @param baselineYaw The user's own FRONT yaw, from [HeadPoseEstimator.effectiveYaw]; 0 while FRONT itself is being captured.
     * @param trackGate Shared across all three angles of one enrollment, so a person swap
     *   between steps is caught. Null disables the check.
     * @return A flow of [CaptureEvent]; completes right after its one [CaptureEvent.Committed]
     *   or [CaptureEvent.TrackBroken].
     *
     * Example Usage:
     * autoCaptureController.run(FaceCaptureAngle.FRONT, cameraFrames, isFrontCamera = true)
     *     .collect { event -> ... }
     */
    fun run(
        angle: FaceCaptureAngle,
        frames: Flow<Bitmap>,
        isFrontCamera: Boolean,
        baselineYaw: Float = 0f,
        trackGate: FaceTrackContinuityGate? = null
    ): Flow<CaptureEvent> {
        var best: Candidate? = null
        var deadline: Long? = null
        // 0L, not Long.MIN_VALUE: `now - lastProcessedAt` below overflows a signed Long
        // when lastProcessedAt starts at MIN_VALUE, wrapping to a huge negative number
        // that's always < MIN_FRAME_INTERVAL_MS — every frame was silently dropped, forever.
        var lastProcessedAt = 0L
        var startedAt: Long? = null
        var passedYawSum = 0f
        var passedYawCount = 0
        val stability = PoseStabilityTracker()

        return frames.transformWhile { bitmap ->
            val now = clock()
            if (startedAt == null) startedAt = now
            val currentDeadline = deadline
            if (currentDeadline != null && now >= currentDeadline) {
                val winner = best!!
                // A candidate only exists after a passing frame, so the count is never 0 here.
                emit(CaptureEvent.Committed(winner.embedding, winner.bitmap, passedYawSum / passedYawCount))
                return@transformWhile false
            }

            if (now - lastProcessedAt < MIN_FRAME_INTERVAL_MS) return@transformWhile true
            lastProcessedAt = now

            val face = faceEngine.detectPrimary(bitmap)

            // Detection only: a blurry face is still the same face.
            trackGate?.observe(face?.rect)
            if (trackGate?.isBroken == true) {
                emit(CaptureEvent.TrackBroken)
                return@transformWhile false
            }

            if (face == null) {
                stability.record(posePassed = false)
                emit(CaptureEvent.Guidance(FaceGuidance.NO_FACE))
                return@transformWhile true
            }

            val rejection = faceQualityGate.evaluate(bitmap, face)
            if (rejection != null) {
                stability.record(posePassed = false)
                emit(CaptureEvent.Guidance(rejection))
                return@transformWhile true
            }

            val yaw = headPoseEstimator.estimateYaw(face.landmarks)
            val posePassed = headPoseEstimator.matchesAngle(yaw, angle, isFrontCamera, baselineYaw)
            val stable = stability.record(posePassed)

            if (!posePassed) {
                val escalated = now - startedAt!! >= ESCALATE_AFTER_MS
                emit(CaptureEvent.Guidance(headPoseEstimator.guidanceFor(angle, escalated)))
                return@transformWhile true
            }
            passedYawSum += headPoseEstimator.effectiveYaw(yaw, isFrontCamera)
            passedYawCount++
            // The pose is right but hasn't held yet — say nothing and let the user keep still.
            if (!stable) return@transformWhile true

            val sharpness = faceQualityGate.sharpnessScore(bitmap, face) ?: 0.0
            val score = scoreOf(sharpness, yaw, angle, isFrontCamera, baselineYaw)
            if (best == null || score > best!!.score) {
                best = Candidate(faceEngine.embed(bitmap, face), bitmap, score)
                deadline = now + SETTLE_WINDOW_MS
            }
            true
        }
    }

    private fun scoreOf(
        sharpness: Double,
        yaw: Float,
        angle: FaceCaptureAngle,
        isFrontCamera: Boolean,
        baselineYaw: Float
    ): Float {
        val normalizedSharpness = (sharpness / (2.0 * FaceQualityGate.ENROLL_MIN_SHARPNESS)).coerceIn(0.0, 1.0)
        val closeness = headPoseEstimator.closeness(yaw, angle, isFrontCamera, baselineYaw)
        return CLOSENESS_WEIGHT * closeness + SHARPNESS_WEIGHT * normalizedSharpness.toFloat()
    }
}
