@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH")

package com.rite.pillcounting.feature.pillCountScan.presentation.compose

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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.utils.compose.WorkflowStepper
import com.rite.pillcounting.feature.pillCountScan.domain.model.DetectedPill
import com.rite.pillcounting.feature.pillCountScan.presentation.logic.CameraHelper
import com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.PillScanningViewModel
import kotlinx.coroutines.flow.conflate

// =========================================================
// ZOOM CONTROL COMPOSABLE
// =========================================================
@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoomControls(
    zoom: Float,
    min: Float = 1f,
    max: Float = 3f,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .width(220.dp)
            .rotate(-90f)
            .padding(top = 130.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp)
        ) {
            Slider(
                value = zoom,
                onValueChange = onChange,
                valueRange = min..max,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.White, CircleShape)
                    )
                },
                track = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(
                                MaterialTheme.colorScheme.secondary,
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            )
        }
    }
}

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
    onPreviewStarted: (() -> Unit)? = null,
    onPreviewSizeKnown: ((width: Int, height: Int) -> Unit)? = null
) {
    val context        = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val previewView    = remember { PreviewView(context) }

    val cameraHelper = remember {
        CameraHelper(context, lifecycleOwner, ContextCompat.getMainExecutor(context))
    }

    val zoomRatio = remember { mutableFloatStateOf(1f) }
    val minZoom   = 1f
    val maxZoom   = 2f

    var showPop by remember { mutableStateOf(false) }
    var popKey  by remember { mutableIntStateOf(0) }
    var popText by remember { mutableStateOf("+0") }

    // ── ViewModel state ───────────────────────────────────────────────────────
    val stepType          by viewModel.currentStep.collectAsState()
    val steps             by viewModel.steps.collectAsState()
    val capturedBitmap    by viewModel.capturedBitmap.collectAsState()
    val showCaptureEffect by viewModel.showFlash.collectAsState()

    // ── Tray detections: rect is in ORIGINAL IMAGE PIXEL space ───────────────
    // We collect directly from the ViewModel here so the caller (screen/fragment)
    // does not need to pass them as a parameter — the wiring is self-contained.
    val trayDetections  by viewModel.trayDetections.collectAsState()
    val uiState         by viewModel.uiState.collectAsState()
    val gloveDetections = uiState.gloveDetections

    // ── Zoom ──────────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        cameraHelper.zoomFlow.collect { zoomRatio.value = it }
    }

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

    LaunchedEffect(capturedBitmap) {
        if (capturedBitmap == null) cameraHelper.resumeCamera(previewView)
    }

    // =========================================================
    // MAIN LAYOUT
    // =========================================================
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (capturedBitmap == null) {

                // ── CAMERA PREVIEW ────────────────────────────────────────────
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            onPreviewSizeKnown?.invoke(size.width, size.height)
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                val current = cameraHelper.getCurrentZoomRatio() ?: 1f
                                val target  = (current * zoom).coerceIn(minZoom, maxZoom)
                                zoomRatio.floatValue = target
                                cameraHelper.setZoom(target)
                            }
                        }
                )

                // ── COUNT POP ANIMATION ───────────────────────────────────────
                AnimatedVisibility(
                    visible  = showPop,
                    enter    = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }) + scaleIn(),
                    exit     = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(1f)
                ) {
                    key(popKey) { AddCountBubble(text = popText) }
                }

                // ── GLOVE WARNING ─────────────────────────────────────────────
                val noGlovesDetected = gloveDetections.any { it.className == "no_gloves" }
                val glovesDetected   = gloveDetections.any { it.className == "gloves" }

                if (noGlovesDetected || glovesDetected) {
                    val isWarning = noGlovesDetected // Warning takes priority
                    val bgColor   = if (isWarning) Color(0xFFDC0000) else Color(0xFF00C800)
                    val label     = if (isWarning) "NO GLOVES" else "GLOVES DETECTED"
                    val icon      = if (isWarning) androidx.compose.material.icons.Icons.Default.Warning else androidx.compose.material.icons.Icons.Default.CheckCircle

                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 100.dp, end = 16.dp)
                            .background(bgColor, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // ── OVERLAY CANVAS ────────────────────────────────────────────
                Canvas(modifier = Modifier.matchParentSize()) {

                    val previewW    = size.width
                    val previewH    = size.height
                    val isLandscape = previewW > previewH

                    // Align frame dimensions to screen orientation
                    val actualFrameW = if (isLandscape)
                        maxOf(imageFrameWidth, imageFrameHeight).toFloat()
                    else
                        minOf(imageFrameWidth, imageFrameHeight).toFloat()

                    val actualFrameH = if (isLandscape)
                        minOf(imageFrameWidth, imageFrameHeight).toFloat()
                    else
                        maxOf(imageFrameWidth, imageFrameHeight).toFloat()

                    if (actualFrameW <= 0f || actualFrameH <= 0f) return@Canvas

                    // ── FILL_CENTER math ──────────────────────────────────────
                    // Reproduces the exact crop/scale PreviewView applies so every
                    // coordinate mapping stays pixel-accurate on all screen sizes.
                    val scale   = maxOf(previewW / actualFrameW, previewH / actualFrameH)
                    val scaledW = actualFrameW * scale
                    val scaledH = actualFrameH * scale
                    // offsetX / offsetY are negative when the image is cropped
                    val offsetX = (previewW - scaledW) / 2f
                    val offsetY = (previewH - scaledH) / 2f

                    // Convert an original-image PIXEL coordinate → screen pixel
                    fun imgX(px: Float) = px * scale + offsetX
                    fun imgY(py: Float) = py * scale + offsetY

                    // ─────────────────────────────────────────────────────────
                    // 1.  TRAY BOUNDING BOXES
                    //     trayDetection.rect values are in original image pixels
                    //     (produced by TrayDetector.reverseLetterbox).
                    // ─────────────────────────────────────────────────────────
                    trayDetections.forEach { tray ->

                        val sLeft   = imgX(tray.rect.left)
                        val sTop    = imgY(tray.rect.top)
                        val sRight  = imgX(tray.rect.right)
                        val sBottom = imgY(tray.rect.bottom)
                        val sWidth  = sRight  - sLeft
                        val sHeight = sBottom - sTop

                        // Skip boxes that are completely outside the viewport
                        if (sRight <= 0f || sLeft >= previewW ||
                            sBottom <= 0f || sTop >= previewH
                        ) return@forEach

                        // Semi-transparent green fill (~13 % opacity)
                        drawRect(
                            color   = Color(0x2200C853),
                            topLeft = Offset(sLeft, sTop),
                            size    = Size(sWidth, sHeight)
                        )

                        // Solid green border
                        drawRect(
                            color   = Color(0xFF00C853),
                            topLeft = Offset(sLeft, sTop),
                            size    = Size(sWidth, sHeight),
                            style   = Stroke(width = 3.dp.toPx())
                        )

                        // White corner accent marks for clarity
                        val cLen  = 20.dp.toPx()
                        val cStroke = 4.dp.toPx()
                        listOf(
                            // top-left
                            Offset(sLeft, sTop)     to Offset(sLeft + cLen, sTop),
                            Offset(sLeft, sTop)     to Offset(sLeft, sTop + cLen),
                            // top-right
                            Offset(sRight, sTop)    to Offset(sRight - cLen, sTop),
                            Offset(sRight, sTop)    to Offset(sRight, sTop + cLen),
                            // bottom-left
                            Offset(sLeft, sBottom)  to Offset(sLeft + cLen, sBottom),
                            Offset(sLeft, sBottom)  to Offset(sLeft, sBottom - cLen),
                            // bottom-right
                            Offset(sRight, sBottom) to Offset(sRight - cLen, sBottom),
                            Offset(sRight, sBottom) to Offset(sRight, sBottom - cLen)
                        ).forEach { (start, end) ->
                            drawLine(
                                color       = Color.White,
                                start       = start,
                                end         = end,
                                strokeWidth = cStroke
                            )
                        }
                    }

                    // ─────────────────────────────────────────────────────────
                    // 2.  PILL DOTS
                    //     pill.x / pill.y are NORMALISED [0..1] relative to the
                    //     original image frame dimensions.
                    //     Multiply by scaledW/H (not actualFrameW/H) then shift
                    //     by offsetX/Y — same transform as the tray boxes above.
                    // ─────────────────────────────────────────────────────────
                    val mapped = pills.map { pill ->
                        val px = pill.x * scaledW + offsetX
                        val py = pill.y * scaledH + offsetY
                        pill to Offset(px, py)
                    }

                    // Propagate only the on-screen pills back to ViewModel
                    viewModel.updateFilteredPills(mapped.map { it.first })
                    onFilteredCountChanged(mapped.size)

                    drawIntoCanvas {
                        mapped.forEachIndexed { i, (_, pos) ->
                            // Discard dots outside the visible crop area
                            if (pos.x !in 0f..previewW || pos.y !in 0f..previewH) return@forEachIndexed

                            val isLast      = i == mapped.lastIndex
                            val outerRadius = 7.dp.toPx()
                            val strokeWidth = 2.dp.toPx()

                            // Dark shadow fill for contrast
                            drawCircle(
                                color  = Color.Black.copy(alpha = 0.6f),
                                radius = outerRadius,
                                center = pos
                            )
                            // Coloured ring: yellow = newest detection, white = rest
                            drawCircle(
                                color  = if (isLast) Color.Yellow else Color.White,
                                radius = outerRadius,
                                center = pos,
                                style  = Stroke(width = strokeWidth)
                            )
                        }
                    }

                    // ─────────────────────────────────────────────────────────
                    // 3.  GLOVE DETECTION BOXES
                    //     gloveDetection.rect values are in original image pixel
                    //     coordinates — same space as tray boxes above.
                    //     green = gloves detected, red = no_gloves.
                    // ─────────────────────────────────────────────────────────
                    val gloveColor = mapOf(
                        "gloves"    to Color(0xFF00C800),  // green
                        "no_gloves" to Color(0xFFDC0000)   // red
                    )

                    drawIntoCanvas { canvas ->
                        gloveDetections.forEach { glove ->
                            val sLeft   = imgX(glove.rect.left)
                            val sTop    = imgY(glove.rect.top)
                            val sRight  = imgX(glove.rect.right)
                            val sBottom = imgY(glove.rect.bottom)
                            val sWidth  = sRight  - sLeft
                            val sHeight = sBottom - sTop

                            if (sRight <= 0f || sLeft >= previewW ||
                                sBottom <= 0f || sTop >= previewH
                            ) return@forEach

                            val boxColor = gloveColor[glove.className] ?: Color.White

                            // Semi-transparent fill
                            drawRect(
                                color   = boxColor.copy(alpha = 0.15f),
                                topLeft = Offset(sLeft, sTop),
                                size    = Size(sWidth, sHeight)
                            )
                            // Solid border
                            drawRect(
                                color   = boxColor,
                                topLeft = Offset(sLeft, sTop),
                                size    = Size(sWidth, sHeight),
                                style   = Stroke(width = 3.dp.toPx())
                            )

                            // Label: "gloves 87%"  /  "no_gloves 72%"
                            val label    = "${glove.className} ${(glove.confidence * 100).toInt()}%"
                            val textSize = 36f  // ~14sp equivalent in canvas units
                            val textPaint = android.graphics.Paint().apply {
                                color     = android.graphics.Color.WHITE
                                this.textSize = textSize
                                isAntiAlias   = true
                                typeface  = android.graphics.Typeface.DEFAULT_BOLD
                            }
                            val bgPaint = android.graphics.Paint().apply {
                                color = when (glove.className) {
                                    "gloves"    -> android.graphics.Color.argb(200, 0, 180, 0)
                                    else        -> android.graphics.Color.argb(200, 180, 0, 0)
                                }
                            }
                            val textBounds = android.graphics.Rect()
                            textPaint.getTextBounds(label, 0, label.length, textBounds)
                            val labelW   = textBounds.width()  + 12f
                            val labelH   = textBounds.height() + 8f
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

                // ── ZOOM SLIDER ───────────────────────────────────────────────
                if (stepType != StepState.VIAL) {
                    ZoomControls(
                        zoom     = zoomRatio.floatValue,
                        min      = minZoom,
                        max      = maxZoom,
                        onChange = { value ->
                            val stepped = (value * 10f).toInt() / 10f
                            zoomRatio.floatValue = stepped
                            cameraHelper.setZoom(stepped)
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }

                // ── WORKFLOW STEPPER ──────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                ) {
                    WorkflowStepper(steps = steps, currentStep = stepType)
                }

            } else {
                // ── CAPTURED BITMAP VIEW ──────────────────────────────────────
                Image(
                    bitmap             = capturedBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.FillHeight
                )
                if (showCaptureEffect) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                }
            }
        }
    }
}