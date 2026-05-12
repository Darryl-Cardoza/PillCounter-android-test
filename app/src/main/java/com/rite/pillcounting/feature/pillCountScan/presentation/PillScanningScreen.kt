package com.rite.pillcounting.feature.pillCountScan.presentation

import Screen
import android.R.attr.maxHeight
import android.R.attr.maxWidth
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.compose.SplitResponsive
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.pillCountScan.domain.data.NavigationEvent
import com.rite.pillcounting.feature.pillCountScan.domain.data.PillScanningEvent
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.AddNoteDialog
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.CameraPreviewSection
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.InformationPanelSection
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.StepTitleWithSpeech
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.TargetPillsCountDialog
import com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@Composable
fun PillScanningScreen(
    navController: NavController,
    countType: String,
    viewModel: PillScanningViewModel = hiltViewModel()
) {
    val context = navController.context
    val uiState by viewModel.uiState.collectAsState()
    val logger = remember { AppLogger("PillScanningScreen") }
    val batchId = navController.previousBackStackEntry
        ?.arguments?.getLong(Screen.ScanBarcode.ARG_BATCH_ID) ?: 0L
    val isStockCount = batchId != 0L
    val topHeightPortrait = maxHeight * 0.75f
    val bottomHeightPortrait = maxHeight * 0.25f
    val startWidthLandScape = maxWidth * 0.7f
    val endWidthLandscape = maxWidth * 0.3f

    // Buffer of last 10 detections
    var lastTenDetections by remember { mutableStateOf<List<Int>>(emptyList()) }

    var filteredPillCount by remember { mutableStateOf(0) }

    var previewWidth by remember { mutableStateOf<Int?>(null) }
    var previewHeight by remember { mutableStateOf<Int?>(null) }

    val showConfirmationDialog = uiState.showDialogForControl
    val showCountMismatchDialog = uiState.showCountMismatchDialog

    // Whenever detected pills update, push into buffer
    LaunchedEffect(uiState.detectedPills) {
        val currentCount = uiState.detectedPills.size
        lastTenDetections = (lastTenDetections + currentCount).takeLast(10)
    }
    LaunchedEffect(uiState.showErrorMessage) {

        uiState.showErrorMessage?.let { message ->
            showToast(context = context, message = message, duration = Toast.LENGTH_SHORT)

            viewModel.clearErrorMessage()
        }
    }

    val stepType by viewModel.currentStep.collectAsState()
    val isSoundEnabled = viewModel.isSoundEnabled.collectAsState().value

    // === Toasts ===
    if (uiState.restrictAdd) {
        UserInterfaceUtils.showToast(context, stringResource(id = R.string.max_count_reached))
        logger.w("Toast: Max count reached")
        viewModel.resetRestrictAdd()
    }

    if (uiState.showNoTransaction) {
        UserInterfaceUtils.showToast(context, stringResource(id = R.string.no_transaction_found))
        logger.w("Toast: No transaction found")
        viewModel.resetNoTransaction()
    }

    if (uiState.showTargetCountDialog) {
        TargetPillsCountDialog(
            onDismiss = {
                viewModel.setTargetCountDialogShown(false)
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Dashboard.route) { inclusive = true }
                }
            },
            onOkay = { count ->
                viewModel.updateTargetCount(count)
                viewModel.setTargetCountDialogShown(false)
            }
        )
    }

    if (uiState.showNotesDialog) {
        AddNoteDialog(
            onDismiss = { viewModel.setNoteDialogShown(false) },
            onSkip = {
                viewModel.onEvent(PillScanningEvent.NoteSkip)
            },
            onSave = { note ->
                viewModel.onEvent(PillScanningEvent.NoteSaved(note))
            },
            viewModel
        )
    }

    // --- Confirm Dialog ---
    if (uiState.showConfirmDialog) {
        val warningText = if (
            countType == CountType.FIXED.toString() &&
            uiState.txnDetailHistory.sumOf { it.count } < uiState.targetCount
        ) {
            stringResource(R.string.confirm_done_desc_fixed)
        } else {
            stringResource(R.string.confirm_done_desc_regular)
        }
        CommonDialog(
            message = warningText,
            title = stringResource(R.string.confirm_done),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { viewModel.onEvent(PillScanningEvent.ConfirmDone) },
            onCancel = { viewModel.onEvent(PillScanningEvent.CancelDone) }
        )
    }

    if (showConfirmationDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_complete_this_step),
            title = stringResource(R.string.confirm_steps_completion),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.moveNextStep()
            },
            onCancel = { viewModel.handleDismissDialog() }
        )
    }

    if (showCountMismatchDialog) {
        CommonDialog(
            message = stringResource(R.string.the_counted_quantity_does_not_match_the_target_count),
            title = stringResource(R.string.count_mismatch),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                viewModel.moveNextStep()
            },
            onCancel = {
                viewModel.resetIdleOverlay()
                viewModel.handleDismissDialog()
            }
        )
    }

    if (uiState.showEndStockCountDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_end_this_count),
            title = stringResource(R.string.confirmation),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                viewModel.moveNextStep()
            },
            onCancel = {
                viewModel.resetIdleOverlay()
                viewModel.handleDismissDialog()
            }
        )
    }

    // === Init & Navigation ===
    LaunchedEffect(Unit) {
        // Reset glove detection state when screen loads
        viewModel.resetGloveDetection()

        viewModel.getDrugInfo()
        viewModel.showTxnInfo(countType)
        viewModel.observeTxnDetailsForTxn(stepType)
        viewModel.navigationEvent.collectLatest { event ->
            when (event) {
                is NavigationEvent.NavigateToDashboard -> {
                    navController.navigate(Screen.Dashboard.route)
                }

                is NavigationEvent.NavigateToBatch -> {
                    navController.navigate(Screen.Batch.createRoute(event.batchId))
                }
            }
        }
    }

    LaunchedEffect(countType) {
        viewModel.setScanType(countType)
    }

    // --- Layout ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.secondaryBackground)
        // DISABLED: Idle timer reset disabled for continuous performance monitoring
        /*
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    viewModel.resetIdleTimer()
                }
            }
        }
        */
    ) {
        // Camera + Info
        SplitResponsive(
            topOrLeft = {
                CameraPreviewSection(
                    viewModel = viewModel,
                    pills = uiState.detectedPills,
                    isCameraPaused = viewModel.cameraPaused.collectAsState().value,
                    onFrame = { imageProxy ->
                        viewModel.onFrameCaptured(imageProxy)
                    },
                    onFilteredCountChanged = { count -> filteredPillCount = count },
                    modifier = Modifier.fillMaxSize(),

                    imageFrameWidth = uiState.imageFrameWidth,
                    imageFrameHeight = uiState.imageFrameHeight,

                    onPreviewSizeKnown = { w, h ->
                        // Store once
                        if (previewWidth == null || previewHeight == null) {
                            previewWidth = w
                            previewHeight = h

                            viewModel.initializeInterpreter(
                                retryCount = 2,
                                viewWidth = w,
                                viewHeight = h
                            )
                        }
                    }
                )
            },
            bottomOrRight = {
                InformationPanelSection(
                    uiState = uiState,
                    viewModel = viewModel,
                    onEvent = viewModel::onEvent,
                    filteredPillCount = filteredPillCount
                )
            },
            landscapeRatio = startWidthLandScape to endWidthLandscape,
            portraitRatio = topHeightPortrait to bottomHeightPortrait
        )

        if (!uiState.showIdleOverlay) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                BackButton(
                    navController = navController,
                    showBox = false,
                    onClick = {
                        if (isStockCount) {
                            viewModel.showEndStockCountDialog()
                        } else {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(0)
                                launchSingleTop = true
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.weight(0.3f))

                StepTitleWithSpeech(
                    stepType = stepType,
                    isSoundOverride = isSoundEnabled,
                    titleResOverride = if (countType == CountType.REGULAR.toString()) R.string.scan_open_pills else null
                )

                Spacer(modifier = Modifier.weight(1f))
            }
        }
        // === Overlay placed last → ensures it is on top ===
        if (uiState.showIdleOverlay) {
            logger.i("Overlay visible")
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.5f))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.counting_paused).uppercase(Locale.ROOT),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        color = AppTheme.extendedColors.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Resume button intentionally disabled for performance monitoring;
                    // the workflow stays paused until the camera resumes externally.
                }
            }
        }
        // Glove-status hand icon is rendered inside CameraPreviewSection's overlay.
    }
}
