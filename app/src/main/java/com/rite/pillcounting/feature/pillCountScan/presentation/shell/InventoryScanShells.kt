package com.rite.pillcounting.feature.pillCountScan.presentation.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountPhoneLandscape
import com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountPhonePortrait
import com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountTabletLandscape
import com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountTabletPortrait
import com.rite.pillcounting.ui.theme.AppTheme

// Each shell is layout-only: all VM/camera/analyzer/permission wiring lives in
// [InventoryScanHost], which supplies a fully-wired InventoryScanScope receiver.

/** Tablet landscape: camera on the left, full-height stacked-card panel on the right. */
@Composable
fun InventoryTabletLandscapeShell(navController: NavController) = InventoryScanHost(navController) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            CameraPreview(frameTag = "tablet-ls", modifier = Modifier.fillMaxSize())
            BackButton(navController, showBox = false, onClick = { inventoryBack(navController) })
        }
        // Left edge rounded (24dp) so the panel reads as a curved sheet over the camera.
        Box(
            modifier = Modifier
                .width(500.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                .background(AppTheme.extendedColors.secondaryBackground),
        ) {
            BatchStockCountTabletLandscape(
                state = state,
                onScanPills = onScanPills,
                onIncrement = onIncrement,
                onDecrement = onDecrement,
                onClear = onClear,
                onAdd = onAdd,
                onEndCount = onEndCount,
                onRowTapped = onRowTapped,
            )
        }
    }
}

/**
 * Phone landscape: full-screen camera with a right-docked panel that shows only
 * the details card when collapsed and slides outward (widens leftward) to reveal
 * RECENT COUNTS. Drag left to expand, right to collapse.
 */
@Composable
fun InventoryPhoneLandscapeShell(navController: NavController) = InventoryScanHost(navController) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val detailsCardWidth = 300.dp
    val collapsedWidth = detailsCardWidth + 28.dp        // + panel horizontal padding
    val expandedWidth = screenWidthDp * 0.9f             // 90% of screen width when expanded
    var expanded by remember { mutableStateOf(false) }
    val panelWidth by animateDpAsState(
        targetValue = if (expanded) expandedWidth else collapsedWidth,
        label = "panelWidth",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(frameTag = "phone-ls", modifier = Modifier.fillMaxSize())
        BackButton(navController, showBox = false, onClick = { inventoryBack(navController) })

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(panelWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                .background(AppTheme.extendedColors.primaryBackground)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        if (delta < -8f) expanded = true
                        else if (delta > 8f) expanded = false
                    },
                ),
        ) {
            BatchStockCountPhoneLandscape(
                state = state,
                recentVisible = expanded,
                detailsCardWidth = detailsCardWidth,
                onScanPills = onScanPills,
                onIncrement = onIncrement,
                onDecrement = onDecrement,
                onClear = onClear,
                onAdd = onAdd,
                onEndCount = onEndCount,
                endCountEnabled = canEndCount,
                onRowTapped = onRowTapped,
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

/** Tablet portrait: camera on top, fixed-height (~38%) panel pinned to the bottom. */
@Composable
fun InventoryTabletPortraitShell(navController: NavController) = InventoryScanHost(navController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            CameraPreview(frameTag = "tablet-pt", modifier = Modifier.fillMaxSize())
            BackButton(navController, showBox = false, onClick = { inventoryBack(navController) })
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.38f),
        ) {
            BatchStockCountTabletPortrait(
                state = state,
                onScanPills = onScanPills,
                onIncrement = onIncrement,
                onDecrement = onDecrement,
                onClear = onClear,
                onAdd = onAdd,
                onEndCount = onEndCount,
                onRowTapped = onRowTapped,
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

/**
 * Phone portrait: full-screen camera with a persistent, non-dismissible
 * [BottomSheetScaffold] over it. Collapsed (peek) shows the details card; pulling
 * up reveals RECENT COUNTS. The panel self-sizes to a 320dp peek.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryPhonePortraitShell(navController: NavController) = InventoryScanHost(navController) {
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        )
    )
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    // Peek height is driven by the measured content height so the sheet always
    // shows the full header + card without clipping. Starts at 320dp while the
    // first measurement arrives, then updates to the real content height.
    var peekHeight by remember { mutableStateOf(320.dp) }
    val listMaxHeight = (screenHeightDp - peekHeight).coerceAtLeast(120.dp)

    // Camera is the full-screen base; the scaffold layers on top with a transparent
    // body so the camera shows through. The back arrow is drawn LAST so it sits on
    // top of the scaffold and actually receives taps.
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(frameTag = "phone", modifier = Modifier.fillMaxSize())

        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetContainerColor = AppTheme.extendedColors.primaryBackground,
            sheetDragHandle = null,
            containerColor = Color.Transparent,
            sheetContent = {
                // Cap the sheet at 90% of screen height so it never covers the
                // full screen when dragged to the top.
                Box(modifier = Modifier.heightIn(max = screenHeightDp * 0.9f)) {
                    BatchStockCountPhonePortrait(
                        state = state,
                        onScanPills = onScanPills,
                        onIncrement = onIncrement,
                        onDecrement = onDecrement,
                        onClear = onClear,
                        onAdd = onAdd,
                        onEndCount = onEndCount,
                        endCountEnabled = canEndCount,
                        onRowTapped = onRowTapped,
                        onPeekHeightChanged = { heightPx ->
                            val measured: Dp = with(density) { heightPx.toDp() }
                            if (measured > 0.dp) peekHeight = measured
                        },
                        listMaxHeight = listMaxHeight,
                    )
                }
            },
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize())
        }

        BackButton(navController, showBox = false, onClick = { inventoryBack(navController) })
    }
}