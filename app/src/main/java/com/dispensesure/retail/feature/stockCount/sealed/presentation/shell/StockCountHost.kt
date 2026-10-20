package com.dispensesure.retail.feature.stockCount.sealed.presentation.shell

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dispensesure.retail.R
import com.dispensesure.retail.core.region.LocalRegion
import com.dispensesure.retail.core.region.wording
import com.dispensesure.retail.core.scanning.analyzer.FrameBarcodeAnalyzer
import com.dispensesure.retail.core.scanning.presentation.compose.AddNoteDialog
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourActionTimeoutMs
import com.dispensesure.retail.core.tourguide.TourGuideOverlay
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.CommonDialog
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
import com.dispensesure.retail.core.utils.common.navigateBackToDashboard
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.permission.rememberPermissionState
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.EditBatchRow
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.EditDrugDetails
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.RecentBatchRow
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.StockCountUiState
import com.dispensesure.retail.feature.stockCount.sealed.presentation.viewmodel.StockCountViewModel
import com.dispensesure.retail.navigation.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Everything a form-factor shell needs to draw itself. The host bakes the
 * analyzer-resume + navigation into the callbacks, so shells only lay out the
 * panel and the [CameraPreview]. [analyzer]/[logger] are exposed for
 * the camera extension and are not meant to be touched by the layout directly.
 */
class StockCountScope(
    val state: StockCountUiState,
    val canEndCount: Boolean,
    val onScanPills: () -> Unit,
    val onIncrement: () -> Unit,
    val onDecrement: () -> Unit,
    val onClear: () -> Unit,
    val onAdd: () -> Unit,
    val onEndCount: () -> Unit,
    val onRowTapped: (RecentBatchRow) -> Unit,
    /** Non-null while the Edit Details panel is open. */
    val editDetails: EditDrugDetails?,
    /** Open the Edit Details panel for the active NDC. */
    val onEdit: () -> Unit,
    /** Dismiss the Edit Details panel without saving. */
    val onEditDismiss: () -> Unit,
    /** Persist the edited sealed/open rows and close the panel. */
    val onEditSave: (sealed: List<EditBatchRow>, open: List<EditBatchRow>) -> Unit,
    internal val analyzer: FrameBarcodeAnalyzer,
    internal val logger: AppLogger,
    internal val hasCameraPermission: Boolean,
    internal val onBarcode: (String) -> Unit,
    internal val onBtBarcode: (String) -> Unit,
)

