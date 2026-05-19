package com.rite.pillcounting.feature.barcodeScan.presentation

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.compose.DialogField
import com.rite.pillcounting.core.utils.compose.HideSystemBarsInCurrentWindow
import com.rite.pillcounting.core.utils.compose.VerifyRxDetailsInlinePanel
import com.rite.pillcounting.core.utils.compose.VerifyStockBottleInlinePanel
import com.rite.pillcounting.feature.barcodeScan.domain.data.ScanBarcodeEvent
import com.rite.pillcounting.feature.barcodeScan.domain.model.ScanBarcodeUiState
import com.rite.pillcounting.feature.barcodeScan.presentation.analyzer.BarcodeAnalyzer
import com.rite.pillcounting.feature.barcodeScan.presentation.compose.ScannerView
import com.rite.pillcounting.feature.barcodeScan.presentation.viewmodel.ScanBarcodeViewModel
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.StepTitleWithSpeech
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * Stateless composable for the barcode scanning screen.
 * Handles lifecycle, permissions, and analyzer interaction.
 */
@Composable
fun ScanBarCodeScreenContent(
    navController: NavController,
    uiState: ScanBarcodeUiState,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onEvent: (ScanBarcodeEvent) -> Unit,
    analyzer: BarcodeAnalyzer,
    viewModel: ScanBarcodeViewModel = hiltViewModel(),
    batchId: Long,
    showInlineRxPanel: Boolean = false,
    onRxCancel: () -> Unit = {},
    onRxProceed: () -> Unit = {},
    // Landscape-only inline stock-bottle verification panel. Same layout strategy
    // as the inline RX panel — overlays the right side of the scanner without
    // covering it with a separate popup window. Pre-existing popup-based stock
    // sheet had a visible inset gap at the screen edges in landscape; this
    // inline panel reaches the edges cleanly.
    showInlineStockPanel: Boolean = false,
    onStockCancel: () -> Unit = {},
    onStockProceed: () -> Unit = {},
    onStockStatusChange: (com.rite.pillcounting.core.utils.compose.ContainerStatus) -> Unit = {},
) {

    val isSoundEnabled = viewModel.isSoundEnabled.collectAsState().value
    val scanType by viewModel.txnScanType.collectAsStateWithLifecycle()
    val latestScanType by rememberUpdatedState(scanType)
    val context = LocalContext.current
    val stepType = if (latestScanType == ScanType.RX_LABEL) {
        StepState.RX_LABEL
    } else if (latestScanType == ScanType.STOCK_COUNT) {
        StepState.STOCK_COUNT
    } else {
        StepState.SCAN
    }

    // Re-assert immersive (no nav buttons / status bar) on the activity window
    // whenever an inline panel is showing. The activity is already immersive
    // globally, but some focus transitions can momentarily re-show bars; this
    // SideEffect re-hides them as the panel composes/recomposes.
    if (showInlineRxPanel || showInlineStockPanel) {
        HideSystemBarsInCurrentWindow()
    }

    // In landscape RX-label flow the inline panel overlays the right side, but the
    // camera preview stays full-bleed in the background so there's no dead black
    // strip behind the panel. Tablet gets a wider drawer (~40% of screen width)
    // matching the tablet-landscape proportions used elsewhere.
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    // Aim for ~30% of the landscape width. Clamp so it's never absurdly narrow
    // on small devices or absurdly wide on large tablets. Easy to nudge: bump
    // the 0.3f multiplier or the clamp bounds.
    val rxPanelWidth = (configuration.screenWidthDp.dp * 0.3f).coerceIn(
        if (isTablet) 320.dp else 280.dp,
        if (isTablet) 440.dp else 340.dp,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        ScannerView(
            analyzer = analyzer,
            isActive = uiState.isScannerActive,
            singleScanMode = true,
            onBarcodeScanned = { value, imagePath ->
                val decoder = BarcodeDecoder()
                val cleanedImagePath = imagePath.orEmpty()

                if (value.isBlank()) {
                    onEvent(ScanBarcodeEvent.InvalidScan)
                    return@ScannerView
                }

                val isGs1 = decoder.isGs1Barcode(value)
                val decoded = if (isGs1) decoder.decode(value) else null

                if (latestScanType == ScanType.RX_LABEL) {
                    onEvent(
                        ScanBarcodeEvent.ScanBarcode(
                            gtin14 = value,
                            imagePath = cleanedImagePath,
                            expiry = "",
                            lotNo = ""
                        )
                    )
                    return@ScannerView
                }

                val extractedGtin = if (isGs1) {
                    decoded?.gtin
                } else {
                    decoder.toGtin14(value)
                }

                val finalGtin14 = extractedGtin?.let { decoder.toGtin14(it) } ?: ""

                val isInvalidGtin = finalGtin14.isBlank() ||
                        finalGtin14.length != 14 ||
                        !finalGtin14.all { it.isDigit() }

                if (isInvalidGtin) {
                    onEvent(ScanBarcodeEvent.InvalidScan)
                    return@ScannerView
                }

                onEvent(
                    ScanBarcodeEvent.ScanBarcode(
                        gtin14 = finalGtin14,
                        imagePath = cleanedImagePath,
                        expiry = if (isGs1) decoded?.expirationDate.toString() else "",
                        lotNo = if (isGs1) decoded?.lotNumber.orEmpty() else ""
                    )
                )
            },
            onError = { exception ->
                onEvent(ScanBarcodeEvent.ScannerError(exception))
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 0.dp,
                    end = if (showInlineRxPanel || showInlineStockPanel) rxPanelWidth else 0.dp,
                    top = 0.dp,
                    bottom = 0.dp,
                )
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            StepTitleWithSpeech(stepType = stepType, isSoundOverride = isSoundEnabled)

            BackButton(
                modifier = Modifier.align(Alignment.CenterStart),
                navController = navController,
                showBox = false,
                onClick = {
                    analyzer.pause()
                    navController.popBackStack()
                }
            )
        }
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (uiState.error != null) {
            showToast(context = context, message = uiState.error, duration = Toast.LENGTH_SHORT)
            viewModel.clearToast()
        }

        if (latestScanType == ScanType.STOCK_COUNT)
            Text(
                text = "${stringResource(R.string.batch)} $batchId",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(
                        AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(50.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                color = AppTheme.extendedColors.textColor,
            )

        // Tap-outside scrim: a transparent, full-screen click target that intercepts
        // taps to the left of the panel. Only enabled when the panel is visible.
        AnimatedVisibility(
            visible = showInlineRxPanel,
            enter = fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRxCancel,
                    )
            )
        }

        // The panel itself — slides in from the right, with swipe-right-to-dismiss.
        AnimatedVisibility(
            visible = showInlineRxPanel,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 260)
            ) + fadeIn(animationSpec = tween(durationMillis = 260)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 220)
            ) + fadeOut(animationSpec = tween(durationMillis = 220)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(rxPanelWidth)
        ) {
            val density = LocalDensity.current
            val dismissThresholdPx = with(density) { (rxPanelWidth * 0.35f).toPx() }
            // Animatable gives direct (non-recomposing) updates while dragging and
            // a smooth animateTo() for spring-back — feels much less laggy than
            // animateFloatAsState driven by a State<Float>.
            val dragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(dragOffset.value.toInt(), 0) }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            // Only allow rightward drag (positive); clamp to non-negative.
                            val next = (dragOffset.value + delta).coerceAtLeast(0f)
                            coroutineScope.launch { dragOffset.snapTo(next) }
                        },
                        onDragStopped = {
                            if (dragOffset.value >= dismissThresholdPx) {
                                onRxCancel()
                            } else {
                                coroutineScope.launch {
                                    dragOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = tween(durationMillis = 200)
                                    )
                                }
                            }
                        }
                    )
                    // Swallow taps so they don't bubble to the scrim and dismiss.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
            ) {
                VerifyRxDetailsInlinePanel(
                    drugName = uiState.drugName,
                    quantity = uiState.qty.toString(),
                    bucket = uiState.selectedBucketId,
                    ndcNumber = uiState.ndc,
                    rxNumber = uiState.rxNo.toString(),
                    onCancel = onRxCancel,
                    onProceed = onRxProceed,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(
                            RoundedCornerShape(
                                topStart = 20.dp,
                                bottomStart = 20.dp,
                                topEnd = 0.dp,
                                bottomEnd = 0.dp,
                            )
                        ),
                    cornerRadius = 0.dp,
                )
            }
        }

        // ── Inline stock-bottle panel (landscape) ───────────────────────────
        // Same scrim + slide-in + swipe-right-to-dismiss treatment as the RX
        // inline panel above. The panel sits flush against the right edge of
        // the camera screen — no popup window, so no inset gap at the bottom
        // or right edge that the popup-based drawer suffered from.
        AnimatedVisibility(
            visible = showInlineStockPanel,
            enter = fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onStockCancel,
                    )
            )
        }

        AnimatedVisibility(
            visible = showInlineStockPanel,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 260)
            ) + fadeIn(animationSpec = tween(durationMillis = 260)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 220)
            ) + fadeOut(animationSpec = tween(durationMillis = 220)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(rxPanelWidth)
        ) {
            val density = LocalDensity.current
            val dismissThresholdPx = with(density) { (rxPanelWidth * 0.35f).toPx() }
            val dragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(dragOffset.value.toInt(), 0) }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            val next = (dragOffset.value + delta).coerceAtLeast(0f)
                            coroutineScope.launch { dragOffset.snapTo(next) }
                        },
                        onDragStopped = {
                            if (dragOffset.value >= dismissThresholdPx) {
                                onStockCancel()
                            } else {
                                coroutineScope.launch {
                                    dragOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = tween(durationMillis = 200)
                                    )
                                }
                            }
                        }
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
            ) {
                VerifyStockBottleInlinePanel(
                    fields = listOf(
                        DialogField(stringResource(R.string.ndc_number), uiState.ndc),
                        // Drug name spans the full row in landscape so long names
                        // (e.g. "Tramadol Hydrochloride") have horizontal room
                        // instead of wrapping inside a half-width column.
                        DialogField(stringResource(R.string.drugname), uiState.drugName, fullWidth = true),
                        DialogField(stringResource(R.string.quantity), uiState.qty.toString()),
                        DialogField(stringResource(R.string.bucket), uiState.selectedBucketId),
                    ),
                    title = stringResource(R.string.label_scanned_successfully),
                    selectedContainerStatus = uiState.selectedContainerStatus,
                    onContainerStatusChange = onStockStatusChange,
                    showSealedButtons = true,
                    onCancel = onStockCancel,
                    onProceed = onStockProceed,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(
                            RoundedCornerShape(
                                topStart = 20.dp,
                                bottomStart = 20.dp,
                                topEnd = 0.dp,
                                bottomEnd = 0.dp,
                            )
                        ),
                    cornerRadius = 0.dp,
                )
            }
        }
    }

}

@Composable
fun FocusAnimationOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "focusPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(responsiveDp(140.dp))
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .background(
                    color = Color.Transparent,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp)
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
        )
    }
}
