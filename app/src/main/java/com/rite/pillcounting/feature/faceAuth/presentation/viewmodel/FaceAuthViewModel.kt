package com.rite.pillcounting.feature.faceAuth.presentation.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.faceAuth.data.FaceProfileRepository
import com.rite.pillcounting.core.faceAuth.logic.AutoCaptureController
import com.rite.pillcounting.core.faceAuth.logic.FaceEngine
import com.rite.pillcounting.core.faceAuth.logic.FaceMatcher
import com.rite.pillcounting.core.faceAuth.logic.FaceQualityGate
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.faceAuth.domain.model.RegistrationState
import com.rite.pillcounting.feature.faceAuth.domain.model.VerifyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Production [FaceAuthViewModel.sessionEmailProvider] implementation: reads the
 * logged-in operator's account email via [PreferenceHelper.getLocalId] + [UserDao].
 *
 * @param preferenceHelper Source of the current session's `localId`.
 * @param userDao Source of the [com.rite.pillcounting.core.room.models.UserEntity] for that id.
 */
class SessionEmailProvider @Inject constructor(
    private val preferenceHelper: PreferenceHelper,
    private val userDao: UserDao
) {
    /**
     * Reads the current session's plaintext email.
     *
     * @return The session's plaintext email, or null if no user is resolvable.
     *
     * Example Usage:
     * val email = sessionEmailProvider()
     */
    suspend operator fun invoke(): String? {
        val localId = preferenceHelper.getLocalId()
        if (localId <= 0) return null
        return userDao.getByLocalId(localId)?.email
    }
}

/**
 * Drives the Face Recognition registration, list, and verify flows.
 *
 * Description:
 * Orchestrates [FaceEngine] (detect/embed) and [FaceProfileRepository]
 * (Room storage), applying the same quality-gate and matching thresholds
 * `standalone_face_tf.py` uses.
 *
 * What it does:
 * - Registration: [startRegistration] resets state, [startAutoCapture] runs the
 *   automatic best-frame-per-angle loop against a live camera stream, [captureFrame]
 *   is the manual-override path racing alongside it for the current step,
 *   [finishRegistration] persists the profile once all 3 angles are captured.
 * - List: [profiles] is a live view of every enrolled profile for the Quick Access
 *   Users screen; [setProfileEnabled] and [deleteProfile] back its toggle/delete actions.
 * - Verify: [startVerify] resets state, [verifyFrame] runs one detect+embed+identify pass.
 */