/**
 * Stateful host for the Batch Stock Count inventory mode, shared by all four
 * form-factor shells. Owns the VMs, the NDC barcode analyzer, camera permission,
 * the cross-cutting effects (error toasts, end-count navigation, idle-pause
 * bypass, system back) and the end-count dialog. The layout is supplied by
 * [content], which receives a fully-wired [StockCountScope].
 *
 * Camera runs in NDC-only mode: the ML pill interpreter is never started (we omit
 * the onCameraReady call). Frames go to the analyzer, which calls back to
 * [StockCountViewModel.onBarcodeDetected].
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun StockCountHost(
    navController: NavController,
    content: @Composable StockCountScope.() -> Unit,
) {
    val logger = remember { AppLogger("StockCountScreen") }
    val stockCountViewModel: StockCountViewModel = hiltViewModel()

    val panelState by stockCountViewModel.uiState.collectAsState()
    val errorMessage by stockCountViewModel.errorMessage.collectAsState()
    val batchEnded by stockCountViewModel.batchEnded.collectAsState()
    val editDetails by stockCountViewModel.editDetails.collectAsState()

    var showNoteDialog by remember { mutableStateOf(false) }
    var showEndCountConfirmDialog by remember { mutableStateOf(false) }
    var pendingNote by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val loggedInRegion = LocalRegion.current
    val analyzer = remember {
        FrameBarcodeAnalyzer(
            context.applicationContext,
            enableFocusChangeDebounce = true,
            // Inventory is barcode-only (no parallel pill detection), so we can
            // run more decode attempts/sec to cut time-to-detect on a steadied label.
            minIntervalMs = 100L,
            isGrayscale = stockCountViewModel::isGrayscaleImage,
            region = loggedInRegion,
        )
    }
    DisposableEffect(Unit) { onDispose { analyzer.close() } }

    // Camera permission. Without it CameraX retries forever and the preview never
    // streams (infinite spinner). Request on entry; render the camera once granted.
    // Falls back to a Settings-redirect dialog if the user has permanently denied it.
    val hasCameraPermission = rememberPermissionState(
        permission = Manifest.permission.CAMERA,
        rationaleTitle = stringResource(R.string.permission_camera_title),
        rationaleMessage = stringResource(R.string.permission_camera_rationale),
    )

    // Resume the analyzer when the active NDC card clears. We do NOT pause while
    // activeNdc is non-null: the analyzer self-pauses on each hit, and the VM
    // auto-commits the current NDC when a different one is scanned. Pausing here
    // would block frames and make that swap unreachable.
    LaunchedEffect(panelState.activeNdc) {
        logger.d("INV_SCAN activeNdc → ${panelState.activeNdc?.ndc} hazardous=${panelState.activeNdc?.isHazardous}")
        if (panelState.activeNdc == null) analyzer.resume()
    }

    // Surface VM errors as toasts. The VM emits a localized resource id + optional
    // arg; we resolve here so the VM stays Context-free.
    LaunchedEffect(errorMessage) {
        errorMessage?.let { err ->
            val text = if (err.formatArg != null) {
                context.getString(loggedInRegion.wording(err.messageResId), err.formatArg)
            } else {
                context.getString(loggedInRegion.wording(err.messageResId))
            }
            showToast(text)
            stockCountViewModel.clearErrorMessage()
        }
    }

    // After END COUNT confirms, leave the screen (pop, or fall back to Dashboard).
    LaunchedEffect(batchEnded) { if (batchEnded) stockCountBack(navController) }

    // Returning from the SCAN PILLS loose-count flow persists open pills onto the
    // active NDC's txn but can't update this screen's in-memory card. Refresh the
    // active card's open-pill total on resume so the counted pills show in its total.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                stockCountViewModel.refreshActiveOpenPills()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // When the user tapped SCAN PILLS before any drug scan, the batch is created
    // when the loose count is saved and published
    // back here via SavedStateHandle so the inventory VM can adopt it and the
    // Recent Counts list re-binds to the real batch instead of the initial 0L.
    //
    // Uses currentBackStackEntryAsState() (reactive) instead of currentBackStackEntry
    // (snapshot). The snapshot value is captured once at composition and doesn't
    // recompose on back-stack changes, so a race between LooseCount's pop-back
    // and this collect starting could drop the adoption entirely.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentBackStackEntry) {
        val handle = currentBackStackEntry?.savedStateHandle ?: return@LaunchedEffect
        handle.getStateFlow<Long?>(Screen.LooseCount.RESULT_BATCH_ID, null)
            .collect { adoptedBatchId ->
                if (adoptedBatchId != null && adoptedBatchId != 0L) {
                    stockCountViewModel.adoptBatchId(adoptedBatchId)
                    handle[Screen.LooseCount.RESULT_BATCH_ID] = null
                }
            }
    }

    // System back gesture: same safe behavior as the on-screen back arrow.
    BackHandler { stockCountBack(navController) }

    // Named so the tour's dummy-Next handler below can invoke the exact same real
    // action a tap would, instead of duplicating it.
    val onEdit = { stockCountViewModel.openEditDetails() }
    val onEndCount = { showNoteDialog = true }

    val scope = StockCountScope(
        state = panelState,
        canEndCount = panelState.totalNdcs > 0 || panelState.activeNdc != null,
        onScanPills = {
            // Persist the active count, then open the loose count for this batch
            // with its bucket and, for a PMS batch, the requested NDCs.
            stockCountViewModel.onScanPillsForActive { batchId, bucketId, allowedNdcs ->
                navController.navigate(
                    Screen.LooseCount.createRoute(
                        batchId = batchId,
                        bucketId = bucketId,
                        allowedNdcs = allowedNdcs,
                    )
                )
            }
        },
        onIncrement = stockCountViewModel::increment,
        onDecrement = stockCountViewModel::decrement,
        // Resume the analyzer explicitly on CLEAR / ADD / row-tap. The
        // LaunchedEffect(activeNdc) path also resumes, but if it lands before the
        // analyzer's per-hit self-pause (coroutine ordering) the analyzer stays
        // paused with no resume scheduled. Resuming here is order-independent.
        onClear = {
            logger.d("INV_SCAN onClear tapped")
            stockCountViewModel.onClear()
            analyzer.resume()
        },
        onAdd = {
            logger.d("INV_SCAN onAdd tapped active=${panelState.activeNdc?.ndc}")
            stockCountViewModel.onAdd()
            analyzer.resume()
        },
        onEndCount = onEndCount,
        onRowTapped = { row ->
            stockCountViewModel.onRecentRowTapped(row)
            analyzer.resume()
        },
        editDetails = editDetails,
        onEdit = onEdit,
        onEditDismiss = stockCountViewModel::dismissEditDetails,
        onEditSave = { sealed, open -> stockCountViewModel.saveEditDetails(sealed, open) },
        analyzer = analyzer,
        logger = logger,
        hasCameraPermission = hasCameraPermission,
        onBarcode = stockCountViewModel::onBarcodeDetected,
        onBtBarcode = stockCountViewModel::onBtBarcodeDetected,
    )

    // StockCountHost has no root Box of its own — each of the 4 shells builds a
    // differently-shaped root (Row/Box/Column/BottomSheetScaffold). Wrapping only
    // this call is the single, minimal place to hang the screen-root tour overlay
    // without touching any of the shells.
    val demoDrugName = stringResource(R.string.tour_demo_drug_name)
    Box(modifier = Modifier.fillMaxSize()) {
        scope.content()
        // s_note is drawn by the Add Note dialog's own window (TourDialogHost).
        if (FtueController.state.currentStep?.id != "s_note") TourGuideOverlay(
            state = FtueController.state,
            modifier = Modifier.fillMaxSize(),
            onNext = {
                when (FtueController.state.currentStep?.id) {
                    "s_scan" -> {
                        stockCountViewModel.useDummyNdcData(demoDrugName)
                        withTimeout(TourActionTimeoutMs) { snapshotFlow { panelState.activeNdc }.first { it != null } }
                    }
                    "s_counter" -> stockCountViewModel.increment()
                    // END COUNT only shows once the active bottle is committed, as in real use.
                    "s_scan_pills" -> {
                        stockCountViewModel.onAdd()
                        analyzer.resume()
                        withTimeout(TourActionTimeoutMs) { snapshotFlow { panelState.activeNdc }.first { it == null } }
                    }
                    "s_edit" -> onEdit()
                    // No real edits to save — dismissing without saving is the same as Cancel.
                    "s_edit_actions" -> stockCountViewModel.dismissEditDetails()
                    "s_end_count" -> onEndCount()
                }
            },
        )
    }

    if (showNoteDialog) {
        AddNoteDialog(
            onDismiss = { showNoteDialog = false },
            onSkip = {
                showNoteDialog = false
                pendingNote = null
                if (FtueController.state.isActive) {
                    // Tour: skip the confirm dialog and finish the demo count directly.
                    stockCountViewModel.confirmEndCount(null)
                } else {
                    showEndCountConfirmDialog = true
                }
            },
            onSave = { note ->
                showNoteDialog = false
                pendingNote = note
                showEndCountConfirmDialog = true
            },
        )
    }

    if (showEndCountConfirmDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_end_this_count),
            title = stringResource(R.string.confirmation),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                stockCountViewModel.confirmEndCount(pendingNote)
                showEndCountConfirmDialog = false
                pendingNote = null
            },
            onCancel = {
                showEndCountConfirmDialog = false
                pendingNote = null
            },
        )
    }
}

/**
 * Safe back for the stock-count shells. `popBackStack()` silently does nothing and
 * returns false when this screen is the back-stack root (e.g. reached via a
 * navigation that cleared the stack). Fall back to the Dashboard so BACK always
 * leaves the screen.
 */
internal fun stockCountBack(navController: NavController) {
    val popped = navController.popBackStack()
    if (!popped) {
        navController.navigateBackToDashboard()
    }
}
