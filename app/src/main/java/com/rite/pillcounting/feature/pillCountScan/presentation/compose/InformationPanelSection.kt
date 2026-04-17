package com.rite.pillcounting.feature.pillCountScan.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.feature.pillCountScan.domain.data.PillScanningEvent
import com.rite.pillcounting.feature.pillCountScan.domain.model.PillScanningUiState
import com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Displays the right-hand information panel in the pill counting screen.
 *
 * This section includes:
 * - The current pill count title
 * - Drug details (name, batch, total, etc.)
 * - The current count display with an option to add transaction details
 * - A horizontally scrolling list of recent transaction history chips
 * - Action buttons for rescan, pause, and completion
 *
 * It is a key UI component within the pill scanning workflow,
 * designed for tablet/kiosk layouts with responsive scaling.
 *
 * @param uiState The current state of the pill scanning UI.
 * @param viewModel The ViewModel managing scan state and user interactions.
 * @param onEvent Callback to send user interaction events to the ViewModel.
 * @param filteredPillCount The filtered pill count detected in the latest scan.
 */
@Composable
fun InformationPanelSection(
    uiState: PillScanningUiState,
    viewModel: PillScanningViewModel,
    onEvent: (PillScanningEvent) -> Unit,
    filteredPillCount: Int
) {
    val stepType by viewModel.currentStep.collectAsState()
    val totalCount = if (uiState.scanType == CountType.REGULAR.toString()) {
        uiState.stockCountSessionTotal
    } else {
        uiState.txnDetailHistory.sumOf { it.count }
    }
    var showHistory by remember { mutableStateOf(false) }
    val txnHistory = uiState.txnDetailHistory
    val onToggleHistory = { showHistory = !showHistory }
    val drugName = uiState.drugName
    val targetCount = uiState.targetCount
    val scanType = uiState.scanType
    val onReset = { onEvent(PillScanningEvent.AllTransactionDetailsDeleted(stepType)) }
    val onAdd = { onEvent(PillScanningEvent.AddTransactionDetailClicked(filteredPillCount, stepType)) }
    val onDone = { onEvent(PillScanningEvent.FinalDone(stepType=stepType,totalCount)) }

    val onDeleteTxn: (Long) -> Unit = { id ->
        onEvent(PillScanningEvent.TransactionDetailDeleted(id))
    }
    //CodeReview is different composable required as we are already in one dedicated composable
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.secondaryBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {

        if (stepType != StepState.VIAL) {

            if (isLandscape) {
                // ---------------- Row 1: Reset (L) + History/Focus (R) ----------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (txnHistory.isEmpty()) {
                            showDeleteConfirmationDialog = false
                            showToast(context, R.string.cannot_reset_no_pills_detected)
                        } else {
                            showDeleteConfirmationDialog = true
                        }
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.delete),
                            contentDescription = "Reset",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(onClick = onToggleHistory) {
                        Icon(
                            painter = painterResource(
                                id = if (showHistory) R.drawable.scanning_foucs else R.drawable.history
                            ),
                            contentDescription = if (showHistory) "Focus" else "History",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(30.dp)
                        )

                    }

                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (txnHistory.isEmpty()) {
                            showDeleteConfirmationDialog = false
                            showToast(context, R.string.cannot_reset_no_pills_detected)
                        } else {
                            showDeleteConfirmationDialog = true
                        }
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.delete),
                            contentDescription = "Reset",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(30.dp)

                        )
                    }

                    Text(
                        text = drugName,
                        color = AppTheme.extendedColors.textColor,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    IconButton(onClick = onToggleHistory) {
                        Icon(
                            painter = painterResource(
                                id = if (showHistory) R.drawable.scanning_foucs else R.drawable.history
                            ),
                            contentDescription = if (showHistory) "Focus" else "History",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(30.dp)
                        )
                    }

                }

            }
        }


        // ---------- Middle: either "Count mode" OR "History mode" ----------
        if (!showHistory) {
            if (isLandscape) {
                CountModeLandscape(
                    totalCount = totalCount,
                    targetCount = targetCount,
                    scanType = scanType,
                    detectedCount = filteredPillCount,
                    onAdd = onAdd,
                    onDone = onDone,
                    viewModel = viewModel,
                    drugName = drugName
                )
            } else {
                CountModePortrait(
                    totalCount = totalCount,
                    targetCount = targetCount,
                    scanType = scanType,
                    detectedCount = filteredPillCount,
                    onAdd = onAdd,
                    onDone = onDone,
                    viewModel = viewModel
                )
            }
        } else {
            if (isLandscape) {
                HistoryModeLandscape(
                    scanType = scanType,
                    targetCount = targetCount,
                    totalCount = totalCount,
                    txnHistory = txnHistory,
                    onDeleteTxn = onDeleteTxn,
                    drugName = drugName,
                    viewModel = viewModel
                )
            } else {
                HistoryModePortrait(
                    scanType = scanType,
                    targetCount = targetCount,
                    totalCount = totalCount,
                    txnHistory = txnHistory,
                    onDeleteTxn = onDeleteTxn,
                    viewModel = viewModel
                )
            }
        }
    }
    if (showDeleteConfirmationDialog) {
        CommonDialog(
            message = stringResource(R.string.confirm_delete_message_on_reset_click),
            title = stringResource(R.string.confirm_delete_title),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                showDeleteConfirmationDialog = false
                onReset()
            },
            onCancel = { showDeleteConfirmationDialog = false }
        )
    }

}

//    BottomPillPanel(
//        drugName = uiState.drugName,
//        totalCount = totalCount,
//        targetCount = uiState.targetCount,
//        scanType = uiState.scanType,
//        detectedCount = filteredPillCount,
//        showHistory = showListOfTxnDetails,
//        txnHistory = uiState.txnDetailHistory,
//        onToggleHistory = { showListOfTxnDetails = !showListOfTxnDetails },
//        onReset = {  onEvent(PillScanningEvent.AllTransactionDetailsDeleted) },
//        onAdd = { onEvent(PillScanningEvent.AddTransactionDetailClicked(filteredPillCount)) },
//        onDone = { onEvent(PillScanningEvent.DoneClicked) },
//        onDeleteTxn = { id -> onEvent(PillScanningEvent.TransactionDetailDeleted(id)) },
//        viewModel = viewModel
//    )
