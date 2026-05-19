package com.rite.pillcounting.core.utils.compose

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Hides status + navigation bars on whichever window owns the calling composable.
 * For a ModalBottomSheet / Dialog this targets the dialog window (so the sheet
 * doesn't pull nav buttons over its content). For a regular activity content
 * scope this targets the activity window. Uses SideEffect + a brief
 * FLAG_NOT_FOCUSABLE toggle so the WindowInsetsController applies even on
 * dialog windows that wouldn't otherwise honor it.
 */
@Composable
fun HideSystemBarsInCurrentWindow() {
    val view = LocalView.current
    androidx.compose.runtime.SideEffect {
        val window: android.view.Window =
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                ?: run {
                    var ctx: android.content.Context? = view.context
                    while (ctx != null && ctx !is android.app.Activity) {
                        ctx = (ctx as? android.content.ContextWrapper)?.baseContext
                    }
                    (ctx as? android.app.Activity)?.window
                }
                ?: return@SideEffect

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        // Toggle focusability so the InsetsController actually applies to the dialog window.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}

/**
 * Inline (NOT overlay) version of the Rx-details panel — designed to be placed
 * as a sibling of another composable (e.g. the camera scanner) inside a Row in
 * landscape, so the scanner can shrink to give it space rather than being
 * covered by an overlay. The caller controls visibility and animation; this
 * composable just renders the styled body.
 *
 * Uses tablet-vertical layout on tablets and the compact phone body on phones.
 */
@Composable
fun VerifyRxDetailsInlinePanel(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        if (isTablet) {
            TabletVerticalBody(
                drugName = drugName,
                quantity = quantity,
                bucket = bucket,
                ndcNumber = ndcNumber,
                rxNumber = rxNumber,
                onCancel = onCancel,
                onProceed = onProceed,
            )
        } else {
            SheetBody(
                drugName = drugName,
                quantity = quantity,
                bucket = bucket,
                ndcNumber = ndcNumber,
                rxNumber = rxNumber,
                isLandscape = true,
                onCancel = onCancel,
                onProceed = onProceed,
            )
        }
    }
}

/* ── Tweakable knobs ──────────────────────────────────────────────
 * Adjust these to nudge the sheet/drawer position if your device still
 * shows a gap. Negative values push past the system-bar inset.
 */
private val PORTRAIT_BOTTOM_NUDGE_DP = (-24).dp   // how far the sheet body extends past the bottom edge
private val LANDSCAPE_RIGHT_NUDGE_DP = (-24).dp   // how far the drawer extends past the right edge
private const val SHEET_USE_NO_LIMITS = true       // apply FLAG_LAYOUT_NO_LIMITS to the sheet's own window
/* ───────────────────────────────────────────────────────────────── */

/**
 * Orientation-aware Rx details prompt:
 * - Portrait: ModalBottomSheet sliding from the bottom edge.
 * - Landscape: custom drawer sliding in from the right edge.
 */
@Composable
fun VerifyRxDetailsSheet(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    // When false, the sheet/drawer cannot be dismissed by swipe / outside-tap /
    // back press — only the Cancel and Proceed buttons close it.
    dismissible: Boolean = true,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // sw600dp is the standard Android tablet threshold — matches Theme.kt's switch.
    val isTablet = configuration.smallestScreenWidthDp >= 600

    when {
        isTablet && isLandscape -> VerifyRxDetailsTabletSideDrawer(
            drugName = drugName,
            quantity = quantity,
            bucket = bucket,
            ndcNumber = ndcNumber,
            rxNumber = rxNumber,
            onCancel = onCancel,
            onProceed = onProceed,
            dismissible = dismissible,
        )
        isTablet -> VerifyRxDetailsTabletBottomSheet(
            drugName = drugName,
            quantity = quantity,
            bucket = bucket,
            ndcNumber = ndcNumber,
            rxNumber = rxNumber,
            onCancel = onCancel,
            onProceed = onProceed,
            dismissible = dismissible,
        )
        isLandscape -> VerifyRxDetailsSideDrawer(
            drugName = drugName,
            quantity = quantity,
            bucket = bucket,
            ndcNumber = ndcNumber,
            rxNumber = rxNumber,
            onCancel = onCancel,
            onProceed = onProceed,
            dismissible = dismissible,
        )
        else -> VerifyRxDetailsBottomSheet(
            drugName = drugName,
            quantity = quantity,
            bucket = bucket,
            ndcNumber = ndcNumber,
            rxNumber = rxNumber,
            onCancel = onCancel,
            onProceed = onProceed,
            dismissible = dismissible,
        )
    }
}

/**
 * Hides the system navigation bar while present in the composition, and restores
 * it on dispose. Status bar stays untouched. Used by all four sheet/drawer variants
 * so the panel can sit flush against the physical screen edge.
 */
/**
 * Hides the navigation/status bars on the **sheet/dialog/popup's own window** so it
 * inherits the app's immersive fullscreen mode. The host Activity's window is left
 * untouched — its immersive state (configured globally via enableImmersiveFullscreen)
 * remains in effect across the rest of the app.
 *
 * For ModalBottomSheet and Dialog the LocalView is rooted in a separate window that
 * defaults to fitting system bars — that's what was re-showing the nav buttons.
 * For Popup the LocalView shares the Activity window, so this becomes a no-op there.
 */
@Composable
private fun HideSystemNavBar() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        // Find the Window that owns *this* view (the sheet/dialog/popup window).
        val sheetWindow: android.view.Window? = generateSequence<android.view.ViewParent>(view.parent) { it.parent }
            .mapNotNull { (it as? androidx.compose.ui.window.DialogWindowProvider)?.window }
            .firstOrNull()

        // The host Activity window — always re-hide bars on it after dismissal so the
        // app's own immersive state doesn't get left in a half-restored state.
        val activityWindow: android.view.Window? = run {
            var ctx: android.content.Context? = view.context
            while (ctx != null && ctx !is android.app.Activity) {
                ctx = (ctx as? android.content.ContextWrapper)?.baseContext
            }
            (ctx as? android.app.Activity)?.window
        }

        fun hideBars(w: android.view.Window) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            )
        }

        // Apply to the sheet window itself (if any) and to the activity. The
        // ModalBottomSheet/Dialog window is a *separate* window — when it gains
        // focus the system shows nav buttons on top of it unless its own
        // WindowInsetsController hides them.
        val targetWindow = sheetWindow ?: activityWindow
        if (targetWindow != null) {
            if (SHEET_USE_NO_LIMITS) {
                targetWindow.setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
            }
            hideBars(targetWindow)
        }

        // Re-hide whenever the sheet window gains focus — the system re-shows
        // bars on focus transitions and this is what was leaving the pill visible.
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && targetWindow != null) hideBars(targetWindow)
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            // After the sheet closes, the activity may have momentarily lost
            // immersive — restore it explicitly.
            activityWindow?.let { hideBars(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyRxDetailsBottomSheet(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { if (dismissible) true else it != androidx.compose.material3.SheetValue.Hidden }
    )

    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onCancel() },
        sheetState = sheetState,
        containerColor = AppTheme.extendedColors.secondaryBackground,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        // Bars are hidden by HideSystemBarsInCurrentWindow below, so content goes
        // flush to the screen edge with no nav-bar inset reserved.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        // Targets the sheet's dialog window — this is the call that actually
        // removes the gesture-pill / nav buttons from overlaying the sheet.
        HideSystemBarsInCurrentWindow()
        SheetBody(
            drugName = drugName,
            quantity = quantity,
            bucket = bucket,
            ndcNumber = ndcNumber,
            rxNumber = rxNumber,
            isLandscape = false,
            onCancel = onCancel,
            onProceed = onProceed,
        )
    }
}

