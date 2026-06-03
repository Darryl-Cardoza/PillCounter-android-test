package com.rite.pillcounting.feature.pillCountScan.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
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
 * phone). The card content reuses [ScannedDrugDetailsPhone] / [ScannedSummaryRow]
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
    // Max height the list region may take (px) when expanded; keeps the inner
    // LazyColumn bounded (a fillMaxSize/weight LazyColumn in a wrap-content sheet
    // crashes with "measured with infinity").
    listMaxHeight: Dp,
    modifier: Modifier = Modifier,
    // END COUNT is disabled until at least one NDC has been scanned.
    endCountEnabled: Boolean = true,
    onRowTapped: (RecentBatchRow) -> Unit = {},
    // Reports the measured height (px) of the always-visible region (header +
    // card) so the host can size the sheet's peek to exactly show it — no fixed
    // guess that clips the counter, no dead space above.
    onPeekHeightChanged: (Int) -> Unit = {},
) {
    // NO own background / rounded surface here — the hosting BottomSheetScaffold
    // provides the grey sheet container, rounded top corners, and the drag handle.
    // wrapContentHeight (NOT fillMaxSize) so the sheet sizes to its content rather
    // than always stretching to max — that stretch is what pushed the header down
    // and dropped the counter below the peek fold.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        // Peek region: top padding + header + the details/counter card + a bottom
        // gap. ALL of it is INSIDE the measured region so the peek height matches
        // it exactly — no extra space below (which leaked the recent-counts header
        // into the collapsed peek) and the header gets real space above it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onPeekHeightChanged(it.height) },
        ) {
            // Top breathing room above the header (no drag handle anymore).
            Spacer(modifier = Modifier.height(20.dp))
            BatchStockCountHeader(onScanPills = onScanPills)
            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
                    EmptyScannedDetailsPhone(
                        totalNdcs = state.totalNdcs,
                        totalPills = state.totalPills,
                        onEndCount = onEndCount,
                        endCountEnabled = endCountEnabled,
                    )
                }
            }

            // Breathing room below the card before the recent-counts section.
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Recent counts — revealed once the sheet is dragged up past the peek.
        Spacer(modifier = Modifier.height(16.dp))
        val label = if (state.recentCounts.isNotEmpty()) {
            stringResource(R.string.batch_stock_count_recent_with_count, state.totalNdcs)
        } else {
            stringResource(R.string.batch_stock_count_recent_summary)
        }
        RecentCountsLabelRow(label = label)
        Spacer(modifier = Modifier.height(8.dp))
        // Bounded height (NOT weight/fillMaxSize) so the LazyColumn measures inside
        // a wrap-content sheet without crashing.
        RecentCountsList(
            rows = state.recentCounts,
            onRowTapped = onRowTapped,
            modifier = Modifier.fillMaxWidth().height(listMaxHeight),
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
    endCountEnabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
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
        ScannedSummaryRow(
            totalNdcs = totalNdcs,
            totalPills = totalPills,
            onEndCount = onEndCount,
            endCountEnabled = endCountEnabled,
        )
    }
}
