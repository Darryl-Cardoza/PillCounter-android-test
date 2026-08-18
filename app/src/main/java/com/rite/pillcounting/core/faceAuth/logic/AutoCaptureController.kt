package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.Bitmap
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.faceAuth.model.FaceGuidance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import javax.inject.Inject

/**
 * Watches a live frame stream for one registration angle and picks the best
 * frame to embed, instead of trusting a single manually-tapped frame.
 *
 * Description:
 * Runs each incoming frame through the same detect + quality-gate checks the
 * manual tap flow already uses, plus a new [HeadPoseEstimator] check that the
 * face is actually turned the way [angle] expects. Frames clearing all three
 * are scored (pose-closeness + sharpness) and the best one seen is kept. Once
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
    }

    /** Overridable for tests; defaults to the real wall clock. */
    var clock: () -> Long = System::currentTimeMillis

    /** What [run] emits while watching for a good frame. */
    sealed interface CaptureEvent {
        /** A live hint for what's currently wrong (move closer, tilt more, ...). */
        data class Guidance(val guidance: FaceGuidance) : CaptureEvent

        /** The best frame found has been selected; its embedding and source bitmap are ready. */
        data class Committed(val embedding: FloatArray, val bitmap: Bitmap) : CaptureEvent
    }

    private data class Candidate(val embedding: FloatArray, val bitmap: Bitmap, val score: Float)

    /**
     * Samples [frames] for [angle] until a best frame is committed.
     *
     * @param angle Which registration step this is capturing for.
     * @param frames A live bitmap stream (e.g. from the camera's frame analysis).
     * @param isFrontCamera Whether [frames] comes from the front camera (affects pose matching).
     * @return A flow of [CaptureEvent]; completes right after its one [CaptureEvent.Committed].
     *
     * Example Usage:
     * autoCaptureController.run(FaceCaptureAngle.FRONT, cameraFrames, isFrontCamera = true)
     *     .collect { event -> ... }
     */
    fun run(angle: FaceCaptureAngle, frames: Flow<Bitmap>, isFrontCamera: Boolean): Flow<CaptureEvent> {
        var best: Candidate? = null
        var deadline: Long? = null
        // 0L, not Long.MIN_VALUE: `now - lastProcessedAt` below overflows a signed Long
        // when lastProcessedAt starts at MIN_VALUE, wrapping to a huge negative number
        // that's always < MIN_FRAME_INTERVAL_MS — every frame was silently dropped, forever.
        var lastProcessedAt = 0L

        return frames.transformWhile { bitmap ->
            val now = clock()
            val currentDeadline = deadline
            if (currentDeadline != null && now >= currentDeadline) {
                emit(CaptureEvent.Committed(best!!.embedding, best!!.bitmap))
                return@transformWhile false
            }

            if (now - lastProcessedAt < MIN_FRAME_INTERVAL_MS) return@transformWhile true
            lastProcessedAt = now

            val face = faceEngine.detectPrimary(bitmap)
            if (face == null) {
                emit(CaptureEvent.Guidance(FaceGuidance.NO_FACE))
                return@transformWhile true
            }

            val rejection = faceQualityGate.evaluate(bitmap, face)
            if (rejection != null) {
                emit(CaptureEvent.Guidance(rejection))
                return@transformWhile true
            }

            val yaw = headPoseEstimator.estimateYaw(face.landmarks)
            if (!headPoseEstimator.matchesAngle(yaw, angle, isFrontCamera)) {
                emit(CaptureEvent.Guidance(headPoseEstimator.guidanceFor(angle)))
                return@transformWhile true
            }

            val score = scoreOf(bitmap, face, yaw, angle, isFrontCamera)
            if (best == null || score > best!!.score) {
                best = Candidate(faceEngine.embed(bitmap, face), bitmap, score)
                deadline = now + SETTLE_WINDOW_MS
            }
            true
        }
    }

    private fun scoreOf(
        bitmap: Bitmap,
        face: FaceBox,
        yaw: Float,
        angle: FaceCaptureAngle,
        isFrontCamera: Boolean
    ): Float {
        val sharpness = faceQualityGate.sharpnessScore(bitmap, face) ?: 0.0
        val normalizedSharpness = (sharpness / (2.0 * FaceQualityGate.ENROLL_MIN_SHARPNESS)).coerceIn(0.0, 1.0)
        val closeness = headPoseEstimator.closeness(yaw, angle, isFrontCamera)
        return CLOSENESS_WEIGHT * closeness + SHARPNESS_WEIGHT * normalizedSharpness.toFloat()
    }
}
