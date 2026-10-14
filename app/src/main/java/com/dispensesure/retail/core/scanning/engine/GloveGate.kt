package com.dispensesure.retail.core.scanning.engine

import com.dispensesure.retail.core.scanning.logic.GloveDetection

/** Locks in "gloves on" after [GLOVE_CONFIRM_FRAMES] strong glove frames in a row. */
class GloveGate {

    // Consecutive-frame counter guarding against single-frame flicker between
    // "gloves" and "no_gloves" classes — require several consecutive strong
    // detections before locking the session state, so one flickery frame
    // doesn't permanently commit "gloves detected".
    private var consecutiveGloveFrames = 0
    private var glovesLocked = false

    /** True only on the frame gloves lock in; false before and after, until [reset]. */
    fun observe(gloveDetections: List<GloveDetection>): Boolean {
        if (glovesLocked) return false
        // Require several CONSECUTIVE strong detections before locking the session
        // state — a single flickery frame (e.g. a bare hand momentarily misclassified
        // as "gloves") must not permanently commit "gloves detected".
        val strongGloveThisFrame = gloveDetections.any { glove ->
            glove.classId == 0 && glove.confidence >= MIN_GLOVE_CONFIDENCE
        }
        consecutiveGloveFrames = if (strongGloveThisFrame) consecutiveGloveFrames + 1 else 0
        if (consecutiveGloveFrames >= GLOVE_CONFIRM_FRAMES) {
            glovesLocked = true
            return true
        }
        return false
    }

    fun reset() {
        consecutiveGloveFrames = 0
        glovesLocked = false
    }

    companion object {
        /**
         * Consecutive frames of a strong "gloves" detection required before the
         * gate locks in. At ~4 fps glove inference, 3 frames is a brief ~750ms
         * hold — enough to reject a single flickery misclassification without
         * feeling slow to the user.
         */
        internal const val GLOVE_CONFIRM_FRAMES = 3

        // A "gloves" detection at or above this confidence is a strong frame.
        private const val MIN_GLOVE_CONFIDENCE = 0.75f
    }
}
