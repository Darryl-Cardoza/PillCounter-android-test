package com.rite.pillcounting.core.tourguide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.ui.theme.AppTheme.extendedColors
import kotlin.math.roundToInt

private val SpotlightPadding = 2.dp
private val SpotlightCornerRadius = 12.dp
private val TooltipWidth = 300.dp
private val TooltipMargin = 16.dp
private val EstimatedTooltipHeight = 190.dp

// A camera screen's own real UI (the workflow-stepper's step-name bubble, its progress bar,
// action buttons) already lives right at the bottom edge — the same edge a BOTTOM-pinned
// tooltip (e.g. a full-screen camera-preview step) would otherwise sit flush against,
// overlapping and garbling both. This pushes it up clear of that zone instead.
private val PinnedBottomExtraClearance = 130.dp

/**
 * Full-screen spotlight tour: dims everything except [TourGuideState]'s current target
 * (cut out of the scrim via [BlendMode.Clear]), rings it, and shows a tooltip. Place this
 * as the last child of the screen's root [Box] so it draws above the content it points at.
 *
 * Behavior branches on the current step's [TourStepMode]:
 * - [TourStepMode.INFO]: blocks every tap, tooltip has Skip/Back/Next.
 * - [TourStepMode.ACTION_TAP]: blocks every tap *except* inside the target rect or the
 *   tooltip card itself (so Skip always stays reachable); a tap inside the target is left
 *   unconsumed (the real control underneath still fires) and is independently detected as
 *   the signal to advance — no Next button. A tap is only ever blocked once the target has
 *   actually registered its bounds — never while it's still null (not yet mounted, or
 *   mounted in a different window this overlay instance can't see), which would otherwise
 *   trap the user behind a full block with nothing tappable.
 * - [TourStepMode.ACTION_WAIT]: blocks nothing; pure visual spotlight. Advancing is driven
 *   externally via [TourGuideState.advanceIfCurrent].
 *
 * [modifier] must size this overlay to exactly the area it should cover — pass
 * `Modifier.fillMaxSize()` at a screen root, or `Modifier.matchParentSize()` when hosting it
 * inside a `Box` alongside content whose own size should NOT be forced to expand (a
 * `ModalBottomSheet`/`Dialog`'s content, which otherwise sizes to wrap its real content —
 * `fillMaxSize()` there would inflate the sheet/dialog itself to fill the whole screen).
 *
 * [pinnedEdgeOverride], when non-null, wins over the current step's own [TourStep.pinnedEdge]
 * for this render only. For a step whose target fills most of the screen (a camera preview)
 * a caller may need to flip which edge the tooltip pins to depending on transient live UI
 * state it alone knows about — e.g. a bottom sheet briefly still visible/animating out over
 * the same camera view, which a bottom-pinned tooltip would otherwise overlap.
 *
 * [isScreenRoot]: pass `false` for an instance mounted inside a `ModalBottomSheet`/`Dialog`
 * rather than at a screen's own root. A step whose real target lives in THIS instance's
 * window is always rendered here regardless (its bounds resolve, so ownership is
 * unambiguous) — this flag only decides who renders a step that has *no* discoverable
 * target at all (an `INFO` step before its target mounts, or a `pinnedEdge` step like a
 * full-screen camera preview). Every currently-mounted instance would otherwise satisfy
 * that "no target" case identically and all render the same tooltip on top of each other —
 * only the screen root should win that tie.
 */
