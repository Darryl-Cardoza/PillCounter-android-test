@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH")

package com.rite.pillcounting.core.scanning.presentation.compose

import android.annotation.SuppressLint
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.compose.WorkflowStepper
import com.rite.pillcounting.core.scanning.domain.model.DetectedPill
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.scanning.logic.GloveDetector
import com.rite.pillcounting.core.scanning.logic.TrayColor
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import kotlinx.coroutines.flow.conflate

// =========================================================
// MAIN CAMERA PREVIEW
// =========================================================
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun CameraPreviewSection(
    viewModel: PillScanningViewModel,
    pills: List<DetectedPill>,
    isCameraPaused: Boolean,
    imageFrameWidth: Int,
    imageFrameHeight: Int,
    onFrame: (ImageProxy) -> Unit,
    onFilteredCountChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showGloveIcon: Boolean = true,
    onPreviewStarted: (() -> Unit)? = null,
    onPreviewSizeKnown: ((width: Int, height: Int) -> Unit)? = null
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

    var showPop by remember { mutableStateOf(false) }
    var popKey by remember { mutableIntStateOf(0) }
    var popText by remember { mutableStateOf("+0") }

    // ── ViewModel state ───────────────────────────────────────────────────────
    val stepType by viewModel.currentStep.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
    val showCaptureEffect by viewModel.showFlash.collectAsState()

    // ── Tray detections: rect is in ORIGINAL IMAGE PIXEL space ───────────────
    // We collect directly from the ViewModel here so the caller (screen/fragment)
    // does not need to pass them as a parameter — the wiring is self-contained.
    val trayDetections by viewModel.trayDetections.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val gloveDetections = uiState.gloveDetections
    // Sticky once the workflow has seen gloves at high confidence; reset only when
    // the workflow resumes from idle (see PillScanningViewModel.resetGloveDetection).
    val glovesDetectedSticky by viewModel.glovesDetected.collectAsState()

    // ── Start camera + begin collecting frames ────────────────────────────────
    LaunchedEffect(cameraHelper) {
        viewModel.attachCameraHelper(cameraHelper)
        cameraHelper.startCamera(previewView)
        onPreviewStarted?.invoke()

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

    // ── Count pop animation ───────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        viewModel.addPopEvents.collect { count ->
            popText = "+$count"
            popKey++
            showPop = true
            kotlinx.coroutines.delay(700)
            showPop = false
        }
    }

    // ── Pause / Resume ────────────────────────────────────────────────────────
    LaunchedEffect(isCameraPaused) {
        if (isCameraPaused) cameraHelper.pauseCamera()
        else cameraHelper.resumeCamera(previewView)
    }

    // Hoisted overlay scratch objects. The Canvas overlay below redraws on every
    // frame of detection updates, and allocating Paint/Rect inside the draw loop
    // is the kind of per-frame churn that wrecks frame budget on weak devices.
    // Paints are immutable per-class so we can share them across frames.
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 36f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val glovesBgPaint = remember {
        android.graphics.Paint().apply { color = android.graphics.Color.argb(200, 0, 180, 0) }
    }
    val noGlovesBgPaint = remember {
        android.graphics.Paint().apply { color = android.graphics.Color.argb(200, 180, 0, 0) }
    }
    val trayLabelBgPaint = remember { android.graphics.Paint() }
    val labelBounds = remember { android.graphics.Rect() }

    // Resume live camera when the captured still is cleared, but NOT while the
    // confirm-completion dialog is open — processCapturedImage() nulls the bitmap
    // just before the dialog appears, which would restart the camera underneath it.
    // When the dialog is dismissed (cancel), showConfirmDialog flips back to false
    // and this effect re-fires, restoring the live preview correctly.
    LaunchedEffect(capturedBitmap, uiState.showConfirmDialog) {
        if (capturedBitmap == null && !uiState.showConfirmDialog) {
            cameraHelper.resumeCamera(previewView)
        }
    }

    val glovesDetectedDesc = stringResource(R.string.cd_gloves_detected)
    val noGlovesDetectedDesc = stringResource(R.string.cd_no_gloves_detected)

    // =========================================================
    // MAIN LAYOUT
    // =========================================================
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (capturedBitmap == null) {

                // ── CAMERA PREVIEW ────────────────────────────────────────────
                // Pinch-to-zoom is intentionally NOT wired up: the preview must
                // stay at the camera's default (1×) framing on every screen, so we
                // don't attach a detectTransformGestures handler here.
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            onPreviewSizeKnown?.invoke(size.width, size.height)
                        }
                )

                // ── PREVIEW WARM-UP PLACEHOLDER ───────────────────────────────
                // Covers the black gap between bindToLifecycle() and the first
                // live frame. Fades out the moment PreviewView reports STREAMING.
                // No spinner — just a neutral fill so the warm-up reads as a calm
                // dark screen rather than a "loading" state.
                androidx.compose.animation.AnimatedVisibility(
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

                // ── COUNT POP ANIMATION ───────────────────────────────────────
                AnimatedVisibility(
                    visible = showPop,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }) + scaleIn(),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(1f)
                ) {
                    key(popKey) { AddCountBubble(text = popText) }
                }

                // ── GLOVE STATUS HAND ICON ────────────────────────────────────
                // Only shown for hazardous drugs (showGloveIcon = true). Green hand
                // once gloves are confirmed; red until then.
                if (showGloveIcon) {
                    val handTint = if (glovesDetectedSticky) Color.Green else Color.Red
                    val gloveConfig = LocalConfiguration.current
                    val gloveIsLandscape = gloveConfig.orientation ==
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val gloveEndPadding = if (gloveIsLandscape) {
                        (gloveConfig.screenWidthDp * 0.3f).dp + 16.dp
                    } else {
                        16.dp
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 100.dp, end = gloveEndPadding)
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FrontHand,
                            contentDescription = if (glovesDetectedSticky) glovesDetectedDesc else noGlovesDetectedDesc,
                            tint = handTint,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(26.dp)
                        )
                    }
                }

                // ── OVERLAY CANVAS ────────────────────────────────────────────
                Canvas(modifier = Modifier.matchParentSize()) {

                    val previewW = size.width
                    val previewH = size.height

                    // ImageAnalysis targetRotation is kept in sync with the display
                    // rotation (CameraHelper.setTargetRotation), so imageFrameWidth/
                    // imageFrameHeight already match the user-facing orientation.
                    // Use them directly — no min/max swap.
                    val actualFrameW = imageFrameWidth.toFloat()
                    val actualFrameH = imageFrameHeight.toFloat()

                    if (actualFrameW <= 0f || actualFrameH <= 0f) return@Canvas

                    // ── FILL_CENTER math ──────────────────────────────────────
                    // Reproduces the exact crop/scale PreviewView applies so every
                    // coordinate mapping stays pixel-accurate on all screen sizes.
                    val scale = maxOf(previewW / actualFrameW, previewH / actualFrameH)
                    val scaledW = actualFrameW * scale
                    val scaledH = actualFrameH * scale
                    // offsetX / offsetY are negative when the image is cropped
                    val offsetX = (previewW - scaledW) / 2f
                    val offsetY = (previewH - scaledH) / 2f

                    // Convert an original-image PIXEL coordinate → screen pixel
                    fun imgX(px: Float) = px * scale + offsetX
                    fun imgY(py: Float) = py * scale + offsetY

                    // Hoist dp→px conversions once per frame (was per-tray/per-pill).
                    val trayStrokePx = 3.dp.toPx()
                    val cornerLenPx = 20.dp.toPx()
                    val cornerStrokePx = 4.dp.toPx()
                    val pillOuterRadiusPx = 7.dp.toPx()
                    val pillStrokePx = 2.dp.toPx()
                    val gloveStrokePx = 3.dp.toPx()

                    // ─────────────────────────────────────────────────────────
                    // 1.  TRAY BOUNDING BOX (chute is detected for exclusion
                    //     math but NOT rendered).
                    // ─────────────────────────────────────────────────────────
                    trayDetections.forEach { tray ->
                        // Skip chute detections in the UI — they exist only to
                        // exclude pills in the chute area from the count.
                        if (tray.cls == com.rite.pillcounting.core.scanning.logic.TrayClass.CHUTE) {
                            return@forEach
                        }

                        val sLeft = imgX(tray.rect.left)
                        val sTop = imgY(tray.rect.top)
                        val sRight = imgX(tray.rect.right)
                        val sBottom = imgY(tray.rect.bottom)
                        val sWidth = sRight - sLeft
                        val sHeight = sBottom - sTop

                        // Skip boxes that are completely outside the viewport
                        if (sRight <= 0f || sLeft >= previewW ||
                            sBottom <= 0f || sTop >= previewH
                        ) return@forEach

                        // Semi-transparent green fill (~13 % opacity)
                        drawRect(
                            color = Color(0x2200C853),
                            topLeft = Offset(sLeft, sTop),
                            size = Size(sWidth, sHeight)
                        )

                        // Solid green border
                        drawRect(
                            color = Color(0xFF00C853),
                            topLeft = Offset(sLeft, sTop),
                            size = Size(sWidth, sHeight),
                            style = Stroke(width = trayStrokePx)
                        )

                        // White corner accent marks for clarity. Inlined as 8 drawLine
                        // calls (was a per-frame listOf-of-pairs allocation per tray).
                        val white = Color.White
                        // top-left
                        drawLine(white, Offset(sLeft, sTop), Offset(sLeft + cornerLenPx, sTop), strokeWidth = cornerStrokePx)
                        drawLine(white, Offset(sLeft, sTop), Offset(sLeft, sTop + cornerLenPx), strokeWidth = cornerStrokePx)
                        // top-right
                        drawLine(white, Offset(sRight, sTop), Offset(sRight - cornerLenPx, sTop), strokeWidth = cornerStrokePx)
                        drawLine(white, Offset(sRight, sTop), Offset(sRight, sTop + cornerLenPx), strokeWidth = cornerStrokePx)
                        // bottom-left
                        drawLine(white, Offset(sLeft, sBottom), Offset(sLeft + cornerLenPx, sBottom), strokeWidth = cornerStrokePx)
                        drawLine(white, Offset(sLeft, sBottom), Offset(sLeft, sBottom - cornerLenPx), strokeWidth = cornerStrokePx)
                        // bottom-right
                        drawLine(white, Offset(sRight, sBottom), Offset(sRight - cornerLenPx, sBottom), strokeWidth = cornerStrokePx)
                        drawLine(white, Offset(sRight, sBottom), Offset(sRight, sBottom - cornerLenPx), strokeWidth = cornerStrokePx)
                    }

                    // ─────────────────────────────────────────────────────────
                    // 2.  PILL DOTS
                    //     pill.x / pill.y are NORMALISED [0..1] relative to the
                    //     original image frame dimensions.
                    //     Multiply by scaledW/H (not actualFrameW/H) then shift
                    //     by offsetX/Y — same transform as the tray boxes above.
                    // ─────────────────────────────────────────────────────────
                    // Propagate the same filtered list to the ViewModel — behavior
                    // unchanged from when this used `mapped.map { it.first }`, just
                    // without the per-frame Pair list allocation.
                    viewModel.updateFilteredPills(pills)
                    onFilteredCountChanged(pills.size)

                    val pillShadow = Color.Black.copy(alpha = 0.6f)
                    val lastIdx = pills.lastIndex
                    drawIntoCanvas {
                        for (i in pills.indices) {
                            val pill = pills[i]
                            val px = pill.x * scaledW + offsetX
                            val py = pill.y * scaledH + offsetY
                            // Discard dots outside the visible crop area
                            if (px !in 0f..previewW || py !in 0f..previewH) continue

                            val pos = Offset(px, py)
                            // Dark shadow fill for contrast
                            drawCircle(
                                color = pillShadow,
                                radius = pillOuterRadiusPx,
                                center = pos
                            )
                            // Coloured ring: yellow = newest detection, white = rest
                            drawCircle(
                                color = if (i == lastIdx) Color.Yellow else Color.White,
                                radius = pillOuterRadiusPx,
                                center = pos,
                                style = Stroke(width = pillStrokePx)
                            )
                        }
                    }

                    // ─────────────────────────────────────────────────────────
                    // 3.  GLOVE DETECTION BOXES
                    //     gloveDetection.rect values are in original image pixel
                    //     coordinates — same space as tray boxes above.
                    //     green = gloves detected, red = no_gloves.
                    // ─────────────────────────────────────────────────────────
                    drawIntoCanvas { canvas ->
                        gloveDetections.forEach { glove ->
                            val sLeft = imgX(glove.rect.left)
                            val sTop = imgY(glove.rect.top)
                            val sRight = imgX(glove.rect.right)
                            val sBottom = imgY(glove.rect.bottom)
                            val sWidth = sRight - sLeft
                            val sHeight = sBottom - sTop

                            if (sRight <= 0f || sLeft >= previewW ||
                                sBottom <= 0f || sTop >= previewH
                            ) return@forEach

                            val boxColor = when (glove.className) {
                                GloveDetector.CLASS_GLOVES -> Color.Green
                                GloveDetector.CLASS_NO_GLOVES -> Color.Red
                                else -> Color.White
                            }

                            // Semi-transparent fill
                            drawRect(
                                color = boxColor.copy(alpha = 0.15f),
                                topLeft = Offset(sLeft, sTop),
                                size = Size(sWidth, sHeight)
                            )
                            // Solid border
                            drawRect(
                                color = boxColor,
                                topLeft = Offset(sLeft, sTop),
                                size = Size(sWidth, sHeight),
                                style = Stroke(width = gloveStrokePx)
                            )

                            // Label: "gloves 87%"  /  "no_gloves 72%"
                            // Paints + rect are hoisted via remember above so we
                            // don't allocate them every frame per glove.
                            val label = "${glove.className} ${(glove.confidence * 100).toInt()}%"
                            val bgPaint = if (glove.className == GloveDetector.CLASS_GLOVES) {
                                glovesBgPaint
                            } else {
                                noGlovesBgPaint
                            }
                            textPaint.getTextBounds(label, 0, label.length, labelBounds)
                            val labelW = labelBounds.width() + 12f
                            val labelH = labelBounds.height() + 8f
                            val labelTop = (sTop - labelH).coerceAtLeast(0f)

                            canvas.nativeCanvas.drawRect(
                                sLeft, labelTop,
                                sLeft + labelW, labelTop + labelH,
                                bgPaint
                            )
                            canvas.nativeCanvas.drawText(
                                label,
                                sLeft + 6f,
                                labelTop + labelH - 4f,
                                textPaint
                            )
                        }
                    }
                }

                // ── WORKFLOW STEPPER ──────────────────────────────────────────
                val configuration = LocalConfiguration.current
                val isLandscape =
                    configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val stepperBottomPadding = if (isLandscape) {
                    20.dp
                } else {
                    (configuration.screenHeightDp * 0.25f).dp + 20.dp
                }
                val stepperEndPadding = if (isLandscape) {
                    (configuration.screenWidthDp * 0.3f).dp
                } else {
                    0.dp
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = stepperBottomPadding, end = stepperEndPadding)
                ) {
                    WorkflowStepper(steps = steps, currentStep = stepType)
                }

            } else {
                // ── CAPTURED BITMAP VIEW ──────────────────────────────────────
                Image(
                    bitmap = capturedBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (showCaptureEffect) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                    )
                }
            }
        }
    }
}