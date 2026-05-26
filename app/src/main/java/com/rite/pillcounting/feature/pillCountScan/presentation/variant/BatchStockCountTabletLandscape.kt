package com.rite.pillcounting.feature.pillCountScan.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.CompositionLocalProvider
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ActiveNdc
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountHeader
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountSampleData
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountUiState
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentBatchRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsLabelRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsList
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedDrugCard
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedSummaryCard
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.StockCountCard

/**
 * Tablet landscape variant of the redesigned Batch Stock Count panel.
 *
 * Persistent right-side panel (NOT a draggable bottom sheet). Caller is
 * expected to place this as a sibling of the camera preview inside a Row so
 * the scanner shrinks to give it room rather than being overlaid.
 *
 * Two stacked cards:
 *  - Top: header + Scan Pills + "Recent Batch Count" list (newest first).
 *  - Bottom: scanned-drug counter card when [BatchStockCountUiState.activeNdc]
 *    is non-null, otherwise the summary card.
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
    modifier: Modifier = Modifier,
) {
    // Bottom card overlaps the top card by [BOTTOM_OVERLAP] and carries a soft
    // upward shadow so it reads as an overlay on the recent-counts card. Box
    // (instead of Column with spacedBy) lets the bottom child sit at the bottom
    // edge while the top child fills the rest of the height behind it.
    Box(
        modifier = modifier
            .fillMaxHeight(),
    ) {
        StockCountCard(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                BatchStockCountHeader(onScanPills = onScanPills)
                Spacer(modifier = Modifier.height(16.dp))
                val label = if (state.activeNdc != null) {
                    "RECENT BATCH COUNT (${state.totalNdcs})"
                } else {
                    "RECENT COUNTS"
                }
                RecentCountsLabelRow(label = label)
                Spacer(modifier = Modifier.height(8.dp))
                // Bottom padding clears the overlapping card so the last list
                // row never hides behind it.
                RecentCountsList(
                    rows = state.recentCounts,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = OVERLAY_CARD_CLEARANCE),
                )
            }
        }

        // Bottom card with a hand-drawn upward gradient shadow. The
        // Modifier.shadow API on Android often renders too subtly against
        // white, so we paint our own band above the card with drawBehind.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            // The shadow band sits ABOVE the card and fades upward — gives a
            // clear "overlay floating on top" cue.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OVERLAY_SHADOW_HEIGHT)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f),
                            )
                        )
                    )
            )
            StockCountCard {
                if (state.activeNdc != null) {
                    ScannedDrugCard(
                        active = state.activeNdc,
                        onIncrement = onIncrement,
                        onDecrement = onDecrement,
                        onClear = onClear,
                        onAdd = onAdd,
                    )
                } else {
                    ScannedSummaryCard(
                        totalNdcs = state.totalNdcs,
                        totalPills = state.totalPills,
                        onEndCount = onEndCount,
                    )
                }
            }
        }
    }
}

/**
 * Bottom padding reserved inside the top card so the last list row doesn't
 * hide behind the overlay card. Slightly larger than the overlay's elevation
 * to leave breathing room.
 */
private val OVERLAY_CARD_CLEARANCE = 24.dp

/** Height of the upward gradient shadow band that sits above the bottom overlay card. */
private val OVERLAY_SHADOW_HEIGHT = 14.dp

/**
 * Stateful preview host that mimics the Figma: grey camera area on the left,
 * the new panel pinned to the right ~42% of the screen. Counter +/- mutate the
 * sample state so the long-press repeat behavior can be exercised in preview
 * on a real device.
 */
@Composable
fun BatchStockCountTabletLandscapePreviewHost(
    initialActive: Boolean = true,
) {
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

    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF1F1F1F))) {
        // Left: camera placeholder.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF6F6F6F)),
            contentAlignment = Alignment.Center,
        ) {}

        // Right: the new panel.
        Box(
            modifier = Modifier
                .width(380.dp)
                .fillMaxHeight()
        ) {
            BatchStockCountTabletLandscape(
                state = state,
                onScanPills = {
                    // Toggle to active sample for the preview.
                    state = state.copy(activeNdc = BatchStockCountSampleData.activeState.activeNdc)
                },
                onIncrement = {
                    state.activeNdc?.let { a ->
                        state = state.copy(activeNdc = a.copy(bottles = a.bottles + 1))
                    }
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

@Preview(name = "Tablet Landscape — Active", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun BatchStockCountTabletLandscape_ActivePreview() {
    PreviewTheme {
        BatchStockCountTabletLandscapePreviewHost(initialActive = true)
    }
}

@Preview(name = "Tablet Landscape — Summary", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun BatchStockCountTabletLandscape_SummaryPreview() {
    PreviewTheme {
        BatchStockCountTabletLandscapePreviewHost(initialActive = false)
    }
}
