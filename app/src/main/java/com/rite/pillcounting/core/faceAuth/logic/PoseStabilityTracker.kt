package com.rite.pillcounting.core.faceAuth.logic

/**
 * Counts how many frames in a row the pose held. One bad frame starts the count over.
 *
 * Landmark jitter alone can make a straight face read as turned for an instant, so
 * accepting the first frame that looks right accepts strays.
 *
 * Example Usage:
 * if (tracker.record(posePassed = true)) { /* safe to treat as a real pose */ }
 */
class PoseStabilityTracker(private val requiredFrames: Int = REQUIRED_STABLE_FRAMES) {

    companion object {
        /**
         * Consecutive passing frames before a pose counts as held. At
         * [AutoCaptureController.MIN_FRAME_INTERVAL_MS] this is roughly 450 ms.
         * Measured: one in six failing frames drifted within 0.07 of the acceptance
         * line, so one frame is not enough. Started at 5; dropped to 3 because a
         * marginal turn kept resetting the count and the step took many seconds.
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
