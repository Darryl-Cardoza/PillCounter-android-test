package com.rite.pillcounting.core.faceAuth.model

/**
 * Why a live capture frame isn't good enough yet. Logic classes return these
 * instead of display text; the UI maps each to a string resource.
 */
enum class FaceGuidance {
    NO_FACE,
    MOVE_CLOSER,
    MOVE_BACK,
    FACE_CROP_FAILED,
    HOLD_STILL,
    LOOK_STRAIGHT,
    TILT_MORE_LEFT,
    TILT_MORE_RIGHT,
}
