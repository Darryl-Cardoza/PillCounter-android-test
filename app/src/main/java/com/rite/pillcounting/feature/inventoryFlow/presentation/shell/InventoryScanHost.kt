package com.rite.pillcounting.feature.inventoryFlow.presentation.shell

import Screen
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.presentation.compose.AddNoteDialog
import com.rite.pillcounting.core.tourguide.FTUE_ID
import com.rite.pillcounting.core.tourguide.FtueController
import com.rite.pillcounting.core.tourguide.TourGuideOverlay
import com.rite.pillcounting.core.tourguide.TourGuideTapBlock
import com.rite.pillcounting.core.tourguide.TourGuidePrefs
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.permission.rememberPermissionState
import com.rite.pillcounting.core.scanning.analyzer.FrameBarcodeAnalyzer
import com.rite.pillcounting.feature.inventoryFlow.domain.model.BatchStockCountUiState
import com.rite.pillcounting.feature.inventoryFlow.domain.model.EditBatchRow
import com.rite.pillcounting.feature.inventoryFlow.domain.model.EditDrugDetails
import com.rite.pillcounting.feature.inventoryFlow.domain.model.RecentBatchRow
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.feature.inventoryFlow.presentation.viewmodel.InventoryScanViewModel

/**
 * Everything a form-factor shell needs to draw itself. The host bakes the
 * analyzer-resume + navigation into the callbacks, so shells only lay out the
 * panel and the [CameraPreview]. [cameraVm]/[analyzer]/[logger] are exposed for
 * the camera extension and are not meant to be touched by the layout directly.
 */
