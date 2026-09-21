package com.rite.pillcounting.core.scanning.logic

/**
 * Two-stage smoothing for the displayed pill count, per the shipped desktop
 * reference config: the median over the last [MEDIAN_WINDOW] raw counts feeds a
 * latch that only moves the displayed number once [AGREEING_FRAMES] consecutive
 * medians agree on the same new value.
 *
 * The median kills single-frame outliers; the latch kills a median that is
 * oscillating between two values.
 *
 * The latch is skipped on first acquisition (while the displayed count is still
 * zero). Flicker is a problem between two non-zero counts, not on the way up
 * from nothing, and waiting [AGREEING_FRAMES] there cost ~3 frames before any
 * number appeared — seconds on a slow frame. The raw count is already filtered
 * by PillTracker, which needs ENTER_FRAMES consecutive detections before a pill
 * counts at all, so the first number is not a single frame's guess.
 */
class CountStabilizer {

    companion object {
        const val MEDIAN_WINDOW = 5
        const val AGREEING_FRAMES = 3
    }

    private val recent = ArrayDeque<Int>()
    private var displayed = 0
    private var pendingValue = 0
    private var pendingStreak = 0

    /** Feeds one frame's raw count and returns the number to show. */
    fun update(rawCount: Int): Int {
        recent.addLast(rawCount)
        while (recent.size > MEDIAN_WINDOW) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2]

        when {
            median == displayed -> pendingStreak = 0
            // First acquisition: show it as soon as the tracker confirms pills.
            displayed == 0 -> {
                displayed = median
                pendingValue = median
                pendingStreak = 0
            }
            median == pendingValue -> {
                pendingStreak++
                if (pendingStreak >= AGREEING_FRAMES) {
                    displayed = median
                    pendingStreak = 0
                }
            }
            else -> {
                pendingValue = median
                pendingStreak = 1
            }
        }
        return displayed
    }

    /** Clears the window and the latch so a new scene starts from zero. */
    fun reset() {
        recent.clear()
        displayed = 0
        pendingValue = 0
        pendingStreak = 0
    }
}