@HiltViewModel
class FaceAuthViewModel @Inject constructor(
    private val faceEngine: FaceEngine,
    private val faceProfileRepository: FaceProfileRepository,
    private val sessionEmailProvider: SessionEmailProvider,
    private val faceQualityGate: FaceQualityGate,
    private val autoCaptureController: AutoCaptureController
) : ViewModel() {

    // TEMPORARY diagnostic for the enroll/verify score mismatch investigation —
    // remove once the root cause is found. Filter logcat on this tag to see the
    // final cosine score behind every "recognized" / "not recognized" decision.
    private val matchLogger = AppLogger("FaceMatchIO")

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _verifyState = MutableStateFlow<VerifyState>(VerifyState.Idle)
    val verifyState: StateFlow<VerifyState> = _verifyState.asStateFlow()

    val profiles: StateFlow<List<FaceProfileEntity>> = faceProfileRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pendingFirstName: String = ""
    private var pendingLastName: String = ""
    private val capturedEmbeddings = mutableMapOf<FaceCaptureAngle, FloatArray>()

    private var autoCaptureJob: Job? = null
    private var autoCaptureFrames: Flow<Bitmap>? = null
    private var autoCaptureIsFrontCamera: Boolean = true

    /**
     * Begins a new registration: resets any prior capture progress.
     *
     * @param firstName Typed at the "What's your name?" step.
     * @param lastName Typed at the "What's your name?" step.
     */
    fun startRegistration(firstName: String, lastName: String) {
        pendingFirstName = firstName
        pendingLastName = lastName
        capturedEmbeddings.clear()
        autoCaptureJob?.cancel()
        autoCaptureFrames = null
        _registrationState.value = RegistrationState.Capturing(FaceCaptureAngle.FRONT, 0)
    }

    /**
     * Starts (or restarts, e.g. after a camera flip) the automatic capture loop
     * for whichever angles haven't been captured yet.
     *
     * @param frames A live bitmap stream from the registration screen's camera.
     * @param isFrontCamera Whether [frames] comes from the front camera.
     *
     * Example Usage:
     * viewModel.startAutoCapture(cameraHelper.frameFlow.map { imageProxyToBitmap(it) }, isFrontCamera = true)
     */
    fun startAutoCapture(frames: Flow<Bitmap>, isFrontCamera: Boolean) {
        autoCaptureFrames = frames
        autoCaptureIsFrontCamera = isFrontCamera
        resumeAutoCapture()
    }

    /** (Re)starts the auto-capture loop from the first angle not yet in [capturedEmbeddings], cancelling any stale in-flight angle. */
    private fun resumeAutoCapture() {
        val frames = autoCaptureFrames ?: return
        autoCaptureJob?.cancel()
        autoCaptureJob = viewModelScope.launch {
            for (angle in FaceCaptureAngle.entries) {
                if (capturedEmbeddings.containsKey(angle)) continue
                autoCaptureController.run(angle, frames, autoCaptureIsFrontCamera).collect { event ->
                    when (event) {
                        is AutoCaptureController.CaptureEvent.Guidance ->
                            _registrationState.value =
                                RegistrationState.Capturing(angle, capturedEmbeddings.size, event.message)

                        is AutoCaptureController.CaptureEvent.Committed -> {
                            capturedEmbeddings[angle] = event.embedding
                            _registrationState.value = RegistrationState.Capturing(angle, capturedEmbeddings.size)
                        }
                    }
                }
            }
        }
    }

    /**
     * Attempts to capture one embedding for [angle] from [bitmap] — the manual-override
     * path, racing alongside the automatic capture loop for the current step.
     *
     * @param bitmap The current camera frame.
     * @param angle Which Scan Face step this frame is for.
     */
    fun captureFrame(bitmap: Bitmap, angle: FaceCaptureAngle) {
        viewModelScope.launch {
            val face = faceEngine.detectPrimary(bitmap)
            if (face == null) {
                _registrationState.value = RegistrationState.Rejected(angle, "no face detected")
                return@launch
            }
            val rejectionReason = faceQualityGate.evaluate(bitmap, face)
            if (rejectionReason != null) {
                _registrationState.value = RegistrationState.Rejected(angle, rejectionReason)
                return@launch
            }
            if (capturedEmbeddings.containsKey(angle)) return@launch // auto-capture already won this step
            capturedEmbeddings[angle] = faceEngine.embed(bitmap, face)
            _registrationState.value = RegistrationState.Capturing(angle, capturedEmbeddings.size)
            resumeAutoCapture() // cancel the now-stale in-flight angle, advance the loop
        }
    }

    /** Persists the profile once all 3 angles have been captured; no-ops (as [RegistrationState.Failed]) otherwise. */
    fun finishRegistration() {
        viewModelScope.launch {
            if (capturedEmbeddings.size < FaceCaptureAngle.entries.size) {
                _registrationState.value = RegistrationState.Failed("capture all 3 angles before finishing")
                return@launch
            }
            val email = sessionEmailProvider()
            faceProfileRepository.registerProfile(
                firstName = pendingFirstName,
                lastName = pendingLastName,
                email = email,
                embeddingsByAngle = capturedEmbeddings.toMap(),
                now = System.currentTimeMillis()
            )
            _registrationState.value = RegistrationState.Enrolled
        }
    }

    /**
     * Toggles a profile's Quick Access Users list switch.
     *
     * @param profile The profile row being toggled.
     * @param enabled New enabled state.
     */
    fun setProfileEnabled(profile: FaceProfileEntity, enabled: Boolean) {
        viewModelScope.launch { faceProfileRepository.setEnabled(profile, enabled) }
    }

    /**
     * Deletes an enrolled profile.
     *
     * @param profile The profile to remove.
     */
    fun deleteProfile(profile: FaceProfileEntity) {
        viewModelScope.launch { faceProfileRepository.deleteProfile(profile) }
    }

    private var autoVerifyJob: Job? = null

    /** Resets verify state before a new verify attempt. */
    fun startVerify() {
        _verifyState.value = VerifyState.Scanning
        autoVerifyJob?.cancel()
    }

    /**
     * Watches a live frame stream and automatically attempts a verify once a
     * frame clears the same detect + quality-gate checks registration uses —
     * no tap required. Stops on its own once the screen collecting it leaves
     * composition (its `LaunchedEffect` gets cancelled).
     *
     * @param frames A live bitmap stream from the verify/lock screen's camera.
     *
     * Example Usage:
     * viewModel.startAutoVerify(cameraHelper.frameFlow.map { it.toBitmap() })
     */
    fun startAutoVerify(frames: Flow<Bitmap>) {
        autoVerifyJob?.cancel()
        autoVerifyJob = viewModelScope.launch {
            // 0L, not Long.MIN_VALUE: `now - lastProcessedAt` below overflows a signed Long
            // when lastProcessedAt starts at MIN_VALUE, wrapping to a huge negative number
            // that's always < the interval — every frame was silently dropped, forever.
            var lastProcessedAt = 0L
            frames.collect { bitmap ->
                // A prior frame in this same loop (or a concurrent manual tap) may have
                // already landed on a result — stop attempting once we're no longer Scanning,
                // so this background loop can't flip Matched/NotRecognized back and forth
                // while the user is looking at a result screen.
                if (_verifyState.value !is VerifyState.Scanning) return@collect

                val now = System.currentTimeMillis()
                if (now - lastProcessedAt < AUTO_VERIFY_FRAME_INTERVAL_MS) return@collect
                lastProcessedAt = now

                val face = faceEngine.detectPrimary(bitmap) ?: return@collect
                if (faceQualityGate.evaluate(bitmap, face) != null) return@collect

                runVerify(bitmap) // suspends here, so no two attempts ever overlap
            }
        }
    }

    /**
     * Runs one detect+embed+identify pass against the live gallery — the
     * manual-override path, fired once per tap.
     *
     * @param bitmap The current camera frame.
     */
    fun verifyFrame(bitmap: Bitmap) {
        viewModelScope.launch { runVerify(bitmap) }
    }

    private suspend fun runVerify(bitmap: Bitmap) {
        val face = faceEngine.detectPrimary(bitmap) ?: return // stay in Scanning; mockup keeps showing the placement prompt

        val probe = faceEngine.embed(bitmap, face)
        val gallery = faceProfileRepository.loadGallery()
        val result = FaceMatcher.identify(probe, gallery)
        matchLogger.i(
            "gallerySize=${gallery.size} bestScore=${result.bestScore} " +
                "threshold=${FaceMatcher.MATCH_THRESHOLD} matchedProfileId=${result.faceProfileId}"
        )

        val matchedId = result.faceProfileId
        if (matchedId == null) {
            _verifyState.value = VerifyState.NotRecognized
            return
        }
        faceProfileRepository.markUsed(matchedId, System.currentTimeMillis())
        val matchedProfile = faceProfileRepository.getProfile(matchedId)
        _verifyState.value = if (matchedProfile != null) {
            VerifyState.Matched(matchedProfile.firstName, matchedProfile.lastName)
        } else {
            VerifyState.NotRecognized
        }
    }

    companion object {
        /** Minimum spacing between auto-verify attempts — keeps it off the full camera frame rate. */
        private const val AUTO_VERIFY_FRAME_INTERVAL_MS = 150L
    }
}
