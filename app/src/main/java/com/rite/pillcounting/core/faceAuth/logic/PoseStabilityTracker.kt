package com.rite.pillcounting.core.faceAuth.logic

/**
 * Counts how many frames in a row the pose held. One bad frame starts the count over.
 *
 * Description:
 * A single camera frame is a weak signal. Landmark jitter alone can make a
 * straight face read as a turned one for an instant, and registration used to
 * accept the first frame that looked right — so a minute of holding still gave
 * roughly four hundred chances for one stray frame to finish a step. Measured
 * on device, one in six failing frames drifted to within 0.07 of the old
 * acceptance line. Requiring the pose to survive several frames in a row
 * removes that entirely.
 *
 * What it does:
 * - [record] takes one frame's pose verdict and answers whether the pose has
 *   now held long enough to be trusted.
 * - Any failing frame drops [streak] back to zero.
 *
 * Example Usage:
 * if (tracker.record(posePassed = true)) { /* safe to treat as a real pose */ }
 */
class PoseStabilityTracker(private val requiredFrames: Int = REQUIRED_STABLE_FRAMES) {

    companion object {
        /**
         * Consecutive passing frames before a pose counts as held. At
         * [AutoCaptureController.MIN_FRAME_INTERVAL_MS] this is roughly 450 ms.
         * Started at 5; dropped to 3 because a marginal turn kept resetting the
         * count and the step could take many seconds to finish.
         */
        const val REQUIRED_STABLE_FRAMES = 3
    }

    // How many frames in a row have passed.
    var streak: Int = 0
        private set

    /**
     * Records one frame's pose verdict.
     *
     * @param posePassed Whether this frame's pose matched the angle being captured.
     * @return true once the pose has passed [requiredFrames] frames in a row.
     */
    fun record(posePassed: Boolean): Boolean {
        streak = if (posePassed) streak + 1 else 0
        return streak >= requiredFrames
    }
}
