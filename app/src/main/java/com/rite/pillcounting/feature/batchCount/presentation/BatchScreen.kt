package com.rite.pillcounting.feature.batchCount.presentation

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.feature.batchCount.domain.model.BatchDrugGroup
import com.rite.pillcounting.feature.batchCount.presentation.compose.BatchNdcCard
import com.rite.pillcounting.feature.batchCount.presentation.viewmodel.BatchViewModel
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.AddNoteDialog
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.AppTheme.dimens


@Composable
fun BatchScreen(
    navController: NavController,
    showActions: Boolean = true,
    onBackClick: () -> Unit = { navController.popBackStack() },
    viewModel: BatchViewModel = hiltViewModel()
) {
    val drugGroups by viewModel.drugGroups.collectAsStateWithLifecycle()
    val displayBatchId by viewModel.displayBatchId.collectAsStateWithLifecycle()
    val isBatchCompleted by viewModel.isBatchCompleted.collectAsStateWithLifecycle()

    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<BatchDrugGroup>()) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showEndBatchDialog by remember { mutableStateOf(false) }
    var pendingNote by remember { mutableStateOf<String?>(null) }
    var expandedDrugId by remember { mutableStateOf<Long?>(null) }

    val hasQty = drugGroups.any { it.totalCount > 0 }

    val isAllSelected = drugGroups.isNotEmpty() && selectedItems.size == drugGroups.size
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
    ) {

        // ── Top bar ──────────────────────────────────────────────────────────
        HeadlineBar(
            navController = navController,
            title = if (displayBatchId != 0.toLong()) "Batch #$displayBatchId" else "Batch",
            searchQuery = "",
            showSearch = false,
            isMultiSelectMode = isMultiSelectMode,
            isAllSelected = isAllSelected,
            hasSelection = selectedItems.isNotEmpty(),
            showDelete = false,
            onSearchClick = {},
            onSearchChange = {},
            onDeleteClick = { isMultiSelectMode = true },
            onCancelClick = {
                isMultiSelectMode = false
                selectedItems = emptySet()
            },
            onConfirmDelete = {},
            onSelectAll = {
                selectedItems = if (isAllSelected) emptySet() else drugGroups.toSet()
            },
            showSearchIcon = false,
            showPdfIcon = true,
            onPdfClick = { /* TODO: PDF export */ },
            onBackClick = onBackClick
        )

        Spacer(Modifier.height(8.dp))

        // ── Empty state ───────────────────────────────────────────────────────
        if (drugGroups.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_items_in_that_batch_yet),
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }
        } else {
            // ── Drug group list ───────────────────────────────────────────────
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(drugGroups, key = { it.drugId ?: 0L }) { group ->
                    BatchNdcCard(
                        group = group,
                        isExpanded = expandedDrugId == group.drugId,
                        onExpandToggle = {
                            expandedDrugId =
                                if (expandedDrugId == group.drugId) null else group.drugId
                        }
                    )
                }
            }
        }

        // ── Bottom action bar ────────────────────────────────────────────────
        if (!isBatchCompleted) {
            BottomActionBar(
                onEndBatch = { showNoteDialog = true },
                onAdd = {
                    navController.navigate(
                        Screen.ScanBarcode.createRoute(
                            scanType = CountType.REGULAR.toString(),
                            txnScanType = ScanType.STOCK_COUNT,
                            batchId = displayBatchId
                        )
                    )
                }
            )
        }
    }

    // ── Note dialog (shown before end-batch confirmation) ────────────────────
    if (showNoteDialog) {
        AddNoteDialog(
            onDismiss = { showNoteDialog = false },
            onSkip = {
                showNoteDialog = false
                pendingNote = null
                showEndBatchDialog = true
            },
            onSave = { note ->
                showNoteDialog = false
                pendingNote = note
                showEndBatchDialog = true
            },
            showSkip = true
        )
    }

    // ── End-batch confirmation dialog ────────────────────────────────────────
    if (showEndBatchDialog) {
        val dialogTitle = if (!hasQty) {
            stringResource(R.string.no_qty_added_message)
        } else {
            stringResource(R.string.end_this_batch_it_will_be_mark_as_completed)
        }
        CommonDialog(
            message = dialogTitle,
            confirmText = stringResource(R.string.end_batch),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.endBatch(pendingNote)
                showEndBatchDialog = false
                pendingNote = null
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Dashboard.route) { inclusive = false }
                }
            },
            onCancel = {
                showEndBatchDialog = false
                pendingNote = null
            }
        )
    }
}

// ── BottomActionBar ───────────────────────────────────────────────────────────
@Composable
private fun BottomActionBar(
    onEndBatch: () -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.extendedColors.primaryBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        HollowButton(
            text = stringResource(R.string.end_count).uppercase(),
            onClick = onEndBatch,
            color = MaterialTheme.colorScheme.primary,
            fixedWidth = false,
            modifier = Modifier.width(dimens.dialogButtonWidth)
        )
        Spacer(modifier = Modifier.width(16.dp))
        ActionButtonPrimary(
            text = stringResource(R.string.plus_add).uppercase(),
            onClick = onAdd,
            fixedWidth = false,
            modifier = Modifier.width(dimens.dialogButtonWidth)
        )
    }
}