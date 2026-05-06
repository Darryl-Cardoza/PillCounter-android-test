package com.rite.pillcounting.feature.batchCount.presentation

import Screen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.feature.batchCount.domain.model.BatchDrugGroup
import com.rite.pillcounting.feature.batchCount.domain.model.BatchLotEntry
import com.rite.pillcounting.feature.batchCount.presentation.viewmodel.BatchViewModel
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.AddNoteDialog
import com.rite.pillcounting.ui.theme.AppTheme


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
                    BatchGroupCard(
                        group = group,
                        isExpanded = expandedDrugId == group.drugId,
                        onExpandToggle = {
                            expandedDrugId =
                                if (expandedDrugId == group.drugId) null else group.drugId
                        },
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = selectedItems.contains(group),
                        onSelect = {
                            selectedItems =
                                if (selectedItems.contains(group)) selectedItems - group
                                else selectedItems + group
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

// ── BatchGroupCard ────────────────────────────────────────────────────────────
@Composable
private fun BatchGroupCard(
    group: BatchDrugGroup,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (isMultiSelectMode) onSelect() else onExpandToggle()
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = AppTheme.extendedColors.textColor.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 8.dp)
                        .align(Alignment.CenterVertically)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.drugName,
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = group.ndc,
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = group.totalCount.toString(),
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown
                    else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Expanded detail
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 0.dp)
            ) {
                HorizontalDivider(
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                    thickness = 0.5.dp
                )
                Spacer(Modifier.height(8.dp))

                LotSection(
                    label = stringResource(R.string.sealed_bottles),
                    total = group.sealedTotal,
                    lots = group.sealedLots
                )

                Spacer(Modifier.height(8.dp))

                LotSection(
                    label = stringResource(R.string.opend_bottles),
                    total = group.openedTotal,
                    lots = group.openedLots
                )

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ── LotSection ────────────────────────────────────────────────────────────────
@Composable
private fun LotSection(label: String, total: Int, lots: List<BatchLotEntry>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = total.toString(),
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }

    if (lots.isEmpty()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = "—",
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 8.dp),
            fontWeight = FontWeight.Normal
        )
    } else {
        Spacer(Modifier.height(4.dp))
        lots.forEach { LotRow(it) }
    }
}

// ── LotRow ────────────────────────────────────────────────────────────────────
@Composable
private fun LotRow(lot: BatchLotEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Lot ${lot.lotNo ?: "—"}",
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = lot.expiry ?: "—",
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.weight(0.1f))
        Text(
            text = lot.count.toString(),
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp
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
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onEndBatch,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        ) {
            Text(
                stringResource(R.string.end_count),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Button(
            onClick = onAdd,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(
                stringResource(R.string.plus_add),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.extendedColors.textColor
            )
        }
    }
}