package com.dispensesure.retail.core.tourguide

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
import com.dispensesure.retail.R
import com.dispensesure.retail.ui.theme.AppTheme.extendedColors
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
 * Full-screen spotlight tour: dims everything except the current step's target and shows
 * a tooltip; place as the last child of the screen's root [Box]. ACTION_TAP steps block all
 * taps on the target — progress comes only from the tour card's own Next button.
 */
@Composable
fun TourGuideOverlay(
    state: TourGuideState,
    onFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    pinnedEdgeOverride: TooltipEdge? = null,
    isScreenRoot: Boolean = true,
    // Restricts this instance to the step id(s) it tags itself; without it, any rect
    // existing in [TourGuideState]'s shared window-wide target map reads as ownership,
    // even when a different composable in the same window registered it.
    ownedStepIds: Set<String>? = null,
    // Non-null only for a step whose hands-free advance is waiting on real external
    // data the demo environment may not have (e.g. an actual Rx label or bottle to
    // scan) — a "Next" button alongside Skip that lets the caller fake that data and
    // advance immediately, instead of leaving the user stuck with nothing to scan.
    onDummyNext: (() -> Unit)? = null,
) {
    if (!state.isActive) return
    val step = state.currentStep ?: return

    // Fires exactly once when the tour truly ends (not on unrelated disposal, e.g.
    // navigating away) — reading isActive inside onDispose is what distinguishes the two.
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

    // A targetless ACTION_WAIT step (explaining a real native dialog we don't tag) has no
    // live interaction to preserve, so block the whole screen like INFO — this can't
    // interfere with the dialog itself, which lives in a separate Android Window.
    val skipScrim = step.mode == TourStepMode.ACTION_WAIT && paddedRect == null

    // ACTION_TAP's target may live in a different window than this instance; if so, render
    // nothing rather than paint a stray tap-block over content the correct instance already
    // gates. ACTION_WAIT falls back the same way when its target hasn't registered yet.
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
        // Full block, same as INFO — the target is never tappable. Every ACTION_TAP step
        // has a working Next button that performs the real action instead, so progress
        // never depends on tapping the underlying UI.
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

        // Skipped for a targetless ACTION_WAIT step: the real dialog it explains draws in
        // its own Android Window on top of this scrim regardless and already dims its own
        // background, so dimming here would just look odd. Tap-blocking still applies.
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
                    // skipScrim: the real dialog's window wins any touch race regardless of
                    // visual overlap, so Skip/Next buttons here would look tappable but never
                    // respond — the dialog's own buttons drive the tour instead.
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
 * Invisible full-screen tap sink used while a step's real dialog isn't on screen yet (or
 * just closed) — keeps the screen locked instead of leaving real UI underneath tappable
 * during that gap.
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
