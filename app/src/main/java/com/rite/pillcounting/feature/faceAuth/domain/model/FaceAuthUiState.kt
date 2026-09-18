package com.rite.pillcounting.feature.faceAuth.domain.model

import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.faceAuth.model.FaceGuidance

/** State of an in-progress registration capture flow. */
sealed interface RegistrationState {
    data object Idle : RegistrationState
    data class Capturing(val angle: FaceCaptureAngle, val capturedCount: Int, val guidance: FaceGuidance? = null) : RegistrationState

    /** The FRONT capture matched an enrolled profile. Capture is paused until the user chooses. */
    data class DuplicateWarning(
        val firstName: String,
        val lastName: String,
        val capturedCount: Int
    ) : RegistrationState

    data object Enrolled : RegistrationState

    /** Saving the profile threw. Every angle is still captured, so the save can be retried. */
    data object Failed : RegistrationState
}

/** State of an in-progress verify flow. */
sealed interface VerifyState {
    data object Idle : VerifyState
    data object Scanning : VerifyState
    data class Matched(val firstName: String, val lastName: String) : VerifyState
    data object NotRecognized : VerifyState
}
