package com.rite.pillcounting.core.tourguide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

// A TOP-pinned tooltip (the targetless-ACTION_WAIT "explaining a real native dialog" case —
// select_bucket, end_count_confirm) would otherwise sit flush against the screen's own top
// edge, covering the real app header (pharmacy name, PMS status, hamburger menu) underneath
// it. This pushes it down clear of that zone. It also happens to keep the tooltip clear of
// the real dialog itself, which is vertically centered lower on screen.
private val PinnedTopExtraClearance = 140.dp

/**
 * Full-screen spotlight tour: dims everything except [TourGuideState]'s current target
 * (cut out of the scrim via [BlendMode.Clear]), rings it, and shows a tooltip. Place this
 * as the last child of the screen's root [Box] so it draws above the content it points at.
 *
 * Behavior branches on the current step's [TourStepMode]:
 * - [TourStepMode.INFO]: blocks every tap, tooltip has Skip/Back/Next.
 * - [TourStepMode.ACTION_TAP]: blocks every tap, same as INFO — the highlighted/spotlighted
 *   target itself is never tappable. Progress comes only from the tour card's own Next
 *   button (see [onDummyNext]), which every ACTION_TAP step wires to perform the exact real
 *   action the target would have. (Earlier this let a tap inside the target pass through
 *   unconsumed to the real control underneath; that's been removed in favor of guiding the
 *   user through the tour card exclusively, matching the Dashboard's INFO-step behavior.)
 * - [TourStepMode.ACTION_WAIT]: blocks nothing (except the two targetless "explaining a real
 *   dialog" steps, select_bucket/end_count_confirm, which block everything exactly like
 *   INFO/ACTION_TAP — see `skipScrim` below); pure visual spotlight otherwise, needed for
 *   live camera interaction. Advancing is driven externally via
 *   [TourGuideState.advanceIfCurrent].
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
    // [Rect]s pulled from [TourGuideState] (which are captured via `boundsInRoot()`) —
    // this Box only sits exactly at its window's (0,0) at the screen root; nested
    // inside a ModalBottomSheet/Dialog (or any padded ancestor) it doesn't, so the
    // root-relative target rect has to be converted to LOCAL coordinates with this
    // offset before it's used for drawing below.
    var boxOffsetInRoot by remember { mutableStateOf(Offset.Zero) }
    val accentColor = MaterialTheme.colorScheme.primary

    val rawRect = state.currentTargetRect
    val padPx = with(density) { SpotlightPadding.toPx() }
    val cornerPx = with(density) { SpotlightCornerRadius.toPx() }
    val paddedRect = rawRect?.let {
        Rect(it.left - padPx, it.top - padPx, it.right + padPx, it.bottom + padPx)
    }
    val effectivePinnedEdge = pinnedEdgeOverride ?: step.pinnedEdge

    // A targetless ACTION_WAIT step (select_bucket, end_count_confirm — explaining a
    // real native AlertDialog/Dialog we deliberately don't tag/gate) has no camera or
    // other live interaction to preserve, unlike every OTHER ACTION_WAIT step (which
    // all DO have a real target once mounted, so this is false for them). Block the
    // whole screen here exactly like an INFO step does — the real dialog is a
    // completely separate Android Window, so this can never interfere with it, only
    // with taps on THIS screen's own content (Quick Action cards, KPI cards, the
    // hamburger menu) that would otherwise let the user wander off the guided flow
    // while the tutorial is still active.
    val skipScrim = step.mode == TourStepMode.ACTION_WAIT && paddedRect == null

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
        // Full block, same as INFO — the highlighted/spotlighted target is NOT
        // tappable. Every ACTION_TAP step already has a working "Next" button (see
        // onDummyNext below) that performs the exact real action the target would
        // have, so the user is never trapped: progress comes only from the tour
        // card itself (Skip or Next), never from tapping the real UI underneath —
        // matching the Dashboard's INFO-step behavior, extended to the Dispense and
        // Stock Count flows. (Previously this let a tap inside the target pass
        // through unconsumed to the real control; that whole mechanism — and the
        // double-tap race it had to work around — no longer applies now that the
        // target is never tappable at all.)
        TourStepMode.ACTION_TAP -> Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
        )
        TourStepMode.ACTION_WAIT -> if (skipScrim) {
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
        } else {
            Modifier
        }
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
        // targetless ACTION_WAIT step (skipScrim, computed above): the real dialog it's
        // explaining draws in its own Android Window, always on top of anything this
        // Compose-level scrim paints, so dimming everything else just makes the
        // (undimmable) dialog look oddly "hot" against a darkened background for no
        // benefit — the dialog already dims its own background. The screen behind it is
        // still fully tap-blocked regardless (interactionModifier above), just not dimmed.
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
                TooltipEdge.TOP -> {
                    val extraClearancePx = with(density) { PinnedTopExtraClearance.toPx() }
                    marginPx + extraClearancePx
                }
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
                .width(TooltipWidth),
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
                    // skipScrim: this tooltip is explaining a real native AlertDialog/
                    // Dialog that's also open right now. That dialog's own window
                    // captures touches across the WHOLE screen for its own
                    // outside-tap handling (even with onDismissRequest a no-op) —
                    // regardless of whether it visually overlaps this tooltip, which
                    // is rendered via Popup specifically so it isn't torn down by the
                    // scrim, but still can't win a touch race against the dialog's own
                    // window. Buttons here would look tappable but silently never
                    // respond — so instead of Skip/Next, the real dialog's own
                    // buttons drive the tour: OK/Yes calls advanceIfCurrent, Cancel/No
                    // calls dismiss() (wired at each call site — DashboardScreen.kt's
                    // Select Bucket, InventoryScanHost.kt's end-count confirmation).
                    if (!skipScrim) {
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
                        // Escape hatch for a hands-free step blocked on real external
                        // data the demo environment may not have (a real Rx label, a
                        // real bottle to scan) — fakes that data via the
                        // caller-supplied callback and advances immediately, same as
                        // the real thing succeeding would.
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

/**
 * Invisible full-screen tap sink — no scrim, no tooltip, just swallows every touch.
 *
 * For a step that explains a real, untagged dialog (select_bucket, end_count_confirm),
 * the caller suppresses [TourGuideOverlay] entirely in the brief window where the tour
 * is already sitting on that step but the real dialog isn't up yet (or just closed) —
 * rendering its own tooltip there would be misleading (pointing at a dialog that isn't
 * on screen). But rendering *nothing at all* also leaves the whole real screen behind
 * it — Quick Action cards, KPI cards, the hamburger menu — freely tappable during an
 * active tutorial, an "outside tap" escape hatch out of the guided flow. Place this in
 * that same suppressed branch instead of nothing, so the screen stays locked to
 * everything except the dialog that's about to appear.
 */
@Composable
fun TourGuideTapBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    )
}
