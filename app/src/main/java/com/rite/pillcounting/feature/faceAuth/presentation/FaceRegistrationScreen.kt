package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.feature.faceAuth.domain.model.RegistrationState
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.PrimaryBackground
import com.rite.pillcounting.ui.theme.inputBackground
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Face registration flow: name entry, then Scan Face for each of the 3 angles.
 *
 * Description:
 * Implements the "Face Access Onboarding" mockups' name-entry and Scan Face
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

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var nameEntered by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            !nameEntered -> {
                BackButton(navController = navController, modifier = Modifier.padding(16.dp))
                NameEntryStep(
                    firstName = firstName,
                    lastName = lastName,
                    onFirstNameChange = { firstName = it },
                    onLastNameChange = { lastName = it },
                    onContinue = {
                        nameEntered = true
                        viewModel.startRegistration(firstName, lastName)
                    }
                )
            }

            state is RegistrationState.Enrolled -> {
                BackButton(navController = navController, modifier = Modifier.padding(16.dp))
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
                onCaptureRequested = { angle ->
                    cameraHelper.captureImage { bitmap -> viewModel.captureFrame(bitmap, angle) }
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
private fun NameEntryStep(
    firstName: String,
    lastName: String,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = stringResource(R.string.face_registration_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = firstName,
            onValueChange = onFirstNameChange,
            label = { Text(stringResource(R.string.face_registration_first_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = lastName,
            onValueChange = onLastNameChange,
            label = { Text(stringResource(R.string.face_registration_last_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onContinue,
            enabled = firstName.isNotBlank() && lastName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.face_registration_continue))
        }
    }
}

@Composable
private fun ScanFaceStep(
    state: RegistrationState,
    cameraHelper: CameraHelper,
    navController: NavController,
    onCaptureRequested: (FaceCaptureAngle) -> Unit,
    onCameraReady: (isFrontCamera: Boolean) -> Unit,
    onFinish: () -> Unit
) {
    val capturing = state as? RegistrationState.Capturing
    val angle = capturing?.angle ?: FaceCaptureAngle.FRONT
    val capturedCount = capturing?.capturedCount ?: 0
    val staticPrompt = when (angle) {
        FaceCaptureAngle.FRONT -> stringResource(R.string.face_registration_scan_front)
        FaceCaptureAngle.TILT_LEFT -> stringResource(R.string.face_registration_scan_tilt_left)
        FaceCaptureAngle.TILT_RIGHT -> stringResource(R.string.face_registration_scan_tilt_right)
    }
    val prompt = capturing?.guidance ?: staticPrompt

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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).also { previewView = it } },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = 240.dp, height = 300.dp)
                .border(width = 3.dp, color = MaterialTheme.colorScheme.secondary, shape = RoundedCornerShape(160.dp))
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
            colors = CardDefaults.cardColors(containerColor = PrimaryBackground)
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
                        text = state.reason,
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
                if (capturedCount == FaceCaptureAngle.entries.size) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.face_registration_continue))
                    }
                }
            }
        }
    }
}

@Composable
private fun EnrolledStep(onAddUser: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = stringResource(R.string.face_registration_enrolled_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.face_registration_enrolled_body), modifier = Modifier.padding(top = 8.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddUser, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.face_registration_add_user))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.face_registration_done))
        }
    }
}
