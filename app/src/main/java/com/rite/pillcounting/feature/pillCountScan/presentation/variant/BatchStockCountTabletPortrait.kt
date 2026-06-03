package com.rite.pillcounting.feature.pillCountScan.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.BatchStockCountUiState
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.RecentBatchRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountHeader
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsLabelRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsList
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedDrugDetailsPortrait
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedSummaryRow
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Tablet portrait variant of the redesigned Batch Stock Count panel.
 *
 * Same logic / data flow as [BatchStockCountTabletLandscape] — only the
 * placement of the UI components changes. Caller places this as a fixed-height
 * bottom sheet below the camera preview (a Column where the camera takes the
 * remaining space). This composable is layout-only; all behavior is delivered
 * through the callbacks, identical to the landscape variant.
 *
 * Layout: a single white card with
 *  - Header row: title + SCAN PILLS button.
 *  - A side-by-side Row:
 *      - Left: "RECENT COUNTS" label + the recent-counts list (scrolls).
 *      - Right: "SCANNED NDC DETAILS" card. When [BatchStockCountUiState.activeNdc]
 *        is non-null it shows the scanned-drug detail + counter + CLEAR/ADD;
 *        otherwise it shows the "scan a new bottle" placeholder with the
 *        scanned-summary totals + END COUNT pinned to the bottom.
 */
@Composable
fun BatchStockCountTabletPortrait(
    state: BatchStockCountUiState,
    onScanPills: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClear: () -> Unit,
    onAdd: () -> Unit,
    onEndCount: () -> Unit,
    modifier: Modifier = Modifier,
    onRowTapped: (RecentBatchRow) -> Unit = {},
) {
    // Grey sheet surface (matches Figma + the landscape variant). The two
    // inner sections (Recent Counts, Scanned NDC Details) are white cards that
    // sit on this grey. Top corners rounded so it reads as a sheet sliding up
    // over the camera; bottom flush against the screen edge.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = 24.dp,
                    topEnd = 24.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                )
            )
            .background(AppTheme.extendedColors.primaryBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            BatchStockCountHeader(onScanPills = onScanPills)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Left: recent counts — NO white card wrapper (per Figma). The
                // section header + the individual row-cards sit directly on the
                // grey sheet; each row supplies its own white card + shadow.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
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
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }

                // Right: scanned NDC details / summary — white card (soft shadow)
                // on the grey sheet. Fills the row height; the active-state content
                // anchors to the top and the empty-state pins END COUNT to the
                // bottom.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .shadow(CARD_ELEVATION, RoundedCornerShape(13.dp))
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppTheme.extendedColors.secondaryBackground)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (state.activeNdc != null) {
                        ScannedDrugDetailsPortrait(
                            active = state.activeNdc,
                            onIncrement = onIncrement,
                            onDecrement = onDecrement,
                            onClear = onClear,
                            onAdd = onAdd,
                        )
                    } else {
                        EmptyScannedDetailsPortrait(
                            totalNdcs = state.totalNdcs,
                            totalPills = state.totalPills,
                            onEndCount = onEndCount,
                        )
                    }
                }
            }
        }
    }
}

/** Soft elevation for the white cards sitting on the grey sheet. */
private val CARD_ELEVATION = 3.dp

/**
 * Empty-state content for the portrait right card: a centered "scan a new
 * bottle" placeholder filling the available space, with the scanned-summary
 * totals + END COUNT pinned to the bottom.
 */
@Composable
private fun EmptyScannedDetailsPortrait(
    totalNdcs: Int,
    totalPills: Int,
    onEndCount: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // Centered placeholder fills the slack between the header and the summary.
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AddBox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.batch_stock_count_scan_new_bottle),
                color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
        ScannedSummaryRow(
            totalNdcs = totalNdcs,
            totalPills = totalPills,
            onEndCount = onEndCount,
        )
    }
}

