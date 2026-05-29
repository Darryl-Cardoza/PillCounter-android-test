package com.rite.pillcounting.feature.pillCountScan.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountHeader
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountUiState
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentBatchRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsLabelRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsList
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedDrugDetailsPhone
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedSummaryRow

/**
 * Phone-landscape variant of the Batch Stock Count panel — the horizontal analog
 * of the portrait bottom sheet:
 *  - Collapsed (normal): only the SCANNED NDC DETAILS / counter card (active) or
 *    the empty placeholder + SCANNED SUMMARY shows, docked on the right.
 *  - Expanded (slid outward): a RECENT COUNTS card is revealed to the LEFT of the
 *    details card.
 *
 * The host (phone-landscape shell) owns the slide gesture and sizes this panel's
 * width; this composable just lays out the two cards. [recentVisible] controls
 * whether the recent-counts card is shown (true once expanded).
 *
 * Card content reuses [ScannedDrugDetailsPhone] / [ScannedSummaryRow] /
 * [RecentCountsList] verbatim so styling matches the other variants.
 */
@Composable
fun BatchStockCountPhoneLandscape(
    state: BatchStockCountUiState,
    recentVisible: Boolean,
    detailsCardWidth: Dp,
    onScanPills: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClear: () -> Unit,
    onAdd: () -> Unit,
    onEndCount: () -> Unit,
    endCountEnabled: Boolean,
    onRowTapped: (RecentBatchRow) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Grey sheet surface (rounded left edge — set by the host clip). Row so the
    // recent card sits to the LEFT of the details card; recent is only laid out
    // when expanded so the collapsed peek is exactly the details card width.
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(0xFFF2F2F2))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (recentVisible) {
            // Recent counts card — fills the slack on the left when expanded.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                val label = if (state.activeNdc != null) {
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
        }

        // Details / summary card — fixed width = the collapsed peek width, docked
        // on the right.
        Column(
            modifier = Modifier
                .width(detailsCardWidth)
                .fillMaxHeight(),
        ) {
            BatchStockCountHeader(onScanPills = onScanPills)
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (state.activeNdc != null) {
                    ScannedDrugDetailsPhone(
                        active = state.activeNdc,
                        onIncrement = onIncrement,
                        onDecrement = onDecrement,
                        onClear = onClear,
                        onAdd = onAdd,
                    )
                } else {
                    EmptyScannedDetailsLandscape(
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
private fun EmptyScannedDetailsLandscape(
    totalNdcs: Int,
    totalPills: Int,
    onEndCount: () -> Unit,
    endCountEnabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
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
            endCountEnabled = endCountEnabled,
        )
    }
}
