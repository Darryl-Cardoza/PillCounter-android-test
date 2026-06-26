package com.rite.pillcounting.feature.inventoryFlow.presentation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.BtScannerInputBar
import com.rite.pillcounting.feature.inventoryFlow.presentation.variant.BatchStockCountPhoneLandscape
import com.rite.pillcounting.feature.inventoryFlow.presentation.variant.BatchStockCountPhonePortrait
import com.rite.pillcounting.feature.inventoryFlow.presentation.variant.BatchStockCountTabletLandscape
import com.rite.pillcounting.feature.inventoryFlow.presentation.variant.BatchStockCountTabletPortrait
import com.rite.pillcounting.ui.theme.AppTheme

// Each shell is layout-only: all VM/camera/analyzer/permission wiring lives in
// [InventoryScanHost], which supplies a fully-wired [InventoryScanScope].
//
// BT scanner pattern (uniform across all four shells):
//   - An invisible 1×1 dp focusable [BtScannerInputBar] captures HID keyboard
//     events from a Bluetooth scanner without triggering the soft IME.
//   - On Enter the accumulated barcode is dispatched via [InventoryScanScope.onBarcode],
//     which routes to [InventoryScanViewModel.onBarcodeDetected].
//   - The field auto-focuses on composition; [BtScannerInputBar] re-focuses on
//     every recomposition so focus is not lost after a scan.

// ── Shells ───────────────────────────────────────────────────────────────────

/** Tablet landscape: camera on the left, full-height stacked-card panel on the right. */
@Composable
fun InventoryTabletLandscapeShell(
    navController: NavController,
) = InventoryScanHost(navController) {
    var btScannerInput by remember { mutableStateOf("") }
    val btFocusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            CameraPreview(frameTag = "tablet-ls", modifier = Modifier.fillMaxSize())
            BackButton(navController, showBox = false, onClick = { inventoryBack(navController) })

            BtScannerInputBar(
                input = btScannerInput,
                onInputChange = { btScannerInput = it },
                onSubmit = { barcode ->
                    btScannerInput = ""
                    onBtBarcode(barcode)
                },
                focusRequester = btFocusRequester,
                modifier = Modifier.align(Alignment.TopStart),
            )
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
                endCountEnabled = canEndCount,
                onRowTapped = onRowTapped,
                onEdit = onEdit,
                editDetails = editDetails,
                onEditDismiss = onEditDismiss,
                onEditSave = onEditSave,
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
fun InventoryPhoneLandscapeShell(
    navController: NavController,
) = InventoryScanHost(navController) {
    var btScannerInput by remember { mutableStateOf("") }
    val btFocusRequester = remember { FocusRequester() }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val detailsCardWidth = 300.dp
    val collapsedWidth = detailsCardWidth + 28.dp
    val expandedWidth = screenWidthDp * 0.9f
    var expanded by remember { mutableStateOf(false) }
    val panelWidth = if (expanded) expandedWidth else collapsedWidth

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(frameTag = "phone-ls", modifier = Modifier.fillMaxSize())
        BackButton(navController, showBox = false, onClick = { inventoryBack(navController) })

        BtScannerInputBar(
            input = btScannerInput,
            onInputChange = { btScannerInput = it },
            onSubmit = { barcode ->
                btScannerInput = ""
                onBtBarcode(barcode)
            },
            focusRequester = btFocusRequester,
            modifier = Modifier.align(Alignment.TopStart),
        )

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
fun InventoryTabletPortraitShell(
    navController: NavController,
) = InventoryScanHost(navController) {
    var btScannerInput by remember { mutableStateOf("") }
    val btFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            CameraPreview(frameTag = "tablet-pt", modifier = Modifier.fillMaxSize())
            BackButton(navController, showBox = false, onClick = { inventoryBack(navController) })

            BtScannerInputBar(
                input = btScannerInput,
                onInputChange = { btScannerInput = it },
                onSubmit = { barcode ->
                    btScannerInput = ""
                    onBtBarcode(barcode)
                },
                focusRequester = btFocusRequester,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.40f),
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
                onEdit = onEdit,
                editDetails = editDetails,
                onEditDismiss = onEditDismiss,
                onEditSave = onEditSave,
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
fun InventoryPhonePortraitShell(
    navController: NavController,
) = InventoryScanHost(navController) {
    var btScannerInput by remember { mutableStateOf("") }
    val btFocusRequester = remember { FocusRequester() }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        )
    )
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    var peekHeight by remember { mutableStateOf(320.dp) }
    val listMaxHeight = (screenHeightDp - peekHeight).coerceAtLeast(120.dp)

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

        // BT scanner sits above the scaffold so it doesn't compete with the sheet
        // drag handle hit area. Must remain in composition between scans to hold
        // focus — visibility is controlled by the stage/overlay guard.
        BtScannerInputBar(
            input = btScannerInput,
            onInputChange = { btScannerInput = it },
            onSubmit = { barcode ->
                btScannerInput = ""
                onBtBarcode(barcode)
            },
            focusRequester = btFocusRequester,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // BackButton drawn last so it sits above the scaffold and receives taps.
        BackButton(navController, showBox = false, onClick = { inventoryBack(navController) })
    }
}