@Composable
private fun VerifyRxDetailsSideDrawer(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val config = LocalConfiguration.current
    val drawerWidth = (config.screenWidthDp.dp * 0.42f).coerceIn(260.dp, 380.dp)
    SwipeableSideDrawer(
        drawerWidth = drawerWidth,
        onCancel = onCancel,
        cornerRadius = 20.dp,
        dismissible = dismissible,
    ) { animatedCancel ->
        SheetBody(
            drugName = drugName,
            quantity = quantity,
            bucket = bucket,
            ndcNumber = ndcNumber,
            rxNumber = rxNumber,
            isLandscape = true,
            onCancel = animatedCancel,
            onProceed = onProceed,
        )
    }
}

/**
 * Right-anchored slide-in drawer with:
 *  - smooth slide-in on appear
 *  - swipe-right-to-dismiss (drag the drawer to the right)
 *  - smooth slide-out on cancel/back press (the [animatedCancel] callback exposed
 *    to the content triggers the exit animation, then invokes [onCancel] once it
 *    finishes — replacing the original instant-stop dismissal).
 *
 * [content] receives `animatedCancel` so it can wire CANCEL / outside actions
 * through the same animated dismissal path.
 */
@Composable
private fun SwipeableSideDrawer(
    drawerWidth: Dp,
    onCancel: () -> Unit,
    cornerRadius: Dp,
    dismissible: Boolean = true,
    content: @Composable (animatedCancel: () -> Unit) -> Unit,
) {
    HideSystemNavBar()
    Popup(
        onDismissRequest = { if (dismissible) onCancel() },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        )
    ) {
        // visible drives both enter and the deferred exit animation.
        var visible by remember { mutableStateOf(false) }
        var pendingClose by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { visible = true }

        // After visible flips to false, wait for the exit animation, then dismiss.
        androidx.compose.runtime.LaunchedEffect(visible, pendingClose) {
            if (!visible && pendingClose) {
                kotlinx.coroutines.delay(220)
                onCancel()
            }
        }
        val animatedCancel: () -> Unit = {
            pendingClose = true
            visible = false
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = dismissible,
                    onClick = animatedCancel
                )
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(durationMillis = 260)
                ) + fadeIn(animationSpec = tween(durationMillis = 260)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(durationMillis = 220)
                ) + fadeOut(animationSpec = tween(durationMillis = 220)),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                // Drag offset (px) accumulated by swipe-right gesture.
                val density = androidx.compose.ui.platform.LocalDensity.current
                val dismissThresholdPx = with(density) { (drawerWidth * 0.35f).toPx() }
                var dragOffsetPx by remember { mutableStateOf(0f) }
                val animatedDragOffset by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = dragOffsetPx,
                    animationSpec = tween(durationMillis = 120),
                    label = "drawerDragOffset"
                )

                // LANDSCAPE_RIGHT_NUDGE_DP is negative — pushes the drawer rightward
                // past the edge of the scrim Box so it reaches the physical screen edge.
                val rightNudgePx = with(density) { LANDSCAPE_RIGHT_NUDGE_DP.toPx() }
                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(animatedDragOffset.toInt() - rightNudgePx.toInt(), 0) }
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .clip(RoundedCornerShape(topStart = cornerRadius, bottomStart = cornerRadius))
                        .background(AppTheme.extendedColors.secondaryBackground)
                        // Swallow taps so they don't bubble to the scrim.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .draggable(
                            orientation = Orientation.Horizontal,
                            enabled = dismissible,
                            state = rememberDraggableState { delta ->
                                // Allow only rightward (positive) drag; clamp to non-negative.
                                dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f)
                            },
                            onDragStopped = {
                                if (dragOffsetPx >= dismissThresholdPx) {
                                    animatedCancel()
                                } else {
                                    dragOffsetPx = 0f
                                }
                            }
                        )
                ) {
                    content(animatedCancel)
                }
            }
        }
    }
}

