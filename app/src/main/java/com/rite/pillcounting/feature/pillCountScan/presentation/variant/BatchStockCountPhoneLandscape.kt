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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountHeader
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.BatchStockCountUiState
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.RecentBatchRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsLabelRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsList
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedDrugDetailsPhone
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedSummaryRow
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils
import com.rite.pillcounting.ui.theme.AppTheme

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
    modifier: Modifier = Modifier,
    onRowTapped: (RecentBatchRow) -> Unit = {},
) {
    // Grey sheet surface. The DETAILS card is first (left) at a fixed width; the
    // RECENT card fills the slack on the RIGHT when expanded. Because the host
    // docks this panel at the right edge and grows it leftward, the details card
    // visually slides OUTWARD (left) as the panel widens while the recent list
    // appears on the right — i.e. the sheet "expands" like the portrait sheet.
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(AppTheme.extendedColors.primaryBackground)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Details / summary card — fixed width = the collapsed peek width.
        Column(
            modifier = Modifier
                .width(detailsCardWidth)
                .fillMaxHeight(),
        ) {
            BatchStockCountHeader(onScanPills = onScanPills)
            Spacer(modifier = Modifier.height(14.dp))
            // Card fills the remaining height (weight) so the summary state can
            // use the full card — placeholder centered, SCANNED SUMMARY pinned to
            // the bottom — instead of bunching at the top with dead space below.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(AppTheme.extendedColors.secondaryBackground)
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

        if (recentVisible) {
            // Recent counts card — fills the slack on the RIGHT when expanded.
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
    // fillMaxSize so the content uses the whole (weighted) card height: header at
    // top, placeholder centered in the slack (weight), SCANNED SUMMARY pinned to
    // the bottom — no dead space below the summary.
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.fixed_count_inner),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(UserInterfaceUtils.responsiveDp(75.dp)),
            )
            Text(
                text = stringResource(R.string.batch_stock_count_scan_new_bottle),
                color = AppTheme.extendedColors.textColor.copy(alpha = 0.9f),
                fontSize = 14.sp,
            )
        }
        HorizontalDivider(
            color = AppTheme.extendedColors.primaryBackground,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        ScannedSummaryRow(
            totalNdcs = totalNdcs,
            totalPills = totalPills,
            onEndCount = onEndCount,
            endCountEnabled = endCountEnabled,
        )
    }
}
