@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH")

package com.dispensesure.retail.core.scanning.presentation.compose

import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dispensesure.retail.core.scanning.logic.CameraHelper
import kotlinx.coroutines.flow.conflate

/**
 * Live CameraX preview. Starts the camera, sends every frame to [onFrame] and keeps the
 * analysis rotation in step with the display. Draws nothing on top; see [CountOverlay].
 */
@Composable
fun CameraFeed(
    isCameraPaused: Boolean,
    // A still photo is on screen: the live preview leaves, and the camera restarts when it clears.
    isStillShown: Boolean,
    onFrame: (ImageProxy) -> Unit,
    modifier: Modifier = Modifier,
    // Gets the helper before the camera starts, so a counting view model can keep it.
    onCameraHelperReady: ((CameraHelper) -> Unit)? = null,
    onCameraReady: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    val cameraHelper = remember {
        CameraHelper(context, lifecycleOwner, ContextCompat.getMainExecutor(context))
    }

    // Mask the brief black window between bindToLifecycle() and the first real
    // surface frame. PreviewView exposes a STREAMING signal via previewStreamState;
    // we flip this flag the moment that fires so the placeholder fades out exactly
    // when live pixels are ready.
    var isPreviewStreaming by remember { mutableStateOf(false) }
    DisposableEffect(previewView) {
        val observer = androidx.lifecycle.Observer<PreviewView.StreamState> { state ->
            isPreviewStreaming = state == PreviewView.StreamState.STREAMING
        }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }

    // ── Start camera + begin collecting frames ────────────────────────────────
    LaunchedEffect(cameraHelper) {
        onCameraHelperReady?.invoke(cameraHelper)
        cameraHelper.startCamera(previewView)

        cameraHelper.frameFlow
            .conflate()
            .collect { image -> onFrame(image) }
    }

    LaunchedEffect(Unit) {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    // The activity has configChanges=orientation set, so it does NOT recreate on
    // rotation. CameraX therefore never refreshes ImageAnalysis.targetRotation on
    // its own — the analyzer keeps emitting frames in the orientation captured at
    // bind time, and pill centroids end up normalised against stale dims.
    //
    // We listen to DisplayManager.DisplayListener.onDisplayChanged so we catch
    // every rotation, including 180° landscape↔landscape flips (which don't
    // change Configuration.orientation or the previewView bounds and so would be
    // missed by either a configuration-keyed LaunchedEffect or a layout listener).
    DisposableEffect(previewView) {
        val displayManager = context.getSystemService(
            android.content.Context.DISPLAY_SERVICE
        ) as android.hardware.display.DisplayManager
        val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (previewView.display?.displayId == displayId) {
                    previewView.display?.rotation?.let { cameraHelper.setTargetRotation(it) }
                }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayManager.registerDisplayListener(displayListener, null)
        // Push current rotation immediately so the first frames after bind are
        // aligned (the listener only fires on subsequent display changes).
        previewView.display?.rotation?.let { cameraHelper.setTargetRotation(it) }
        onDispose { displayManager.unregisterDisplayListener(displayListener) }
    }

    // ── Pause / Resume ────────────────────────────────────────────────────────
    LaunchedEffect(isCameraPaused) {
        if (isCameraPaused) cameraHelper.pauseCamera()
        else cameraHelper.resumeCamera(previewView)
    }

    // Resume live camera when the captured still is cleared.
    LaunchedEffect(isStillShown) {
        if (!isStillShown) {
            cameraHelper.resumeCamera(previewView)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!isStillShown) {

            // ── CAMERA PREVIEW ────────────────────────────────────────────
            // Pinch-to-zoom is intentionally NOT wired up: the preview must
            // stay at the camera's default (1×) framing on every screen, so we
            // don't attach a detectTransformGestures handler here.
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        onCameraReady?.invoke()
                    }
            )

            // ── PREVIEW WARM-UP PLACEHOLDER ───────────────────────────────
            // Covers the black gap between bindToLifecycle() and the first
            // live frame. Fades out the moment PreviewView reports STREAMING.
            // No spinner — just a neutral fill so the warm-up reads as a calm
            // dark screen rather than a "loading" state.
            AnimatedVisibility(
                visible = !isPreviewStreaming,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1F1F1F)),
                )
            }
        }
    }
}