@Composable
fun TourGuideOverlay(
    state: TourGuideState,
    onFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    pinnedEdgeOverride: TooltipEdge? = null,
    isScreenRoot: Boolean = true,
    // Non-null for a non-screen-root instance embedded in a sheet/dialog: restricts it to
    // ONLY the step id(s) it actually tags itself, e.g. setOf("verify_rx_proceed"). Without
    // this, "does a rect exist for the current step" is the only ownership signal — but
    // [TourGuideState]'s target map is shared window-wide, so a rect registered by some
    // completely different composable in the SAME window (e.g. the camera preview behind a
    // non-modal inline panel) reads as "yes, a rect exists" here too, and this instance would
    // wrongly render a step it has nothing to do with, right on top of its own real content.
    ownedStepIds: Set<String>? = null,
    // Non-null only for a step whose hands-free advance is waiting on real external
    // data the demo environment may not have (e.g. an actual Rx label or bottle to
    // scan) — a "Next" button alongside Skip that lets the caller fake that data and
    // advance immediately, instead of leaving the user stuck with nothing to scan.
    onDummyNext: (() -> Unit)? = null,
) {
    if (!state.isActive) return
    val step = state.currentStep ?: return

    // Whatever dismisses the tour — Skip, Next/Done on the last INFO step, or a real
    // ACTION_TAP action silently completing the last step (no button, no explicit
    // onFinished() call anywhere) — this fires exactly once, right when this instance's
    // active content is torn down because isActive flipped to false. Reading isActive
    // *inside* onDispose (rather than relying on the call site) distinguishes "the tour
    // ended" from "this composable was merely disposed for an unrelated reason (e.g.
    // navigating away) while the tour is still active" — only the former should mark it seen.
    DisposableEffect(state) {
        onDispose { if (!state.isActive) onFinished() }
    }

    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // This overlay's own top-left, in the SAME root-relative coordinate space as
    // [Rect]s pulled from [TourGuideState] (which are captured via `boundsInRoot()`).
    // A `pointerInput` block only ever sees LOCAL positions (relative to the Box the
    // modifier is attached to) — comparing those directly against a root-relative rect is
    // only correct when this Box happens to sit exactly at its window's (0,0), which isn't
    // true once it's nested inside a ModalBottomSheet/Dialog (or any padded ancestor). Every
    // position read out of the gesture below is converted to root-relative with this offset
    // before it's compared against anything.
    var boxOffsetInRoot by remember { mutableStateOf(Offset.Zero) }
    var tooltipRect by remember { mutableStateOf<Rect?>(null) }
    val accentColor = MaterialTheme.colorScheme.primary

    val rawRect = state.currentTargetRect
    val padPx = with(density) { SpotlightPadding.toPx() }
    val cornerPx = with(density) { SpotlightCornerRadius.toPx() }
    val paddedRect = rawRect?.let {
        Rect(it.left - padPx, it.top - padPx, it.right + padPx, it.bottom + padPx)
    }
    val effectivePinnedEdge = pinnedEdgeOverride ?: step.pinnedEdge

    // An ACTION_TAP step's target can live in a *different* window from this overlay
    // instance (e.g. a step gating a button inside a ModalBottomSheet, while this
    // instance sits at the screen root) — its bounds will then never register here. For
    // ACTION_TAP specifically, render nothing at all rather than paint a stray
    // full-screen scrim/tap-block over content the *correct* overlay instance (in that
    // other window) is already gating properly.
    //
    // ACTION_WAIT gets the same no-target fallback as INFO/pinnedEdge: it blocks
    // nothing, so there's no multi-window tap-gating conflict to avoid, and its target
    // living on a screen with its own continuously-recomposing subtree (e.g. a live
    // camera preview re-registering bounds every frame) can leave paddedRect
    // transiently — or, if [tourTarget]'s onGloballyPositioned genuinely never fires
    // for some layout edge case, permanently — null. Without this, a step like
    // "count_add_circle" (ACTION_WAIT, no pinnedEdge) would render nothing at all and
    // never recover, even though its real target is right there on screen.
    val ownsCurrentStep = if (ownedStepIds != null) {
        step.id in ownedStepIds
    } else {
        paddedRect != null ||
            (isScreenRoot && (step.mode != TourStepMode.ACTION_TAP || effectivePinnedEdge != null))
    }
    if (!ownsCurrentStep) return

    val interactionModifier = when (step.mode) {
        TourStepMode.INFO -> Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
        )
        TourStepMode.ACTION_TAP -> {
            // Read these through rememberUpdatedState, NOT as pointerInput keys: the target
            // rect, tooltip rect and root offset all keep re-measuring for a frame or two
            // while a screen first settles (images/data loading, the tooltip's own position
            // adjusting to the just-measured container). Keying pointerInput on them would
            // cancel-and-restart this whole gesture detector on every such wobble — and if
            // that happens between a real down and up (a tap already in flight), the
            // restarted detector never sees that down (awaitFirstDown only fires on a NEW
            // press), silently dropping the tap. The user's own tap still often reaches the
            // real control underneath (a separate, unaffected node), so it looked like it
            // took two taps: the first do nothing, the second — after things had settled —
            // work. Keying only on step.id (which never changes mid-gesture) avoids this
            // entirely, while these three still read their latest value every gesture.
            val latestRect by rememberUpdatedState(paddedRect)
            val latestTooltip by rememberUpdatedState(tooltipRect)
            val latestOffset by rememberUpdatedState(boxOffsetInRoot)
            Modifier.pointerInput(step.id) {
            awaitEachGesture {
                // Decide block-or-pass-through as early as possible (Initial pass, before
                // any descendant's default gesture detector — which requires an
                // unconsumed down — gets a look at it).
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val rect = latestRect
                val tooltip = latestTooltip
                val downInRoot = down.position + latestOffset
                when {
                    rect != null && rect.contains(downInRoot) -> {
                        // Inside the target — never consume, so the real control underneath
                        // processes this gesture completely normally. Advance right away,
                        // on the down, rather than waiting to observe a release: the real
                        // control's own action (e.g. navigating to another screen) can run
                        // and tear this whole composable down as part of the very same
                        // gesture, before a later "wait for the up, then advance" ever gets
                        // to execute — which silently ate the first tap (the tour never
                        // advanced, so the next screen's overlay had no matching step and
                        // showed nothing) and made it look like it took two taps to work.
                        state.advanceIfCurrent(step.id)
                    }
                    tooltip != null && tooltip.contains(downInRoot) -> {
                        // Inside the tooltip card itself (e.g. its Skip button) — never
                        // consume; let the card's own child handle it on the normal pass.
                    }
                    rect == null -> {
                        // The target hasn't registered its bounds — either it hasn't
                        // mounted yet, or (a real target living in a different window,
                        // e.g. a ModalBottomSheet) it never will from this overlay
                        // instance. Either way, blocking here would leave the user with
                        // nothing tappable and no way out — pass everything through.
                    }
                    else -> {
                        // Outside the one allowed target and the tooltip — block the
                        // whole gesture so nothing underneath reacts to it.
                        down.consume()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
            }
        }
        TourStepMode.ACTION_WAIT -> Modifier
    }

    Box(
        modifier = modifier
            .onGloballyPositioned {
                containerSize = it.size
                boxOffsetInRoot = it.positionInRoot()
            }
            .then(interactionModifier),
    ) {
        // Drawing (Canvas) and offset placement below all happen in coordinates LOCAL to
        // this Box, unlike the root-relative Rects from TourGuideState — convert once here.
        val localRect = paddedRect?.let {
            Rect(
                it.left - boxOffsetInRoot.x, it.top - boxOffsetInRoot.y,
                it.right - boxOffsetInRoot.x, it.bottom - boxOffsetInRoot.y,
            )
        }

        // Scrim with a cutout hole around the target — drawn offscreen so BlendMode.Clear
        // punches through this layer only, not the content underneath. Skipped for a
        // targetless ACTION_WAIT step (e.g. "select_bucket", explaining a real system
        // AlertDialog we deliberately don't tag): that dialog draws in its own Android
        // Window, always on top of anything this Compose-level scrim paints, so dimming
        // everything else just makes the (undimmable) dialog look oddly "hot" against a
        // darkened background for no benefit — the dialog already dims its own background.
        val skipScrim = step.mode == TourStepMode.ACTION_WAIT && localRect == null
        if (!skipScrim) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
            ) {
                drawRect(color = Color.Black.copy(alpha = 0.68f))
                if (localRect != null) {
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(localRect.left, localRect.top),
                        size = Size(localRect.width, localRect.height),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        blendMode = BlendMode.Clear,
                    )
                }
            }
        }

        if (localRect != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(localRect.left, localRect.top),
                    size = Size(localRect.width, localRect.height),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = with(density) { 1.dp.toPx() }),
                )
            }
        }

        val tooltipWidthPx = with(density) { TooltipWidth.toPx() }
        val marginPx = with(density) { TooltipMargin.toPx() }
        val estimatedHeightPx = with(density) { EstimatedTooltipHeight.toPx() }
        // Landscape targets (the KPI column, tab strip, etc.) sit mid-height with little
        // room above or below — stacking the tooltip there would cover the very thing it's
        // pointing at. Prefer a side (left/right) placement, vertically centred on the
        // target, whenever the screen is wider than it is tall.
        val isLandscape = containerSize.width > containerSize.height

        val tooltipX: Float
        val tooltipY: Float
        if (effectivePinnedEdge != null) {
            tooltipX = ((containerSize.width - tooltipWidthPx) / 2f)
                .coerceIn(marginPx, (containerSize.width - tooltipWidthPx - marginPx).coerceAtLeast(marginPx))
            tooltipY = when (effectivePinnedEdge) {
                TooltipEdge.TOP -> marginPx
                TooltipEdge.BOTTOM -> {
                    val extraClearancePx = with(density) { PinnedBottomExtraClearance.toPx() }
                    (containerSize.height - estimatedHeightPx - marginPx - extraClearancePx).coerceAtLeast(marginPx)
                }
            }
        } else if (localRect == null) {
            tooltipX = (containerSize.width - tooltipWidthPx) / 2f
            tooltipY = (containerSize.height - estimatedHeightPx) / 2f
        } else if (isLandscape) {
            val roomRight = containerSize.width - localRect.right
            val roomLeft = localRect.left
            val spaceRight = roomRight >= (tooltipWidthPx + marginPx)
            val spaceLeft = roomLeft >= (tooltipWidthPx + marginPx)
            tooltipX = when {
                spaceRight -> localRect.right + marginPx
                spaceLeft -> localRect.left - marginPx - tooltipWidthPx
                // Neither side has full clearance — push to whichever edge has more room
                // instead of centring, which would land the tooltip right back on the
                // target it's supposed to stay clear of.
                roomRight >= roomLeft -> (containerSize.width - tooltipWidthPx - marginPx).coerceAtLeast(marginPx)
                else -> marginPx
            }
            tooltipY = ((localRect.top + localRect.bottom) / 2f - estimatedHeightPx / 2f)
                .coerceIn(marginPx, (containerSize.height - estimatedHeightPx - marginPx).coerceAtLeast(marginPx))
        } else {
            tooltipX = ((containerSize.width - tooltipWidthPx) / 2f)
                .coerceIn(marginPx, (containerSize.width - tooltipWidthPx - marginPx).coerceAtLeast(marginPx))
            val spaceBelow = (containerSize.height - localRect.bottom) >= (estimatedHeightPx + marginPx)
            tooltipY = if (spaceBelow) {
                localRect.bottom + marginPx
            } else {
                (localRect.top - marginPx - estimatedHeightPx).coerceAtLeast(marginPx)
            }
        }

        Card(
            modifier = Modifier
                .offset { IntOffset(tooltipX.roundToInt(), tooltipY.roundToInt()) }
                .width(TooltipWidth)
                .onGloballyPositioned { tooltipRect = it.boundsInRoot() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = extendedColors.secondaryBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(22.dp)
                            .background(accentColor, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (state.currentIndex + 1).toString(),
                            color = extendedColors.primaryBackground,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = stringResource(step.titleRes),
                        color = extendedColors.textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = stringResource(step.descriptionRes),
                    color = extendedColors.textColor,
                    fontSize = 12.5.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(extendedColors.primaryBackground, RoundedCornerShape(2.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((state.currentIndex + 1) / state.steps.size.toFloat())
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )
                }
                val compactPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.tour_step_of, state.currentIndex + 1, state.steps.size),
                        color = extendedColors.textColor,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { state.dismiss(); onFinished() },
                        contentPadding = compactPadding,
                    ) {
                        Text(
                            text = stringResource(R.string.tour_skip),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Escape hatch for a hands-free step blocked on real external data
                    // the demo environment may not have (a real Rx label, a real bottle
                    // to scan) — fakes that data via the caller-supplied callback and
                    // advances immediately, same as the real thing succeeding would.
                    if (onDummyNext != null) {
                        TextButton(
                            onClick = onDummyNext,
                            contentPadding = compactPadding,
                        ) {
                            Text(
                                text = stringResource(R.string.tour_next),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    // Action steps advance from the real tap/callback, not a synthetic
                    // button — only Skip is offered alongside Back/Next for INFO steps.
                    if (step.mode == TourStepMode.INFO) {
                        if (state.currentIndex > 0) {
                            TextButton(
                                onClick = { state.back() },
                                contentPadding = compactPadding,
                            ) {
                                Text(
                                    text = stringResource(R.string.tour_back),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                if (state.isLastStep) {
                                    state.dismiss()
                                    onFinished()
                                } else {
                                    state.next()
                                }
                            },
                            contentPadding = compactPadding,
                        ) {
                            Text(
                                text = if (state.isLastStep) stringResource(R.string.tour_done) else stringResource(R.string.tour_next),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
