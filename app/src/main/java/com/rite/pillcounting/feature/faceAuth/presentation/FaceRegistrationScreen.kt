package com.rite.pillcounting.feature.faceAuth.presentation

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.faceAuth.model.FaceGuidance
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.utils.common.SoundUtils
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.TABLET_BREAKPOINT_DP
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.feature.faceAuth.domain.model.RegistrationState
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Face registration flow: Scan Photo ID for the name, then Scan Face for each
 * of the 3 angles.
 *
 * Description:
 * Implements the "Face Access Onboarding" mockups' Scan Photo ID and Scan Face
 * steps, driven by [FaceAuthViewModel]. Camera frames come from the existing
 * [CameraHelper] (the same wrapper the pill-scanning flow uses).
 *
 * @param navController Used to return to Settings on Done, or restart on Add User.
 * @param viewModel Supplies registration state and capture/finish actions.
 */
@Composable
fun FaceRegistrationScreen(
    navController: NavController,
    viewModel: FaceAuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraHelper = remember { CameraHelper(context, lifecycleOwner, ContextCompat.getMainExecutor(context)) }
    val scope = rememberCoroutineScope()
    val state by viewModel.registrationState.collectAsState()
    val isSessionLocked by viewModel.isSessionLocked.collectAsState()

    // Camera-based face/ID capture requires portrait framing on phones. Tablets keep
    // free rotation because their landscape layout is intentionally supported.
    val activity = context as? Activity
    val isPhone = LocalConfiguration.current.smallestScreenWidthDp < TABLET_BREAKPOINT_DP
    DisposableEffect(Unit) {
        if (isPhone) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (isPhone) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // rememberSaveable: a scanned/typed name survives activity recreation and
    // process death while the user is still mid-enrollment.
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var nameEntered by rememberSaveable { mutableStateOf(false) }

    // After process death these flags are restored but the ViewModel's pending
    // names are not — re-seed it, or the face scan would enroll a blank name.
    // No-op on config changes: the surviving ViewModel is past Idle by then.
    LaunchedEffect(Unit) {
        if (nameEntered && state is RegistrationState.Idle) {
            viewModel.startRegistration(firstName, lastName)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            !nameEntered -> {
                ScanPhotoIdStep(
                    cameraHelper = cameraHelper,
                    navController = navController,
                    firstName = firstName,
                    lastName = lastName,
                    isSessionLocked = isSessionLocked,
                    isVoiceoverEnabled = viewModel.isVoiceoverEnabled,
                    onUserInteraction = viewModel::onSessionActivity,
                    onFirstNameChange = { firstName = it },
                    onLastNameChange = { lastName = it },
                    onContinue = {
                        nameEntered = true
                        viewModel.startRegistration(firstName, lastName)
                    }
                )
            }

            state is RegistrationState.Enrolled -> {
                EnrolledStep(
                    onAddUser = {
                        nameEntered = false
                        firstName = ""
                        lastName = ""
                    },
                    onDone = { navController.popBackStack() }
                )
            }

            else -> ScanFaceStep(
                state = state,
                cameraHelper = cameraHelper,
                navController = navController,
                isSessionLocked = isSessionLocked,
                isVoiceoverEnabled = viewModel.isVoiceoverEnabled,
                onCaptureRequested = { angle ->
                    cameraHelper.captureImage(onCaptured = { bitmap -> viewModel.captureFrame(bitmap, angle) })
                },
                onCameraReady = { isFrontCamera ->
                    viewModel.startAutoCapture(
                        // frameFlow is raw YUV_420_888 (ImageAnalysis), not JPEG — toBitmap()
                        // is CameraX's own YUV-aware conversion; imageProxyToBitmap() only
                        // works on ImageCapture's JPEG output (see captureImage() above).
                        frames = cameraHelper.frameFlow.map { proxy ->
                            proxy.toBitmap().also { proxy.close() }
                        },
                        isFrontCamera = isFrontCamera
                    )
                },
                onFinish = { scope.launch { viewModel.finishRegistration() } }
            )
        }
    }
}

@Composable
private fun ScanFaceStep(
    state: RegistrationState,
    cameraHelper: CameraHelper,
    navController: NavController,
    isSessionLocked: Boolean,
    isVoiceoverEnabled: Boolean,
    onCaptureRequested: (FaceCaptureAngle) -> Unit,
    onCameraReady: (isFrontCamera: Boolean) -> Unit,
    onFinish: () -> Unit
) {
    val capturing = state as? RegistrationState.Capturing
    val rejected = state as? RegistrationState.Rejected
    val angle = capturing?.angle ?: rejected?.angle ?: FaceCaptureAngle.FRONT
    val capturedCount = capturing?.capturedCount ?: 0
    // After the last angle the state still names it, so say done instead of asking for a tilt again.
    val isComplete = capturedCount == FaceCaptureAngle.entries.size
    val staticPrompt = if (isComplete) {
        Utterance("face_scan_prompt_COMPLETE", stringResource(R.string.face_registration_scan_complete))
    } else {
        Utterance(
            id = "face_scan_prompt_${angle.name}",
            text = when (angle) {
                FaceCaptureAngle.FRONT -> stringResource(R.string.face_registration_scan_front)
                FaceCaptureAngle.TILT_LEFT -> stringResource(R.string.face_registration_scan_tilt_left)
                FaceCaptureAngle.TILT_RIGHT -> stringResource(R.string.face_registration_scan_tilt_right)
            }
        )
    }
    val guidanceSpeech = capturing?.guidance?.let { Utterance("face_guidance_${it.name}", guidanceText(it)) }
    val rejectionSpeech = rejected?.let { Utterance("face_reject_${it.reason.name}", guidanceText(it.reason)) }
    val prompt = guidanceSpeech?.text ?: staticPrompt.text

    // Speak each angle's prompt once, as the scanning screens' title chip does.
    val context = LocalContext.current
    DisposableEffect(context) {
        SoundUtils.prewarmTts(context)
        onDispose { SoundUtils.stopSpeaking() }
    }

    // New speech cuts off old speech, so hints wait their turn.
    var lastSpeechAt by remember { mutableLongStateOf(0L) }
    val speakNow: (Utterance) -> Unit = { utterance ->
        lastSpeechAt = System.currentTimeMillis()
        SoundUtils.speak(context = context, text = utterance.text, utteranceId = utterance.id)
    }

    LaunchedEffect(staticPrompt.id, SoundUtils.isTtsReady, isVoiceoverEnabled) {
        if (SoundUtils.isTtsReady && isVoiceoverEnabled) {
            speakNow(staticPrompt)
        }
    }

    // Keyed on the id, so the same hint is never spoken twice. If the
    // hint keeps changing, the delay restarts and nothing is spoken.
    LaunchedEffect(guidanceSpeech?.id, SoundUtils.isTtsReady, isVoiceoverEnabled) {
        if (guidanceSpeech == null || !SoundUtils.isTtsReady || !isVoiceoverEnabled) return@LaunchedEffect
        delay(GUIDANCE_DEBOUNCE_MS)
        val quietLeft = SPEECH_QUIET_PERIOD_MS - (System.currentTimeMillis() - lastSpeechAt)
        if (quietLeft > 0) delay(quietLeft)
        speakNow(guidanceSpeech)
    }

    // A rejection answers the user's tap, so it speaks right away.
    LaunchedEffect(rejectionSpeech?.id, SoundUtils.isTtsReady, isVoiceoverEnabled) {
        if (rejectionSpeech != null && SoundUtils.isTtsReady && isVoiceoverEnabled) {
            speakNow(rejectionSpeech)
        }
    }

    var isFrontCamera by remember { mutableStateOf(true) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(previewView, isFrontCamera) {
        previewView?.let {
            cameraHelper.switchCamera(
                it,
                cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            )
            onCameraReady(isFrontCamera)
        }
    }

    // The lock overlay's own camera bind calls unbindAll(), killing this
    // screen's use cases — without an explicit rebind on unlock the preview
    // stays dead and auto-capture never commits (mirrors ScanPhotoIdStep).
    var wasLocked by remember { mutableStateOf(false) }
    LaunchedEffect(isSessionLocked) {
        if (isSessionLocked) {
            wasLocked = true
            cameraHelper.pauseCamera()
        } else if (wasLocked) {
            wasLocked = false
            previewView?.let {
                cameraHelper.switchCamera(
                    it,
                    cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                )
                onCameraReady(isFrontCamera)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).also { previewView = it } },
            modifier = Modifier.fillMaxSize()
        )
        // Full size on tablets, smaller on phones so the oval always fits.
        val ovalWidth = minOf(
            FACE_OVAL_MAX_WIDTH,
            maxWidth * FACE_OVAL_FILL_FRACTION,
            maxHeight * FACE_OVAL_FILL_FRACTION * FACE_OVAL_ASPECT_RATIO
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(ovalWidth)
                .aspectRatio(FACE_OVAL_ASPECT_RATIO)
                .border(width = 3.dp, color = MaterialTheme.colorScheme.secondary, shape = RoundedCornerShape(percent = FACE_OVAL_CORNER_PERCENT))
        )
        BackButton(navController = navController, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        IconButton(
            onClick = { isFrontCamera = !isFrontCamera },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.FlipCameraAndroid,
                contentDescription = stringResource(R.string.face_flip_camera),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(350.dp)
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.extendedColors.primaryBackground)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = prompt,
                    color = AppTheme.extendedColors.textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state is RegistrationState.Rejected) {
                    Text(
                        text = guidanceText(state.reason),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Visual left-to-right order matches the mockup; capture order (FRONT
                    // first) is independent and tracked via each angle's own sequence index.
                    listOf(FaceCaptureAngle.TILT_LEFT, FaceCaptureAngle.FRONT, FaceCaptureAngle.TILT_RIGHT).forEach { slotAngle ->
                        val sequenceIndex = FaceCaptureAngle.entries.indexOf(slotAngle)
                        val isDone = capturedCount > sequenceIndex
                        val isCurrent = capturedCount == sequenceIndex
                        val description = when (slotAngle) {
                            FaceCaptureAngle.FRONT -> stringResource(R.string.face_registration_scan_front)
                            FaceCaptureAngle.TILT_LEFT -> stringResource(R.string.face_registration_scan_tilt_left)
                            FaceCaptureAngle.TILT_RIGHT -> stringResource(R.string.face_registration_scan_tilt_right)
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    color = if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.inverseSurface,
                                    shape = CircleShape
                                )
                                .clickable(enabled = isCurrent) { onCaptureRequested(slotAngle) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    isDone -> Icons.Filled.Check
                                    slotAngle == FaceCaptureAngle.TILT_LEFT -> Icons.AutoMirrored.Filled.ArrowBack
                                    slotAngle == FaceCaptureAngle.TILT_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
                                    else -> Icons.Filled.KeyboardArrowUp
                                },
                                contentDescription = description,
                                tint = if (isDone) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                if (isComplete) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.face_registration_continue))
                    }
                }
            }
        }
    }
}

