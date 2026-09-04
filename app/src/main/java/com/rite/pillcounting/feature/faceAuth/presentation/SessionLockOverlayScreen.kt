package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.feature.faceAuth.domain.model.VerifyState
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.delay

private enum class LockStage { LOCKED, SCANNING }

/**
 * Full-screen "Session Locked" overlay, drawn on top of [com.rite.pillcounting.navigation.AppNavGraph]
 * by [com.rite.pillcounting.MainActivity] whenever [com.rite.pillcounting.core.faceAuth.logic.SessionLockController.isLocked]
 * is true.
 *
 * Description:
 * Mirrors the "Session Locked" mockups' 4 states: idle lock screen, Scan Face
 * (reuses [ScanningStep]), a resuming/welcome-back state that auto-dismisses,
 * and a not-recognized state with Cancel / Try Again. The nav graph
 * keeps composing underneath this overlay, so whatever screen the user was on
 * is exactly where they land once [onUnlocked] fires.
 *
 * @param timeoutMinutes The configured idle timeout, shown in the idle screen's subtitle.
 * @param startOnScan Skip the idle lock screen and open straight on the verify camera
 *   (the post-login lock). Cancelling a failed verify still falls back to the lock screen.
 * @param onUnlocked Called once a live face verify matches an enrolled profile.
 * @param viewModel Supplies verify state and the start/verify actions.
 */
@Composable
fun SessionLockOverlayScreen(
    timeoutMinutes: Int,
    startOnScan: Boolean,
    onUnlocked: () -> Unit,
    viewModel: FaceAuthViewModel = hiltViewModel()
) {
    // startVerify() has to run before verifyState is first read: the ViewModel is
    // activity-scoped, so a Matched left over from the previous lock would otherwise
    // flash "Welcome back" for a frame before the camera appears.
    val initialStage = remember {
        if (startOnScan) {
            viewModel.startVerify()
            LockStage.SCANNING
        } else {
            LockStage.LOCKED
        }
    }
    var stage by remember { mutableStateOf(initialStage) }
    val verifyState by viewModel.verifyState.collectAsState()

    // The ViewModel is activity-scoped (this overlay sits above the nav graph),
    // so the verify loop must be cancelled explicitly when the overlay goes away.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopAutoVerify() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            stage == LockStage.LOCKED -> LockedStep(
                timeoutMinutes = timeoutMinutes,
                onVerifyWithFace = {
                    viewModel.startVerify()
                    stage = LockStage.SCANNING
                }
            )

            verifyState is VerifyState.Matched -> {
                val matched = verifyState as VerifyState.Matched
                ResumingStep(firstName = matched.firstName, onResumed = onUnlocked)
            }

            verifyState is VerifyState.NotRecognized -> LockNotRecognizedStep(
                onTryAgain = { viewModel.startVerify() },
                onCancel = { stage = LockStage.LOCKED }
            )

            else -> ScanCameraStep(
                onCaptureRequested = { bitmap -> viewModel.verifyFrame(bitmap) },
                onAutoVerifyReady = { frames -> viewModel.startAutoVerify(frames) },
                isVoiceoverEnabled = viewModel.isVoiceoverEnabled
            )
        }
    }
}

@Composable
private fun ScanCameraStep(
    onCaptureRequested: (android.graphics.Bitmap) -> Unit,
    onAutoVerifyReady: (kotlinx.coroutines.flow.Flow<android.graphics.Bitmap>) -> Unit,
    isVoiceoverEnabled: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraHelper = remember { CameraHelper(context, lifecycleOwner, ContextCompat.getMainExecutor(context)) }
    // The lifecycle owner here is the Activity (not a nav destination), so the
    // camera would stay bound after unlock without an explicit unbind.
    DisposableEffect(Unit) {
        onDispose { cameraHelper.pauseCamera() }
    }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScanningStep(
            onCaptureRequested = onCaptureRequested,
            onAutoVerifyReady = onAutoVerifyReady,
            cameraHelper = cameraHelper,
            isVoiceoverEnabled = isVoiceoverEnabled
        )
    }
}

@Composable
private fun LockedStep(timeoutMinutes: Int, onVerifyWithFace: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
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
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.session_locked_title),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.session_locked_subtitle),
                color = AppTheme.extendedColors.textColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        ActionButtonPrimary(
            text = stringResource(R.string.session_locked_verify_button).uppercase(),
            onClick = onVerifyWithFace,
            modifier = Modifier.align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.primary,
            fixedWidth = false
        )
    }
}

@Composable
private fun ResumingStep(firstName: String, onResumed: () -> Unit) {
    LaunchedEffect(firstName) {
        delay(1_200L)
        onResumed()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
            text = stringResource(R.string.session_locked_resuming_title, firstName),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.session_locked_resuming_body),
            color = AppTheme.extendedColors.textColor,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun LockNotRecognizedStep(onTryAgain: () -> Unit, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
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
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.face_verify_not_recognized_title),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.face_verify_not_recognized_body),
                modifier = Modifier.padding(top = 8.dp),
                color = AppTheme.extendedColors.textColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HollowButton(
                text = stringResource(R.string.face_verify_cancel).uppercase(),
                onClick = onCancel,
                color = MaterialTheme.colorScheme.primary
            )
            ActionButtonPrimary(
                text = stringResource(R.string.face_verify_try_again).uppercase(),
                onClick = onTryAgain,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
