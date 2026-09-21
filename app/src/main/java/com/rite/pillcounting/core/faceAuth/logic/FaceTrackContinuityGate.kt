package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.RectF
import com.rite.pillcounting.core.scanning.logic.NMS
import javax.inject.Inject

/**
 * Watches the face's box, not the face, so a second person can't take over halfway through.
 *
 * Comparing embeddings across angles was measured on device and cannot do this: a
 * genuine enrolment scored 0.140 against an impostor's 0.254, so no threshold
 * separates them. A swap instead has to drop detection or jump the box, which is
 * visible however weak the recognizer is.
 *
 * Driven by detection alone, never the quality gate — a blurry face is still the
 * same face, and failing quality must not look like a person swap.
 *
 * Example Usage:
 * trackGate.observe(face?.rect)
 * if (trackGate.isBroken) { /* start the face scan again */ }
 */
class FaceTrackContinuityGate @Inject constructor() {

    companion object {
        /** How much two consecutive boxes must overlap to be the same face. */
        const val MIN_TRACK_IOU = 0.35f

        /** Consecutive frames with no face that are forgiven before the track is lost. ~450 ms. */
        const val MAX_TRACK_GAP_FRAMES = 3
    }

    /** True once an armed track has been lost. Latches — only [reset] clears it. */
    var isBroken: Boolean = false
        private set

    // Consecutive frames with no face.
    private var gapFrames: Int = 0

    private var isArmed = false
    private var lastRect: RectF? = null

    /**
     * Starts holding the track to account, at the straight-on capture.
     *
     * Keeps whatever box [observe] last saw rather than taking one: the frame that
     * won the step was already observed, so re-detecting it would cost a second
     * full detector pass for a box we have.
     */
    fun arm() {
        isArmed = true
        isBroken = false
    }

    /**
     * Follows the track through one sampled frame.
     *
     * @param rect That frame's face box, or null if no face was detected.
     */
    fun observe(rect: RectF?) {
        if (isBroken) return
        if (rect == null) {
            gapFrames++
            if (gapFrames > MAX_TRACK_GAP_FRAMES) loseTrack()
            return
        }
        val previous = lastRect
        lastRect = rect
        gapFrames = 0
        // A jump this large between frames 150ms apart is a different face, not movement.
        if (previous != null && NMS.iou(previous, rect) < MIN_TRACK_IOU) loseTrack()
    }

    /**
     * Drops the last box without disarming, for a resume after the frame loop was
     * stopped. Nothing observed the track across that gap, so the next box starts a
     * fresh comparison instead of being judged against a seconds-old one.
     */
    fun dropLastRect() {
        lastRect = null
        gapFrames = 0
    }

    /** Clears everything, including [isBroken]. Called when the face scan starts over. */
    fun reset() {
        isArmed = false
        isBroken = false
        lastRect = null
        gapFrames = 0
    }

    private fun loseTrack() {
        lastRect = null
        gapFrames = 0
        // Before the straight-on capture there is nothing to protect — the scan
        // simply hasn't established whose face it is yet.
        if (isArmed) isBroken = true
    }
}