/**
 * A spoken line plus the id the TTS engine tags it with. The id comes from the
 * enum, so it does not change when the text or the language changes.
 */
private data class Utterance(val id: String, val text: String)

// A hint waits this long before it is spoken.
private const val GUIDANCE_DEBOUNCE_MS = 700L

// A hint stays quiet this long after any other speech.
private const val SPEECH_QUIET_PERIOD_MS = 2_500L

/** Localized text for a [FaceGuidance] emitted by the capture logic. */
@Composable
private fun guidanceText(guidance: FaceGuidance): String = stringResource(
    when (guidance) {
        FaceGuidance.NO_FACE -> R.string.face_guidance_no_face
        FaceGuidance.MOVE_CLOSER -> R.string.face_guidance_move_closer
        FaceGuidance.MOVE_BACK -> R.string.face_guidance_move_back
        FaceGuidance.FACE_CROP_FAILED -> R.string.face_guidance_crop_failed
        FaceGuidance.HOLD_STILL -> R.string.face_guidance_hold_still
        FaceGuidance.LOOK_STRAIGHT -> R.string.face_guidance_look_straight
        FaceGuidance.TILT_MORE_LEFT -> R.string.face_guidance_tilt_more_left
        FaceGuidance.TILT_MORE_RIGHT -> R.string.face_guidance_tilt_more_right
    }
)

@Composable
private fun EnrolledStep(onAddUser: () -> Unit, onDone: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.face_registration_enrolled_title),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.face_registration_enrolled_body),
                color = AppTheme.extendedColors.textColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HollowButton(
                text = stringResource(R.string.face_registration_add_user).uppercase(),
                onClick = onAddUser,
                color = MaterialTheme.colorScheme.primary
            )
            ActionButtonPrimary(
                text = stringResource(R.string.face_registration_done).uppercase(),
                onClick = onDone,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
