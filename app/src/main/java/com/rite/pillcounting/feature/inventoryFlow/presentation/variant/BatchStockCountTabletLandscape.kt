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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils
import com.rite.pillcounting.feature.inventoryFlow.domain.model.BatchStockCountUiState
import com.rite.pillcounting.feature.inventoryFlow.domain.model.RecentBatchRow
import com.rite.pillcounting.feature.inventoryFlow.presentation.compose.BatchStockCountHeader
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
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val overlayHeightDp = with(density) { overlayHeightPx.toDp() }

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
            // Soft upward shadow so the card reads as floating above the list.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f))
                        )
                    )
            )
            // Card with rounded top corners, flat bottom (flush with screen edge).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(
                        androidx.compose.foundation.shape.RoundedCornerShape(
                            topStart = 24.dp,
                            topEnd = 24.dp,
                            bottomStart = 0.dp,
                            bottomEnd = 0.dp,
                        )
                    )
                    .background(AppTheme.extendedColors.secondaryBackground)
            ) {
                // Drug details: active drug counter or the "scan a new bottle" placeholder.
                if (state.activeNdc != null) {
                    ScannedDrugCard(
                        active = state.activeNdc,
                        onIncrement = onIncrement,
                        onDecrement = onDecrement,
                        onClear = onClear,
                        onAdd = onAdd,
                    )
                } else {
                    EmptyScannedDetailsTabletLandscape()

                    HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

                    // Summary only shown when no drug is actively being scanned.
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
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
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
                fontSize = 14.sp,
            )
        }
    }
}