/* ─────────────────────────  TABLET  ───────────────────────── */

/**
 * Tablet portrait: full-width bottom sheet. Tiles are laid out horizontally in a row,
 * details labels/values sit horizontally below the tiles, buttons centered at bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyRxDetailsTabletBottomSheet(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { if (dismissible) true else it != androidx.compose.material3.SheetValue.Hidden }
    )
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onCancel() },
        sheetState = sheetState,
        containerColor = AppTheme.extendedColors.secondaryBackground,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        HideSystemBarsInCurrentWindow()
        TabletHorizontalBody(
            drugName = drugName,
            quantity = quantity,
            bucket = bucket,
            ndcNumber = ndcNumber,
            rxNumber = rxNumber,
            onCancel = onCancel,
            onProceed = onProceed
        )
    }
}

/**
 * Tablet landscape: right-side drawer (~40% width, full height). Tiles stacked
 * vertically and details stacked vertically below, all centered.
 */
@Composable
private fun VerifyRxDetailsTabletSideDrawer(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val config = LocalConfiguration.current
    val drawerWidth = (config.screenWidthDp.dp * 0.4f).coerceIn(360.dp, 520.dp)
    SwipeableSideDrawer(
        drawerWidth = drawerWidth,
        onCancel = onCancel,
        cornerRadius = 24.dp,
        dismissible = dismissible,
    ) { animatedCancel ->
        TabletVerticalBody(
            drugName = drugName,
            quantity = quantity,
            bucket = bucket,
            ndcNumber = ndcNumber,
            rxNumber = rxNumber,
            onCancel = animatedCancel,
            onProceed = onProceed
        )
    }
}

