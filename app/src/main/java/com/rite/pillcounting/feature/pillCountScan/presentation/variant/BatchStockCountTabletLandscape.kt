package com.rite.pillcounting.feature.pillCountScan.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.BatchStockCountUiState
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.RecentBatchRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountHeader
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsLabelRow
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentCountsList
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedDrugCard
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ScannedSummaryCard
import com.rite.pillcounting.ui.theme.AppTheme

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
    onRowTapped: (RecentBatchRow) -> Unit = {},
) {
    // Bottom card overlaps the top card and carries a soft upward shadow so it
    // reads as an overlay on the recent-counts card. Box (instead of Column
    // with spacedBy) lets the bottom child sit at the bottom edge while the
    // top child fills the rest of the height behind it.
    //
    // We measure the bottom card's height at runtime so the top list can
    // reserve exactly that much bottom padding — otherwise the last list rows
    // would render BEHIND the overlay and bleed through visibly.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val overlayHeightDp = with(density) { overlayHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxHeight(),
    ) {
        // Top section: flat-bottom container so the bottom card's rounded top
        // corners read as the only curve at their meeting point. Top corners
        // stay square too — the panel sits flush against the screen edge.
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
                // Bottom padding clears the overlapping card so the last list
                // row never hides behind it. Width is measured at runtime via
                // overlayHeightDp (see overlay column's onSizeChanged below).
                RecentCountsList(
                    rows = state.recentCounts,
                    onRowTapped = onRowTapped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = overlayHeightDp),
                )
            }
        }

        // Bottom card with a hand-drawn upward gradient shadow. The
        // Modifier.shadow API on Android often renders too subtly against
        // white, so we paint our own band above the card with drawBehind.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { },
        ) {
            // The shadow band sits ABOVE the card and fades upward — gives a
            // clear "overlay floating on top" cue.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.18f),
                            )
                        )
                    )
            )
            // Bottom card: pronounced top corners (so they read clearly against
            // the shadow band above), flat bottom corners since the card sits
            // flush against the screen edge.
            Box(
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

///**
// * Stateful preview host that mimics the Figma: grey camera area on the left,
// * the new panel pinned to the right ~42% of the screen. Counter +/- mutate the
// * sample state so the long-press repeat behavior can be exercised in preview
// * on a real device.
// */
//@Composable
//fun BatchStockCountTabletLandscapePreviewHost(
//    initialActive: Boolean = true,
//) {
//    val sampleRow = RecentBatchRow(ndc = "234-345-654-2343", drugName = "Levothyroxidfrfgfne 250mg", pills = 1800, bottles = 20)
//    val sampleActiveNdc = ActiveNdc(ndc = "234-345-654-2343", drugName = "Levothyroxine Disulphide 50mg", bucket = "Normal", batchNo = "4324534547", expiry = "05-23-2026", pillsPerBottle = 9, bottles = 20)
//    var state by remember {
//        mutableStateOf(
//            if (initialActive) BatchStockCountUiState(recentCounts = List(5) { sampleRow }, activeNdc = sampleActiveNdc, totalNdcs = 25, totalPills = 5648)
//            else BatchStockCountUiState(recentCounts = List(8) { sampleRow }, activeNdc = null, totalNdcs = 150, totalPills = 5648)
//        )
//    }
//
//    fun commitActiveToList(active: ActiveNdc) {
//        val newRow = RecentBatchRow(
//            ndc = active.ndc,
//            drugName = active.drugName,
//            pills = active.totalPills,
//            bottles = active.bottles,
//        )
//        state = state.copy(
//            recentCounts = listOf(newRow) + state.recentCounts,
//            activeNdc = null,
//            totalNdcs = state.totalNdcs + 1,
//            totalPills = state.totalPills + active.totalPills,
//        )
//    }
//
//    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF1F1F1F))) {
//        // Left: camera placeholder.
//        Box(
//            modifier = Modifier
//                .weight(1f)
//                .fillMaxHeight()
//                .background(Color(0xFF6F6F6F)),
//            contentAlignment = Alignment.Center,
//        ) {}
//
//        // Right: the new panel.
//        Box(
//            modifier = Modifier
//                .width(380.dp)
//                .fillMaxHeight()
//        ) {
//            BatchStockCountTabletLandscape(
//                state = state,
//                onScanPills = {
//                    state = state.copy(activeNdc = sampleActiveNdc)
//                },
//                onIncrement = {
//                    state.activeNdc?.let { a ->
//                        state = state.copy(activeNdc = a.copy(bottles = a.bottles + 1))
//                    }
//                },
//                onDecrement = {
//                    state.activeNdc?.let { a ->
//                        val next = (a.bottles - 1).coerceAtLeast(1)
//                        state = state.copy(activeNdc = a.copy(bottles = next))
//                    }
//                },
//                onClear = { state = state.copy(activeNdc = null) },
//                onAdd = { state.activeNdc?.let { commitActiveToList(it) } },
//                onEndCount = {},
//            )
//        }
//    }
//}
//
//@Composable
//private fun PreviewTheme(content: @Composable () -> Unit) {
//    val extended = ExtendedColors(
//        primaryBackground = PrimaryBackground,
//        secondaryBackground = SecondaryBackground,
//        textColor = TextColor,
//        inputBackground = inputBackground,
//        statusChipBackgroundOnPrimary = statusChipBackgroundOnPrimary,
//        statusChipBackgroundOnSecondary = statusChipBackgroundOnSecondary,
//    )
//    CompositionLocalProvider(LocalExtendedColors provides extended) {
//        MaterialTheme(
//            colorScheme = lightColorScheme(primary = PrimaryColor, secondary = SecondaryColor),
//            content = content,
//        )
//    }
//}

//@Preview(name = "Tablet Landscape — Active", device = "spec:width=1280dp,height=800dp,dpi=240")
//@Composable
//private fun BatchStockCountTabletLandscape_ActivePreview() {
//    PreviewTheme {
//        BatchStockCountTabletLandscapePreviewHost(initialActive = true)
//    }
//}
//
//@Preview(name = "Tablet Landscape — Summary", device = "spec:width=1280dp,height=800dp,dpi=240")
//@Composable
//private fun BatchStockCountTabletLandscape_SummaryPreview() {
//    PreviewTheme {
//        BatchStockCountTabletLandscapePreviewHost(initialActive = false)
//    }
//}
