package com.dispensesure.retail.core.scanning.engine

import androidx.annotation.VisibleForTesting

/** Tells a real count change from detector flicker on a still tray. */
class CountConfirmer {

    // Count-confirmation state. See observe().
    private var pendingCount = -1
    private var confirmedCount = -1
    private var consecutiveSameCount = 0

    // A count must repeat for COUNT_CONFIRM_FRAMES frames to count as activity,
    // so detector flicker can't hold the watchdog open forever.
    // Returns true on the frame that confirms a new count.
    fun observe(count: Int): Boolean {
        if (count == pendingCount) {
            consecutiveSameCount++
        } else {
            pendingCount = count
            consecutiveSameCount = 1
        }
        if (consecutiveSameCount == COUNT_CONFIRM_FRAMES && count != confirmedCount) {
            confirmedCount = count
            return true
        }
        return false
    }

    fun reset() {
        pendingCount = -1
        confirmedCount = -1
        consecutiveSameCount = 0
    }

    companion object {
        // Frames a count must hold before it counts as activity. Rejects
        // single-frame flicker on a still tray.
        @VisibleForTesting
        internal const val COUNT_CONFIRM_FRAMES = 3
    }
}
