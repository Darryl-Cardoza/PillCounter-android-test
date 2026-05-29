package com.rite.pillcounting.feature.pillCountScan.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountHeader
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountUiState
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentBatchRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsLabelRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsList
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedDrugDetailsPortrait
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedSummaryRow

/**
 * Phone-portrait variant of the redesigned Batch Stock Count panel.
 *
 * Same data + callbacks as the tablet variants — only the layout differs to fit
 * a narrow phone in a draggable bottom sheet. The caller (the phone shell) hosts
 * this inside a BottomSheetScaffold:
 *  - Collapsed (peek): header + the SCANNED NDC DETAILS card/counter (active) or
 *    the "scan a new bottle" placeholder + SCANNED SUMMARY (empty).
 *  - Expanded (pull up): the RECENT COUNTS list is revealed below the card.
 *
 * Single column throughout (the tablet side-by-side Row is too cramped on a
 * phone). The card content reuses [ScannedDrugDetailsPortrait] / [ScannedSummaryRow]
 * verbatim so styling stays identical to tablet portrait.
 */
@Composable
fun BatchStockCountPhonePortrait(
    state: BatchStockCountUiState,
    onScanPills: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClear: () -> Unit,
    onAdd: () -> Unit,
    onEndCount: () -> Unit,
    onRowTapped: (RecentBatchRow) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // NO own background / rounded surface here — the hosting BottomSheetScaffold
    // provides the grey sheet container, rounded top corners, and the drag handle.
    // Painting them again here produced a visible "double sheet" (curved scaffold
    // sheet behind + flat-cornered grey on top). We only lay out content + padding.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        BatchStockCountHeader(onScanPills = onScanPills)
        Spacer(modifier = Modifier.height(14.dp))

        // White card: scanned NDC details + counter (active) or empty placeholder
        // + summary. This is the always-visible (collapsed) region.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(Color.White)
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
                EmptyScannedDetailsPhone(
                    totalNdcs = state.totalNdcs,
                    totalPills = state.totalPills,
                    onEndCount = onEndCount,
                )
            }
        }

        // Recent counts — revealed in the expanded sheet region. The header label
        // + search icon sit directly on the grey sheet; each row is its own white
        // card (RecentCountsList supplies the row cards), matching the tablet.
        Spacer(modifier = Modifier.height(16.dp))
        val label = if (state.activeNdc != null) {
            stringResource(R.string.batch_stock_count_recent_with_count, state.totalNdcs)
        } else {
            stringResource(R.string.batch_stock_count_recent_summary)
        }
        RecentCountsLabelRow(label = label)
        Spacer(modifier = Modifier.height(8.dp))
        // weight(1f) so the LazyColumn gets a bounded height inside the sheet and
        // scrolls internally — a LazyColumn with unbounded height inside the
        // sheet's Column would crash ("measured with infinity"). The list is only
        // meaningfully visible once the sheet is dragged up past the peek height.
        RecentCountsList(
            rows = state.recentCounts,
            onRowTapped = onRowTapped,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * Empty-state content for the phone card: a centered "scan a new bottle"
 * placeholder, with the scanned-summary totals + END COUNT below. Mirrors the
 * tablet portrait empty state but without the fill-height weighting (the phone
 * card wraps its content inside the sheet).
 */
@Composable
private fun EmptyScannedDetailsPhone(
    totalNdcs: Int,
    totalPills: Int,
    onEndCount: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
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
                color = Color(0xFF888888),
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