/**
 * Body for tablet portrait bottom sheet: title, horizontal row of tiles, horizontal
 * row of detail items below, buttons centered.
 */
@Composable
private fun TabletHorizontalBody(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.verify_rx_details),
            color = AppTheme.extendedColors.textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FormTile()
            QuantityTile(quantity = quantity)
            BucketTile(bucket = bucket)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            CenteredDetail(
                label = stringResource(R.string.ndc_number),
                value = ndcNumber,
                modifier = Modifier.weight(1f)
            )
            CenteredDetail(
                label = stringResource(R.string.drugname),
                value = drugName,
                modifier = Modifier.weight(1f)
            )
            CenteredDetail(
                label = stringResource(R.string.rx_number),
                value = rxNumber,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Use the local tablet button row so HollowButton & ActionButtonPrimary
        // share identical outer widths in portrait *and* landscape on tablet.
        TabletButtonRow(onCancel = onCancel, onProceed = onProceed, spacing = 14.dp)

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Tablet button row: CANCEL/PROCEED with identical widths. Both use the same
 * underlying Box(width = TABLET_BUTTON_WIDTH) wrapping HollowButton/ActionButtonPrimary,
 * with the inner button forced to fillMaxSize() so border + content padding
 * resolve to the same bounds for both.
 */
@Composable
private fun TabletButtonRow(
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    spacing: Dp,
) {
    // Use the universal HollowButton (outlined) + ActionButtonPrimary (filled).
    // Give the Row a fixed total width and let each child claim weight(1f) of it
    // — this forces identical layout widths regardless of the differing internal
    // size/border modifiers inside the two button composables.
    val rowWidth = TABLET_BUTTON_WIDTH * 2 + spacing
    Row(
        modifier = Modifier.width(rowWidth),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        HollowButton(
            text = stringResource(R.string.cancel).uppercase(),
            onClick = onCancel,
            color = MaterialTheme.colorScheme.primary,
            fixedWidth = false,
            modifier = Modifier.weight(1f),
        )
        ActionButtonPrimary(
            text = stringResource(R.string.proceed).uppercase(),
            onClick = onProceed,
            color = MaterialTheme.colorScheme.primary,
            fixedWidth = false,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Body for tablet landscape side drawer: tiles stacked vertically (centered),
 * details stacked vertically below (each label+value centered), buttons centered.
 */
@Composable
private fun TabletVerticalBody(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.verify_rx_details),
            color = AppTheme.extendedColors.textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FormTile()
            QuantityTile(quantity = quantity)
            BucketTile(bucket = bucket)

            Spacer(modifier = Modifier.height(8.dp))

            CenteredDetail(
                label = stringResource(R.string.ndc_number),
                value = ndcNumber
            )
            CenteredDetail(
                label = stringResource(R.string.drugname),
                value = drugName
            )
            CenteredDetail(
                label = stringResource(R.string.rx_number),
                value = rxNumber
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TabletButtonRow(onCancel = onCancel, onProceed = onProceed, spacing = 12.dp)

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// Tablet CANCEL/PROCEED button dimensions — same in portrait & landscape so they
// look identical on a tablet. Adjust here to resize both at once.
private val TABLET_BUTTON_WIDTH = 140.dp
private val TABLET_BUTTON_HEIGHT = 44.dp

/** Centered label-on-top, value-below detail item used in tablet layouts. */
@Composable
private fun CenteredDetail(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = AppTheme.extendedColors.textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/* ────────────────────────  PHONE BODY  ─────────────────────── */

@Composable
private fun SheetBody(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    isLandscape: Boolean,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
) {
    // 3-section layout: fixed title, scrollable middle (gets remaining height),
    // pinned button row. Outer column fills the parent's bounded height so weight() works.
    val extraBottom = if (!isLandscape) (-PORTRAIT_BOTTOM_NUDGE_DP).coerceAtLeast(0.dp) else 0.dp
    // Inline landscape usage gives this Column a bounded height, so fillMaxHeight
    // pushes the buttons to the bottom via weight(1f) on the scroll section.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isLandscape) it.fillMaxHeight() else it }
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp + extraBottom)
    ) {
        Text(
            text = stringResource(R.string.verify_rx_details),
            color = AppTheme.extendedColors.textColor,
            fontSize = if (isLandscape) 15.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLandscape) 8.dp else 12.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f, fill = isLandscape)
                .fillMaxWidth()
                .then(
                    // Portrait: keep verticalScroll so long content doesn't break
                    // layout. Landscape: skip scroll so DetailsGrid can fillMaxHeight()
                    // and use SpaceBetween to evenly distribute the three rows.
                    if (isLandscape) Modifier else Modifier.verticalScroll(rememberScrollState())
                )
        ) {
            DetailsGrid(
                drugName = drugName,
                quantity = quantity,
                bucket = bucket,
                ndcNumber = ndcNumber,
                rxNumber = rxNumber,
                compact = isLandscape,
            )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 10.dp else 14.dp))

        // Fixed-width buttons so the physical size matches across portrait
        // (wide sheet) and landscape (narrow side panel) on the same device.
        // HollowButton vs ActionButtonPrimary internally apply slightly different
        // size/border modifiers, so wrapping each in a Box(width=BUTTON_WIDTH) +
        // fillMaxWidth() on the button forces both outer bounds to match exactly.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            Box(modifier = Modifier.width(BUTTON_WIDTH)) {
                HollowButton(
                    text = stringResource(R.string.cancel).uppercase(),
                    onClick = onCancel,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Box(modifier = Modifier.width(BUTTON_WIDTH)) {
                ActionButtonPrimary(
                    text = stringResource(R.string.proceed).uppercase(),
                    onClick = onProceed,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// Phone-body CANCEL/PROCEED button width — same in portrait & landscape so they
// look identical on a single device. Adjust here to resize both at once.
private val BUTTON_WIDTH = 130.dp

/**
 * Renders three aligned rows: [Form tile | Drug Name], [Quantity tile | NDC Number],
 * [Bucket tile | Rx Number]. Each row's two children share the same vertical center.
 */
@Composable
private fun DetailsGrid(
    drugName: String,
    quantity: String,
    bucket: String,
    ndcNumber: String,
    rxNumber: String,
    compact: Boolean,
) {
    val rowSpacing = if (compact) 6.dp else 10.dp
    // Landscape: fill the available height and SpaceBetween-distribute the three
    // rows so the gap between rows expands instead of leaving dead space below
    // the last row. Portrait keeps fixed spacedBy() — natural top-aligned flow.
    Column(
        modifier = if (compact) Modifier.fillMaxHeight() else Modifier,
        verticalArrangement = if (compact) Arrangement.SpaceBetween
        else Arrangement.spacedBy(rowSpacing)
    ) {
        TileDetailRow(
            tile = { FormTile(compact = compact) },
            label = stringResource(R.string.drugname),
            value = drugName,
            compact = compact,
        )
        TileDetailRow(
            tile = { QuantityTile(quantity = quantity, compact = compact) },
            label = stringResource(R.string.ndc_number),
            value = ndcNumber,
            compact = compact,
        )
        TileDetailRow(
            tile = { BucketTile(bucket = bucket, compact = compact) },
            label = stringResource(R.string.rx_number),
            value = rxNumber,
            compact = compact,
        )
    }
}

@Composable
private fun TileDetailRow(
    tile: @Composable () -> Unit,
    label: String,
    value: String,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        tile()
        DetailItem(
            label = label,
            value = value,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FormTile(compact: Boolean = false) {
    SquareTile(label = stringResource(R.string.form), compact = compact) {
        Icon(
            painter = painterResource(R.drawable.pill_capsule),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(if (compact) 22.dp else 28.dp)
        )
    }
}

@Composable
private fun QuantityTile(quantity: String, compact: Boolean = false) {
    SquareTile(label = stringResource(R.string.quantity), compact = compact) {
        Text(
            text = quantity,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = if (compact) 18.sp else 20.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BucketTile(bucket: String, compact: Boolean = false) {
    SquareTile(label = stringResource(R.string.bucket), compact = compact) {
        Text(
            text = bucket.ifBlank { "-" },
            color = MaterialTheme.colorScheme.secondary,
            fontSize = if (compact) 14.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SquareTile(
    label: String,
    compact: Boolean = false,
    content: @Composable () -> Unit
) {
    val tileSize = if (compact) 70.dp else 84.dp
    Column(
        modifier = Modifier
            .width(tileSize)
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.extendedColors.primaryBackground)
            .padding(vertical = if (compact) 7.dp else 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            color = AppTheme.extendedColors.textColor,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Normal
        )
        Box(
            modifier = Modifier.height(if (compact) 26.dp else 34.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun DetailItem(
    label: String,
    value: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Header (e.g. "Drug Name") → white.
        Text(
            text = label,
            color = AppTheme.extendedColors.textColor,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Normal
        )
        // Value below the header → app textColor token (not the cyan accent).
        Text(
            text = value,
            color = AppTheme.extendedColors.textColor,
            fontSize = if (compact) 14.sp else 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}


// ──────────────────────────────────────────────────────────────────────────────
// NDC / Stock-bottle verification sheet
//
// Mirrors the RX sheet pattern (portrait ModalBottomSheet, landscape side
// drawer, tablet variants) but renders NDC-specific content:
//   - Form tile + NDC number
//   - Bucket tile + Drug name
//
// Reuses the same SwipeableSideDrawer / HideSystemNavBar /
// FormTile / BucketTile / SquareTile / DetailItem / TileDetailRow / TabletButtonRow
// helpers above so the look-and-feel matches exactly.
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Inline (non-overlay) version of the NDC verification panel for landscape
 * usage where the camera shrinks to give the panel room rather than being
 * covered by an overlay. Parity with [VerifyRxDetailsInlinePanel].
 */
@Composable
fun VerifyNdcDetailsInlinePanel(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        if (isTablet) {
            NdcTabletVerticalBody(
                drugName = drugName,
                bucket = bucket,
                ndcNumber = ndcNumber,
                onCancel = onCancel,
                onProceed = onProceed,
            )
        } else {
            NdcSheetBody(
                drugName = drugName,
                bucket = bucket,
                ndcNumber = ndcNumber,
                isLandscape = true,
                onCancel = onCancel,
                onProceed = onProceed,
            )
        }
    }
}

/**
 * Orientation-aware NDC verification prompt. Same surface treatment as
 * [VerifyRxDetailsSheet]: portrait → ModalBottomSheet, landscape → right-side
 * drawer; tablet variants for both.
 *
 * Set [dismissible] = false to force the user to commit via Cancel/Proceed
 * (matches the merged dispense flow's contract).
 */
@Composable
fun VerifyNdcDetailsSheet(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = configuration.smallestScreenWidthDp >= 600

    when {
        isTablet && isLandscape -> VerifyNdcDetailsTabletSideDrawer(
            drugName = drugName,
            bucket = bucket,
            ndcNumber = ndcNumber,
            onCancel = onCancel,
            onProceed = onProceed,
            dismissible = dismissible,
        )
        isTablet -> VerifyNdcDetailsTabletBottomSheet(
            drugName = drugName,
            bucket = bucket,
            ndcNumber = ndcNumber,
            onCancel = onCancel,
            onProceed = onProceed,
            dismissible = dismissible,
        )
        isLandscape -> VerifyNdcDetailsSideDrawer(
            drugName = drugName,
            bucket = bucket,
            ndcNumber = ndcNumber,
            onCancel = onCancel,
            onProceed = onProceed,
            dismissible = dismissible,
        )
        else -> VerifyNdcDetailsBottomSheet(
            drugName = drugName,
            bucket = bucket,
            ndcNumber = ndcNumber,
            onCancel = onCancel,
            onProceed = onProceed,
            dismissible = dismissible,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyNdcDetailsBottomSheet(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { if (dismissible) true else it != androidx.compose.material3.SheetValue.Hidden }
    )

    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onCancel() },
        sheetState = sheetState,
        containerColor = AppTheme.extendedColors.secondaryBackground,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        HideSystemBarsInCurrentWindow()
        NdcSheetBody(
            drugName = drugName,
            bucket = bucket,
            ndcNumber = ndcNumber,
            isLandscape = false,
            onCancel = onCancel,
            onProceed = onProceed,
        )
    }
}

@Composable
private fun VerifyNdcDetailsSideDrawer(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val config = LocalConfiguration.current
    val drawerWidth = (config.screenWidthDp.dp * 0.42f).coerceIn(260.dp, 380.dp)
    SwipeableSideDrawer(
        drawerWidth = drawerWidth,
        onCancel = onCancel,
        cornerRadius = 20.dp,
        dismissible = dismissible,
    ) { animatedCancel ->
        NdcSheetBody(
            drugName = drugName,
            bucket = bucket,
            ndcNumber = ndcNumber,
            isLandscape = true,
            onCancel = animatedCancel,
            onProceed = onProceed,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyNdcDetailsTabletBottomSheet(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { if (dismissible) true else it != androidx.compose.material3.SheetValue.Hidden }
    )
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onCancel() },
        sheetState = sheetState,
        containerColor = AppTheme.extendedColors.secondaryBackground,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        HideSystemBarsInCurrentWindow()
        NdcTabletHorizontalBody(
            drugName = drugName,
            bucket = bucket,
            ndcNumber = ndcNumber,
            onCancel = onCancel,
            onProceed = onProceed,
        )
    }
}

@Composable
private fun VerifyNdcDetailsTabletSideDrawer(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    dismissible: Boolean = true,
) {
    val config = LocalConfiguration.current
    val drawerWidth = (config.screenWidthDp.dp * 0.4f).coerceIn(360.dp, 520.dp)
    SwipeableSideDrawer(
        drawerWidth = drawerWidth,
        onCancel = onCancel,
        cornerRadius = 24.dp,
        dismissible = dismissible,
    ) { animatedCancel ->
        NdcTabletVerticalBody(
                drugName = drugName,
                bucket = bucket,
                ndcNumber = ndcNumber,
                onCancel = animatedCancel,
                onProceed = onProceed,
            )
    }
}

/**
 * Phone-body for portrait sheet and landscape inline panel. Two rows:
 *   - [Form tile | NDC number]
 *   - [Bucket tile | Drug name]
 */
@Composable
private fun NdcSheetBody(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    isLandscape: Boolean,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
) {
    val extraBottom = if (!isLandscape) (-PORTRAIT_BOTTOM_NUDGE_DP).coerceAtLeast(0.dp) else 0.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isLandscape) it.fillMaxHeight() else it }
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp + extraBottom)
    ) {
        Text(
            text = stringResource(R.string.verify_stock_bottle_details),
            color = AppTheme.extendedColors.textColor,
            fontSize = if (isLandscape) 15.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLandscape) 8.dp else 12.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f, fill = isLandscape)
                .fillMaxWidth()
                .then(
                    if (isLandscape) Modifier else Modifier.verticalScroll(rememberScrollState())
                )
        ) {
            NdcDetailsGrid(
                drugName = drugName,
                bucket = bucket,
                ndcNumber = ndcNumber,
                compact = isLandscape,
            )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 10.dp else 14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            Box(modifier = Modifier.width(BUTTON_WIDTH)) {
                HollowButton(
                    text = stringResource(R.string.cancel).uppercase(),
                    onClick = onCancel,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(modifier = Modifier.width(BUTTON_WIDTH)) {
                ActionButtonPrimary(
                    text = stringResource(R.string.proceed).uppercase(),
                    onClick = onProceed,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Two-row NDC grid: [Form | NDC number], [Bucket | Drug name]. */
@Composable
private fun NdcDetailsGrid(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    compact: Boolean,
) {
    val rowSpacing = if (compact) 6.dp else 10.dp
    Column(
        modifier = if (compact) Modifier.fillMaxHeight() else Modifier,
        verticalArrangement = if (compact) Arrangement.SpaceBetween
        else Arrangement.spacedBy(rowSpacing)
    ) {
        TileDetailRow(
            tile = { FormTile(compact = compact) },
            label = stringResource(R.string.ndc_number),
            value = ndcNumber,
            compact = compact,
        )
        TileDetailRow(
            tile = { BucketTile(bucket = bucket, compact = compact) },
            label = stringResource(R.string.drugname),
            value = drugName,
            compact = compact,
        )
    }
}

/** Tablet portrait: title, horizontal row of two tiles, centered detail row. */
@Composable
private fun NdcTabletHorizontalBody(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.verify_stock_bottle_details),
            color = AppTheme.extendedColors.textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FormTile()
            BucketTile(bucket = bucket)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            CenteredDetail(
                label = stringResource(R.string.ndc_number),
                value = ndcNumber,
                modifier = Modifier.weight(1f)
            )
            CenteredDetail(
                label = stringResource(R.string.drugname),
                value = drugName,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        TabletButtonRow(onCancel = onCancel, onProceed = onProceed, spacing = 14.dp)

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Tablet landscape side drawer: tiles + details stacked vertically. */
@Composable
private fun NdcTabletVerticalBody(
    drugName: String,
    bucket: String,
    ndcNumber: String,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.verify_stock_bottle_details),
            color = AppTheme.extendedColors.textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FormTile()
            BucketTile(bucket = bucket)

            Spacer(modifier = Modifier.height(8.dp))

            CenteredDetail(
                label = stringResource(R.string.ndc_number),
                value = ndcNumber
            )
            CenteredDetail(
                label = stringResource(R.string.drugname),
                value = drugName
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TabletButtonRow(onCancel = onCancel, onProceed = onProceed, spacing = 12.dp)

        Spacer(modifier = Modifier.height(8.dp))
    }
}
