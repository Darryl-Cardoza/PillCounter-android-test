package com.rite.pillcounting.feature.inventoryFlow.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.feature.inventoryFlow.domain.model.BatchStockCountUiState
import com.rite.pillcounting.feature.inventoryFlow.domain.model.EditBatchRow
import com.rite.pillcounting.feature.inventoryFlow.domain.model.EditDrugDetails
import com.rite.pillcounting.feature.inventoryFlow.domain.model.RecentBatchRow
import com.rite.pillcounting.feature.inventoryFlow.presentation.compose.BatchStockCountHeader
import com.rite.pillcounting.feature.inventoryFlow.presentation.compose.EditDetailsContent
import com.rite.pillcounting.feature.inventoryFlow.presentation.compose.RecentCountsLabelRow
import com.rite.pillcounting.feature.inventoryFlow.presentation.compose.RecentCountsList
import com.rite.pillcounting.feature.inventoryFlow.presentation.compose.ScannedDrugCard
import com.rite.pillcounting.feature.inventoryFlow.presentation.compose.ScannedSummaryCard
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Tablet landscape variant of the redesigned Batch Stock Count panel.
 *
 * Persistent right-side panel (NOT a draggable bottom sheet). Caller is
 * expected to place this as a sibling of the camera preview inside a Row so
 * the scanner shrinks to give it room rather than being overlaid.
 *
 * Two stacked sections in the bottom overlay card:
 *  - Top: scanned-drug counter card when [BatchStockCountUiState.activeNdc]
 *    is non-null, otherwise the "scan a new bottle" placeholder.
 *  - Bottom (always visible): scanned-summary totals + END COUNT button.
 */
@Composable
fun BatchStockCountTabletLandscape(
    state: BatchStockCountUiState,
    onScanPills: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClear: () -> Unit,
    onAdd: () -> Unit,
    onEndCount: () -> Unit,
    endCountEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onRowTapped: (RecentBatchRow) -> Unit = {},
    onEdit: () -> Unit = {},
    editDetails: EditDrugDetails? = null,
    onEditDismiss: () -> Unit = {},
    onEditSave: (sealed: List<EditBatchRow>, open: List<EditBatchRow>) -> Unit = { _, _ -> },
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val overlayHeightDp = with(density) { overlayHeightPx.toDp() }

    // Edit mode: the header morphs to "Edit Details" + X, the recent list stays
    // visible, and the bottom card shows the editable batch rows in place of the
    // scanned-drug counter.
    if (editDetails != null) {
        BatchStockCountEditingLandscape(
            details = editDetails,
            onScanPills = onScanPills,
            onEditDismiss = onEditDismiss,
            onEditSave = onEditSave,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxHeight()) {
        // Top section: header + recent counts list. Fills the full height behind
        // the bottom overlay; the list reserves bottom padding equal to the
        // overlay height so the last row is never hidden.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .fillMaxWidth()
                .background(AppTheme.extendedColors.primaryBackground),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                BatchStockCountHeader(onScanPills = onScanPills)
                Spacer(modifier = Modifier.height(16.dp))
                val label = if (state.recentCounts.isNotEmpty()) {
                    stringResource(R.string.batch_stock_count_recent_with_count, state.totalNdcs)
                } else {
                    stringResource(R.string.batch_stock_count_recent_summary)
                }
                RecentCountsLabelRow(label = label)
                Spacer(modifier = Modifier.height(8.dp))
                RecentCountsList(
                    rows = state.recentCounts,
                    onRowTapped = onRowTapped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = overlayHeightDp),
                )
            }
        }

        // Bottom overlay: always shows BOTH the drug-details section (active card
        // or placeholder) AND the scanned-summary row. Height is measured via
        // onSizeChanged so the recent list above can reserve the exact space.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { overlayHeightPx = it.height },
        ) {
            // Card with rounded top corners, flat bottom (flush with screen edge).
            // The shadow follows the rounded top corners so it reads as floating
            // above the list (rather than a straight horizontal line).
            val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = 0.dp,
                bottomEnd = 0.dp,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 10.dp, shape = cardShape, clip = false)
                    .clip(cardShape)
                    .background(AppTheme.extendedColors.secondaryBackground)
            ) {
                when {
                    // Drug details: active drug counter.
                    state.activeNdc != null -> {
                        ScannedDrugCard(
                            active = state.activeNdc,
                            onIncrement = onIncrement,
                            onDecrement = onDecrement,
                            onClear = onClear,
                            onAdd = onAdd,
                            onEdit = onEdit,
                        )
                    }
                    // "Scan a new bottle" placeholder + summary.
                    else -> {
                        EmptyScannedDetailsTabletLandscape()

                        HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

                        ScannedSummaryCard(
                            totalNdcs = state.totalNdcs,
                            totalPills = state.totalPills,
                            onEndCount = onEndCount,
                            endCountEnabled = endCountEnabled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Edit mode for the landscape panel. The top header morphs to "Edit Details" with
 * an X close (replacing the SCAN PILLS pill); the edit card stretches to fill the
 * entire area below the header, hosting the editable batch rows (sealed bottles /
 * open pills) in place of the scanned-drug counter — its own title bar is
 * suppressed since the panel header already shows it.
 */
@Composable
private fun BatchStockCountEditingLandscape(
    details: EditDrugDetails,
    onScanPills: () -> Unit,
    onEditDismiss: () -> Unit,
    onEditSave: (sealed: List<EditBatchRow>, open: List<EditBatchRow>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(AppTheme.extendedColors.primaryBackground),
    ) {
        // Header pinned at the top.
        BatchStockCountHeader(
            onScanPills = onScanPills,
            editing = true,
            onClose = onEditDismiss,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        )

        // Edit card stretches to fill the rest below the header: rounded top, flush
        // bottom — same surface as the scanned-drug card it replaces. The shadow
        // follows the rounded top corners so it reads as floating below the header.
        val editCardShape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .shadow(elevation = 10.dp, shape = editCardShape, clip = false)
                .clip(editCardShape)
                .background(AppTheme.extendedColors.secondaryBackground)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            EditDetailsContent(
                details = details,
                onDismiss = onEditDismiss,
                onSave = onEditSave,
                showTitle = false,
            )
        }
    }
}

@Composable
private fun EmptyScannedDetailsTabletLandscape() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = responsiveSp(8.sp),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.fixed_count_inner),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(UserInterfaceUtils.responsiveDp(56.dp)),
            )
            Text(
                text = stringResource(R.string.batch_stock_count_scan_new_bottle),
                color = AppTheme.extendedColors.textColor.copy(alpha = 0.9f),
                fontSize = responsiveSp(7.sp),
            )
        }
    }
}
