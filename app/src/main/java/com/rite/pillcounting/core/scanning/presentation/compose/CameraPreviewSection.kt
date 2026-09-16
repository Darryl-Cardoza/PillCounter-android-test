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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.domain.model.DetectedPill
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.scanning.logic.TrayClass
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.common.OverlayUtils
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
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
    val showCaptureEffect by viewModel.showFlash.collectAsState()

    // ── Tray detections: rect is in ORIGINAL IMAGE PIXEL space ───────────────
    // We collect directly from the ViewModel here so the caller (screen/fragment)
    // does not need to pass them as a parameter — the wiring is self-contained.
    val trayDetections by viewModel.trayDetections.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val stepType by viewModel.currentStep.collectAsState()
    // isDispense drives the pill-dot green/red split — stock counts have no target.
    val txnInfo by viewModel.txnInfo.collectAsState()

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

                    // ─────────────────────────────────────────────────────────
                    // 1.  TRAY BOUNDING BOX (chute is detected for exclusion
                    //     math but NOT rendered).
                    // ─────────────────────────────────────────────────────────
                    trayDetections.forEach { tray ->
                        // Skip chute detections in the UI — they exist only to
                        // exclude pills in the chute area from the count.
                        if (tray.cls == TrayClass.CHUTE) {
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

                    // ── Target vs excess split ────────────────────────────────
                    // The pills here are ON TRAY (chute pills are already excluded
                    // upstream). The target count stays on the tray and is drawn
                    // GREEN (dispense these); the EXCESS pills nearest the chute are
                    // drawn RED (push these into the chute). "Nearest the chute" is
                    // measured in original-image pixel space, which already tracks
                    // the display orientation, so the split is correct in both
                    // portrait and landscape.
                    // Pills already captured/added in THIS session reduce how many
                    // still need to be dispensed, so the green threshold must track
                    // the REMAINING target, not the original target. Mirrors the
                    // "already counted" total shown in InformationPanelSection.
                    val alreadyCounted =
                        if (uiState.scanType == CountType.REGULAR.toString()) {
                            uiState.stockCountSessionTotal
                        } else {
                            uiState.txnDetailHistory.sumOf { it.count }
                        }
                    val excessCount = OverlayUtils.excessPillCount(
                        pillCount = pills.size,
                        targetCount = uiState.targetCount,
                        alreadyCounted = alreadyCounted,
                        isDispense = txnInfo?.isDispense,
                        stepType = stepType,
                    )
                    val excessIndices: Set<Int> = if (excessCount <= 0) {
                        emptySet()
                    } else {
                        val chuteRect = trayDetections
                            .firstOrNull { it.cls == TrayClass.CHUTE }
                            ?.rect
                        // Fallback when the chute isn't segmented this frame: the
                        // bottom-centre of the tray, else of the frame (mirrors the
                        // Python POC).
                        val fallback = trayDetections
                            .firstOrNull { it.cls == TrayClass.TRAY }
                            ?.rect
                            ?.let { Offset(it.centerX(), it.bottom) }
                            ?: Offset(actualFrameW / 2f, actualFrameH)
                        // Squared distance is enough for ordering (monotonic).
                        pills.indices
                            .sortedBy { idx ->
                                val cx = pills[idx].x * actualFrameW
                                val cy = pills[idx].y * actualFrameH
                                if (chuteRect != null) {
                                    val nx = cx.coerceIn(chuteRect.left, chuteRect.right)
                                    val ny = cy.coerceIn(chuteRect.top, chuteRect.bottom)
                                    val dx = cx - nx; val dy = cy - ny
                                    dx * dx + dy * dy
                                } else {
                                    val dx = cx - fallback.x; val dy = cy - fallback.y
                                    dx * dx + dy * dy
                                }
                            }
                            .take(excessCount)
                            .toSet()
                    }

                    val pillTarget = Color(0xFF00C853)   // GREEN — keep on tray (dispense)
                    val pillExcess = Color.Red           // RED   — excess, push into chute
                    drawIntoCanvas {
                        for (i in pills.indices) {
                            val pill = pills[i]
                            val px = pill.x * scaledW + offsetX
                            val py = pill.y * scaledH + offsetY
                            // Discard dots outside the visible crop area
                            if (px !in 0f..previewW || py !in 0f..previewH) continue

                            val pos = Offset(px, py)
                            val color = if (i in excessIndices) pillExcess else pillTarget
                            // Light translucent fill (not dark) + solid coloured
                            // border ring: green = target (keep), red = excess.
                            drawCircle(
                                color = color.copy(alpha = 0.35f),
                                radius = pillOuterRadiusPx,
                                center = pos
                            )
                            drawCircle(
                                color = color,
                                radius = pillOuterRadiusPx,
                                center = pos,
                                style = Stroke(width = pillStrokePx)
                            )
                        }
                    }

                }

                // ── WORKFLOW STEPPER ──────────────────────────────────────────
                // Both orientations now render the stepper inside the bottom count
                // bar of the full-bleed count overlay (see CountModeLandscape /
                // CountModeOverlay), so there is no separate floating stepper here.
                // During the pre-scan stages the workflow steps list is empty, so a
                // floating stepper would have nothing to show anyway.

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
