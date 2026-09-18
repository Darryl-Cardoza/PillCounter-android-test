package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.RectF
import com.rite.pillcounting.core.scanning.logic.NMS
import javax.inject.Inject

/**
 * Watches the face's box, not the face, so a second person can't take over halfway through.
 *
 * Description:
 * Registration stores one embedding per angle, and nothing used to check the
 * three came from the same person — so one person could do the straight-on
 * capture and another both tilts, all saved under one name. Comparing their
 * embeddings was measured on device and does not work: a genuine single-person
 * enrolment scored 0.140 while a genuine impostor scored 0.254, so no threshold
 * separates them.
 *
 * This takes the other route. To put a second person in a slot, the first has to
 * leave the frame and the second enter, which either drops detection for a
 * moment or makes the box jump. Both are visible without asking the recognizer
 * anything, so this works regardless of how accurate the recognizer is.
 *
 * What it does:
 * - [observe] takes each sampled frame's face box, or null when no face was
 *   detected, and follows the track.
 * - [arm] starts holding the track to account, at the straight-on capture.
 * - [isBroken] latches true once an armed track is lost; only [reset] clears it.
 *
 * Deliberately driven by detection alone, never the quality gate: a blurry or
 * badly-lit face is still the same face, and failing quality must not look like
 * a person swap.
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
