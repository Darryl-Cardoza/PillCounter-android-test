package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Live camera-capture UI for face verification, used by
 * [SessionLockOverlayScreen]'s Scan Face stage.
 *
 * Detection is automatic: [onAutoVerifyReady] hands the caller a live bitmap stream to
 * feed into [FaceAuthViewModel.startAutoVerify], no tap required. The manual-capture
 * button (wired to [onCaptureRequested]) stays hidden until [FALLBACK_BUTTON_DELAY_MS]
 * has passed with no result yet, for whenever auto-detection is struggling.
 */
@Composable
internal fun ScanningStep(
    onCaptureRequested: (android.graphics.Bitmap) -> Unit,
    onAutoVerifyReady: (Flow<android.graphics.Bitmap>) -> Unit,
    cameraHelper: CameraHelper
) {
    var isFrontCamera by remember { mutableStateOf(true) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var showFallbackButton by remember { mutableStateOf(false) }

    LaunchedEffect(previewView, isFrontCamera) {
        previewView?.let {
            cameraHelper.switchCamera(
                it,
                cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            )
            onAutoVerifyReady(
                cameraHelper.frameFlow.map { proxy -> proxy.toBitmap().also { proxy.close() } }
            )
        }
    }

    LaunchedEffect(Unit) {
        delay(FALLBACK_BUTTON_DELAY_MS)
        showFallbackButton = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also { previewView = it } },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 240.dp, height = 300.dp)
                    .border(width = 3.dp, color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(160.dp))
            )
            IconButton(
                onClick = { isFrontCamera = !isFrontCamera },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.FlipCameraAndroid,
                    contentDescription = stringResource(R.string.face_flip_camera)
                )
            }
        }
        if (showFallbackButton) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Button(onClick = { cameraHelper.captureImage(onCaptured = { bitmap -> onCaptureRequested(bitmap) }) }) {
                    Text(stringResource(R.string.face_verify_capture_button))
                }
            }
        }
    }
}

/** How long auto-verify gets before the manual-capture fallback button appears. */
private const val FALLBACK_BUTTON_DELAY_MS = 5_000L
