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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ActiveNdc
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountHeader
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountSampleData
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountUiState
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentBatchRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsLabelRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsList
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedDrugDetailsPortrait
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedSummaryRow
import com.rite.pillcounting.ui.theme.ExtendedColors
import com.rite.pillcounting.ui.theme.LocalExtendedColors
import com.rite.pillcounting.ui.theme.PrimaryBackground
import com.rite.pillcounting.ui.theme.PrimaryColor
import com.rite.pillcounting.ui.theme.SecondaryBackground
import com.rite.pillcounting.ui.theme.SecondaryColor
import com.rite.pillcounting.ui.theme.TextColor
import com.rite.pillcounting.ui.theme.inputBackground
import com.rite.pillcounting.ui.theme.statusChipBackgroundOnPrimary
import com.rite.pillcounting.ui.theme.statusChipBackgroundOnSecondary
import androidx.compose.material3.lightColorScheme

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
    onRowTapped: (RecentBatchRow) -> Unit = {},
    modifier: Modifier = Modifier,
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
            .background(Color(0xFFF2F2F2)),
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
            color = Color(0xFF888888),
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

/* ─────────────────────────  PREVIEW  ───────────────────────── */

@Composable
private fun BatchStockCountTabletPortraitPreviewHost(initialActive: Boolean) {
    var state by remember {
        mutableStateOf(
            if (initialActive) BatchStockCountSampleData.activeState
            else BatchStockCountSampleData.summaryState
        )
    }

    fun commitActiveToList(active: ActiveNdc) {
        val newRow = RecentBatchRow(
            ndc = active.ndc,
            drugName = active.drugName,
            pills = active.totalPills,
            bottles = active.bottles,
        )
        state = state.copy(
            recentCounts = listOf(newRow) + state.recentCounts,
            activeNdc = null,
            totalNdcs = state.totalNdcs + 1,
            totalPills = state.totalPills + active.totalPills,
        )
    }

    // Camera placeholder on top, panel pinned to the bottom ~42%.
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFE5E5E5))) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF6F6F6F)),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(0.85f)) {
            BatchStockCountTabletPortrait(
                state = state,
                onScanPills = {
                    state = state.copy(activeNdc = BatchStockCountSampleData.activeState.activeNdc)
                },
                onIncrement = {
                    state.activeNdc?.let { a -> state = state.copy(activeNdc = a.copy(bottles = a.bottles + 1)) }
                },
                onDecrement = {
                    state.activeNdc?.let { a ->
                        val next = (a.bottles - 1).coerceAtLeast(1)
                        state = state.copy(activeNdc = a.copy(bottles = next))
                    }
                },
                onClear = { state = state.copy(activeNdc = null) },
                onAdd = { state.activeNdc?.let { commitActiveToList(it) } },
                onEndCount = {},
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun PreviewTheme(content: @Composable () -> Unit) {
    val extended = ExtendedColors(
        primaryBackground = PrimaryBackground,
        secondaryBackground = SecondaryBackground,
        textColor = TextColor,
        inputBackground = inputBackground,
        statusChipBackgroundOnPrimary = statusChipBackgroundOnPrimary,
        statusChipBackgroundOnSecondary = statusChipBackgroundOnSecondary,
    )
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = lightColorScheme(primary = PrimaryColor, secondary = SecondaryColor),
            content = content,
        )
    }
}

@Preview(name = "Tablet Portrait — Active", device = "spec:width=800dp,height=1280dp,dpi=240")
@Composable
private fun BatchStockCountTabletPortrait_ActivePreview() {
    PreviewTheme { BatchStockCountTabletPortraitPreviewHost(initialActive = true) }
}

@Preview(name = "Tablet Portrait — Summary", device = "spec:width=800dp,height=1280dp,dpi=240")
@Composable
private fun BatchStockCountTabletPortrait_SummaryPreview() {
    PreviewTheme { BatchStockCountTabletPortraitPreviewHost(initialActive = false) }
}
