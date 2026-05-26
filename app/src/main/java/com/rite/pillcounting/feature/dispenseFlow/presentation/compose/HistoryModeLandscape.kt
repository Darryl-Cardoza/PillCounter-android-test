package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.FullScreenImageDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.FilledButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSpForPillCountingHistoryScreen
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.dispenseFlow.domain.model.TxnDetail
import com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun HistoryModeLandscape(
    navController: NavController,
    scanType: String,
    targetCount: Int,
    totalCount: Int,
    txnHistory: List<TxnDetail>,
    onDeleteTxn: (Long) -> Unit,
    viewModel: PillScanningViewModel,
    drugName: String,
    onBack: () -> Unit
) {
    val activeHistory = remember(txnHistory) { txnHistory.sortedBy { it.createdAt } }
    val stepType by viewModel.currentStep.collectAsState()
    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var selectedImagePath by remember { mutableStateOf<String?>(null) }
    val isAllSelected = selectedIds.size == activeHistory.size && activeHistory.isNotEmpty()

    val listState = rememberLazyGridState()

    LaunchedEffect(activeHistory.size) {
        if (activeHistory.isNotEmpty()) listState.animateScrollToItem(activeHistory.lastIndex)
    }

    val handleBack: () -> Unit = {
        if (isDeleteMode) {
            isDeleteMode = false
            selectedIds = emptySet()
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.extendedColors.primaryBackground)
        ) {
            HeadlineBar(
                navController = navController,
                title = stringResource(R.string.total_count),
                searchQuery = "",
                showSearch = false,
                isMultiSelectMode = isDeleteMode,
                isAllSelected = isAllSelected,
                hasSelection = selectedIds.isNotEmpty(),
                showDelete = true,
                showSearchIcon = false,
                onSearchClick = {},
                onSearchChange = {},
                onDeleteClick = { isDeleteMode = true },
                onCancelClick = { handleBack() },
                onConfirmDelete = {},
                onSelectAll = {
                    selectedIds = if (isAllSelected) emptySet()
                    else activeHistory.map { it.txnDetailId }.toSet()
                },
                onBackClick = { handleBack() }
            )

            // ── Drug name + count (hidden in delete mode) ──
            if (!isDeleteMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = drugName,
                        color = AppTheme.extendedColors.textColor,
                        fontSize = responsiveSpForPillCountingHistoryScreen(18.sp),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (scanType == CountType.FIXED.toString() && stepType != StepState.CONTAINER_INITIATE)
                            "$totalCount/$targetCount"
                        else
                            "$totalCount",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = responsiveSpForPillCountingHistoryScreen(18.sp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // ── Grid ──
            if (activeHistory.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_pill_added_yet),
                        color = AppTheme.extendedColors.textColor,
                        fontSize = responsiveSpForPillCountingHistoryScreen(16.sp),
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (isTablet) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(
                        items = activeHistory,
                        key = { _, item -> item.txnDetailId }
                    ) { index, item ->
                        TxnHistoryCard(
                            txnDetail = item,
                            index = index + 1,
                            isDeleteMode = isDeleteMode,
                            isSelected = item.txnDetailId in selectedIds,
                            onToggleSelect = {
                                selectedIds = if (item.txnDetailId in selectedIds)
                                    selectedIds - item.txnDetailId
                                else
                                    selectedIds + item.txnDetailId
                            },
                            onDelete = onDeleteTxn,
                            onImageClick = { path -> selectedImagePath = path },
                            cardModifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.80f)
                        )
                    }
                }
            } else {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(1),
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    itemsIndexed(
                        items = activeHistory,
                        key = { _, item -> item.txnDetailId }
                    ) { index, item ->
                        TxnHistoryCard(
                            txnDetail = item,
                            index = index + 1,
                            isDeleteMode = isDeleteMode,
                            isSelected = item.txnDetailId in selectedIds,
                            onToggleSelect = {
                                selectedIds = if (item.txnDetailId in selectedIds)
                                    selectedIds - item.txnDetailId
                                else
                                    selectedIds + item.txnDetailId
                            },
                            onDelete = onDeleteTxn,
                            onImageClick = { path -> selectedImagePath = path },
                            cardModifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(0.80f)
                        )
                    }
                }
            }

            // ── Delete-mode bottom bar ──
            if (isDeleteMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HollowButton(
                        text = stringResource(R.string.cancel).uppercase(),
                        onClick = {
                            isDeleteMode = false
                            selectedIds = emptySet()
                        },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(responsiveDp(120.dp))
                    )
                    FilledButton(
                        text = stringResource(R.string.delete).uppercase(),
                        onClick = {
                            if (selectedIds.isNotEmpty()) showDeleteConfirmDialog = true
                        },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(responsiveDp(120.dp))
                    )
                }
            }
        }

        selectedImagePath?.let { path ->
            FullScreenImageDialog(
                imagePath = path,
                onDismiss = { selectedImagePath = null },
                modifier = Modifier.zIndex(1f)
            )
        }
    }

    if (showDeleteConfirmDialog) {
        CommonDialog(
            title = stringResource(R.string.confirm_delete_title),
            message = stringResource(R.string.delete_selected_items_text),
            confirmText = stringResource(R.string.delete),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                selectedIds.forEach { id -> onDeleteTxn(id) }
                showDeleteConfirmDialog = false
                isDeleteMode = false
                selectedIds = emptySet()
            },
            onCancel = { showDeleteConfirmDialog = false }
        )
    }
}