class InventoryScanScope(
    val state: BatchStockCountUiState,
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
    internal val cameraVm: PillScanningViewModel,
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
 * [content], which receives a fully-wired [InventoryScanScope].
 *
 * Camera runs in NDC-only mode: the ML pill interpreter is never started (we omit
 * the onPreviewSizeKnown call). Frames go to the analyzer, which calls back to
 * [InventoryScanViewModel.onBarcodeDetected].
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun InventoryScanHost(
    navController: NavController,
    content: @Composable InventoryScanScope.() -> Unit,
) {
    val logger = remember { AppLogger("InventoryScreen") }
    val cameraVm: PillScanningViewModel = hiltViewModel()
    val inventoryVm: InventoryScanViewModel = hiltViewModel()

    val panelState by inventoryVm.uiState.collectAsState()
    val errorMessage by inventoryVm.errorMessage.collectAsState()
    val batchEnded by inventoryVm.batchEnded.collectAsState()
    val editDetails by inventoryVm.editDetails.collectAsState()

    var showNoteDialog by remember { mutableStateOf(false) }
    var showEndCountConfirmDialog by remember { mutableStateOf(false) }
    var pendingNote by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val analyzer = remember {
        FrameBarcodeAnalyzer(
            context.applicationContext,
            enableFocusChangeDebounce = true,
            // Inventory is barcode-only (no parallel pill detection), so we can
            // run more decode attempts/sec to cut time-to-detect on a steadied label.
            minIntervalMs = 100L,
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
        if (panelState.activeNdc == null) {
            analyzer.resume()
        } else {
            // Hands-free FTUE step: a scanned bottle just became the active NDC.
            FtueController.state.advanceIfCurrent("inv_camera_preview")
        }
    }

    // Surface VM errors as toasts. The VM emits a localized resource id + optional
    // arg; we resolve here so the VM stays Context-free.
    LaunchedEffect(errorMessage) {
        errorMessage?.let { err ->
            val text = if (err.formatArg != null) {
                context.getString(err.messageResId, err.formatArg)
            } else {
                context.getString(err.messageResId)
            }
            showToast(context, text)
            inventoryVm.clearErrorMessage()
        }
    }

    // After END COUNT confirms, leave the screen (pop, or fall back to Dashboard).
    LaunchedEffect(batchEnded) { if (batchEnded) inventoryBack(navController) }

    // Inventory wants continuous scanning, so bypass the legacy idle-pause path:
    // the shared VM auto-pauses after ~30s (to stop the ML pipeline).
    LaunchedEffect(Unit) { cameraVm.pauseIdleTimer() }

    // Returning from the SCAN PILLS loose-count flow persists open pills onto the
    // active NDC's txn but can't update this screen's in-memory card. Refresh the
    // active card's open-pill total on resume so the counted pills show in its total.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                inventoryVm.refreshActiveOpenPills()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // When the user tapped SCAN PILLS before any drug scan, the batch is created
    // lazily inside PillScanningViewModel.flushStagedDetails on Done and published
    // back here via SavedStateHandle so the inventory VM can adopt it and the
    // Recent Counts list re-binds to the real batch instead of the initial 0L.
    //
    // Uses currentBackStackEntryAsState() (reactive) instead of currentBackStackEntry
    // (snapshot). The snapshot value is captured once at composition and doesn't
    // recompose on back-stack changes, so a race between DispenseFlow's pop-back
    // and this collect starting could drop the adoption entirely.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentBackStackEntry) {
        val handle = currentBackStackEntry?.savedStateHandle ?: return@LaunchedEffect
        handle.getStateFlow<Long?>(Screen.DispenseFlow.NAV_KEY_STOCK_COUNT_BATCH_ID, null)
            .collect { adoptedBatchId ->
                if (adoptedBatchId != null && adoptedBatchId != 0L) {
                    inventoryVm.adoptStockCountBatchId(adoptedBatchId)
                    handle[Screen.DispenseFlow.NAV_KEY_STOCK_COUNT_BATCH_ID] = null
                }
            }
    }

    // System back gesture: same safe behavior as the on-screen back arrow.
    BackHandler { inventoryBack(navController) }

    // Named so the tour's dummy-Next handler below can invoke the exact same real
    // action a tap would, instead of duplicating it.
    val onEdit = {
        // FTUE hands-free: EDIT was actually tapped — advance past the optional
        // "inv_edit_button" step onto "inv_edit_actions" for the real panel's
        // Cancel/Save row.
        FtueController.state.advanceIfCurrent("inv_edit_button")
        inventoryVm.openEditDetails()
    }
    val onEndCount = {
        // FTUE safety net: user skipped some or all of the optional
        // "inv_edit_button"/"inv_edit_actions"/"inv_scan_pills" steps and tapped
        // END COUNT directly, so the ACTION_TAP tap-in-target advance for
        // "inv_end_count" never got a chance to fire (the tour wasn't on that
        // step yet) — skip past all of them in one go instead of getting stuck.
        FtueController.state.advanceWhileCurrentIn(
            setOf("inv_edit_button", "inv_edit_actions", "inv_scan_pills", "inv_end_count")
        )
        showNoteDialog = true
    }

    val scope = InventoryScanScope(
        state = panelState,
        canEndCount = panelState.totalNdcs > 0 || panelState.activeNdc != null,
        onScanPills = {
            // FTUE: SCAN PILLS is itself optional, but tapping it always means the
            // user is done with the optional "inv_edit_button"/"inv_edit_actions"
            // (Edit Details) and "inv_scan_pills" steps too, whichever of them the
            // tour is still sitting on — skip past all three in one go before the
            // dispense-flow hand-off below.
            FtueController.state.advanceWhileCurrentIn(
                setOf("inv_edit_button", "inv_edit_actions", "inv_scan_pills")
            )
            // Persist the active count, clear the staged txn, then hand
            // off to the merged dispense flow in stock-count mode. fromResume=true so
            // DispenseFlowScreen skips RX and loads the drug/txn from preferences,
            // landing at PRE_NDC ready for the container barcode scan.
            inventoryVm.onScanPillsForActive { batchId, allowedNdcs ->
                navController.navigate(
                    Screen.DispenseFlow.createRoute(
                        scanType = CountType.REGULAR.toString(),
                        batchId = batchId,
                        allowedNdcs = allowedNdcs,
                    )
                )
            }
        },
        onIncrement = inventoryVm::increment,
        onDecrement = inventoryVm::decrement,
        // Resume the analyzer explicitly on CLEAR / ADD / row-tap. The
        // LaunchedEffect(activeNdc) path also resumes, but if it lands before the
        // analyzer's per-hit self-pause (coroutine ordering) the analyzer stays
        // paused with no resume scheduled. Resuming here is order-independent.
        onClear = {
            logger.d("INV_SCAN onClear tapped")
            inventoryVm.onClear()
            analyzer.resume()
        },
        onAdd = {
            logger.d("INV_SCAN onAdd tapped active=${panelState.activeNdc?.ndc}")
            inventoryVm.onAdd()
            analyzer.resume()
        },
        onEndCount = onEndCount,
        onRowTapped = { row ->
            inventoryVm.onRecentRowTapped(row)
            analyzer.resume()
        },
        editDetails = editDetails,
        onEdit = onEdit,
        onEditDismiss = inventoryVm::dismissEditDetails,
        onEditSave = { sealed, open -> inventoryVm.saveEditDetails(sealed, open) },
        cameraVm = cameraVm,
        analyzer = analyzer,
        logger = logger,
        hasCameraPermission = hasCameraPermission,
        onBarcode = inventoryVm::onBarcodeDetected,
        onBtBarcode = inventoryVm::onBtBarcodeDetected,
    )

    // InventoryScanHost has no root Box of its own — each of the 4 shells builds a
    // differently-shaped root (Row/Box/Column/BottomSheetScaffold). Wrapping only
    // this call is the single, minimal place to hang the screen-root tour overlay
    // without touching any of the shells.
    Box(modifier = Modifier.fillMaxSize()) {
        scope.content()
        // The "end_count_confirm" step explains the real Yes/No confirmation dialog
        // (a shared, generic CommonDialog we deliberately don't tag/gate) — only show
        // its tooltip while that dialog is actually up, not in the brief moment right
        // after "add_note_actions" advances here but before showEndCountConfirmDialog
        // has been set. Same treatment as DashboardScreen.kt's "select_bucket",
        // including requiring isActive: dismiss() doesn't reset currentIndex, so a
        // tour that ended while sitting on this exact (last) step would otherwise
        // leave this true forever, permanently tap-blocking this real screen after.
        val suppressEndCountConfirmStep = FtueController.state.isActive &&
            FtueController.state.currentStep?.id == "end_count_confirm" && !showEndCountConfirmDialog
        if (suppressEndCountConfirmStep) {
            // Don't leave the real Batch Stock Count screen freely tappable in this
            // gap — an "outside tap" escape hatch out of the guided flow while the
            // tutorial is still active.
            TourGuideTapBlock(modifier = Modifier.fillMaxSize())
        } else {
            TourGuideOverlay(
                state = FtueController.state,
                onFinished = { TourGuidePrefs.markSeen(context, FTUE_ID) },
                modifier = Modifier.fillMaxSize(),
                // Escape hatch for every gated step this root overlay owns — the
                // camera-scan step fakes the missing real bottle; the rest just perform
                // the exact same action their real target would (reusing the very same
                // callbacks above where one exists), since those aren't blocked on any
                // external data in the first place.
                onDummyNext = when (FtueController.state.currentStep?.id) {
                    "inv_camera_preview" -> { { inventoryVm.useDummyNdcData() } }
                    "inv_counter" -> {
                        { FtueController.state.advanceIfCurrent("inv_counter"); inventoryVm.increment() }
                    }
                    "inv_edit_button" -> onEdit
                    "inv_edit_actions" -> {
                        // No real edits to save — dismissing without saving is the safe
                        // equivalent of tapping Cancel.
                        { FtueController.state.advanceIfCurrent("inv_edit_actions"); inventoryVm.dismissEditDetails() }
                    }
                    "inv_scan_pills" -> {
                        // Purely optional — dummy Next just skips it, same as the real
                        // "do neither SCAN PILLS nor EDIT" path.
                        { FtueController.state.advanceIfCurrent("inv_scan_pills") }
                    }
                    "inv_end_count" -> onEndCount
                    // "end_count_confirm" has no dummy-Next handler: its tooltip never
                    // renders a button row at all while the real confirmation dialog
                    // is open (TourGuideOverlay.kt's skipScrim) — that dialog's own
                    // Yes/No drive the tour instead (wired below).
                    else -> null
                },
            )
        }
    }

    if (showNoteDialog) {
        AddNoteDialog(
            onDismiss = { showNoteDialog = false },
            onSkip = {
                showNoteDialog = false
                pendingNote = null
                showEndCountConfirmDialog = true
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
                inventoryVm.confirmEndCount(pendingNote)
                showEndCountConfirmDialog = false
                pendingNote = null
                // FTUE: "end_count_confirm" is the tour's final step — this ends it,
                // exactly like tapping Yes for real finishing the batch.
                FtueController.state.advanceIfCurrent("end_count_confirm")
            },
            onCancel = {
                // FTUE: this dialog's own Yes/No are what drive the "end_count_confirm"
                // step now — its Skip/Next buttons aren't reliably tappable while this
                // real dialog is also open (see TourGuideOverlay.kt's skipScrim
                // comment), so No here doubles as ending the tour, same as Skip would.
                if (FtueController.state.isActive && FtueController.state.currentStep?.id == "end_count_confirm") {
                    FtueController.state.dismiss()
                }
                showEndCountConfirmDialog = false
                pendingNote = null
            },
        )
    }
}

/**
 * Safe back for the inventory shells. `popBackStack()` silently does nothing and
 * returns false when this screen is the back-stack root (e.g. reached via a
 * navigation that cleared the stack). Fall back to the Dashboard so BACK always
 * leaves the screen.
 */
internal fun inventoryBack(navController: NavController) {
    val popped = navController.popBackStack()
    if (!popped) {
        navController.navigate(Screen.Dashboard.route) {
            popUpTo(0)
            launchSingleTop = true
        }
    }
}
