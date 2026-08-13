package com.rite.pillcounting.feature.faceAuth.domain.model

import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle

/** State of an in-progress registration capture flow. */
sealed interface RegistrationState {
    data object Idle : RegistrationState
    data class Capturing(val angle: FaceCaptureAngle, val capturedCount: Int, val guidance: String? = null) : RegistrationState
    data class Rejected(val angle: FaceCaptureAngle, val reason: String) : RegistrationState
    data object Enrolled : RegistrationState
    data class Failed(val message: String) : RegistrationState
}

/** State of an in-progress verify flow. */
sealed interface VerifyState {
    data object Idle : VerifyState
    data object Scanning : VerifyState
    data class Matched(val firstName: String, val lastName: String) : VerifyState
    data object NotRecognized : VerifyState
}
