package com.dispensesure.retail.core.tourguide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
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

// A TOP-pinned tooltip would otherwise sit flush against the screen's own top edge, covering
// the real app header (pharmacy name, PMS status, hamburger menu). This pushes it down clear.
private val PinnedTopExtraClearance = 140.dp

/**
 * Full-screen spotlight tour: dims everything except the current step's target and shows
 * a tooltip; place as the last child of the screen's root [Box]. Every step blocks all taps on
 * the screen — progress comes only from the tour card's Next / Skip buttons. [onNext] is the
 * demo action Next performs for this step (null = just advance); it runs through
 * [TourGuideState.runStep], so a double tap can't run it twice.
 */
@Composable
fun TourGuideOverlay(
    state: TourGuideState,
    modifier: Modifier = Modifier,
    pinnedEdgeOverride: TooltipEdge? = null,
    isScreenRoot: Boolean = true,
    // Restricts this instance to the step id(s) it owns; without it, any rect existing in
    // [TourGuideState]'s shared window-wide target map reads as ownership, even when a
    // different composable in another window registered it.
    ownedStepIds: Set<String>? = null,
    // False inside a Dialog window, where the host decides what Back does.
    handleBack: Boolean = true,
    onNext: (suspend () -> Unit)? = null,
) {
    if (!state.isActive || state.pendingSection != null) return
    val step = state.currentStep ?: return

    val context = LocalContext.current
    // Only failures that happen while this overlay is up count; older ones were already shown.
    val seenFailures = remember { state.failureTick }
    if (handleBack) BackHandler(enabled = true) { state.end() }

    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // This overlay's own top-left, in the SAME root-relative coordinate space as
    // [Rect]s pulled from [TourGuideState] (captured via `boundsInRoot()`) — nested inside a
    // ModalBottomSheet/Dialog this Box doesn't sit at its window's (0,0), so the target rect
    // is converted to LOCAL coordinates with this offset before drawing.
    var boxOffsetInRoot by remember { mutableStateOf(Offset.Zero) }
    val accentColor = MaterialTheme.colorScheme.primary

    val rawRect = state.currentTargetRect
    val padPx = with(density) { SpotlightPadding.toPx() }
    val cornerPx = with(density) { SpotlightCornerRadius.toPx() }
    val paddedRect = rawRect?.let {
        Rect(it.left - padPx, it.top - padPx, it.right + padPx, it.bottom + padPx)
    }
    val effectivePinnedEdge = pinnedEdgeOverride ?: step.pinnedEdge

    // A target may live in a different window than this instance; if so, render nothing
    // rather than paint a stray scrim over content the correct instance already covers.
    val ownsCurrentStep = if (ownedStepIds != null) step.id in ownedStepIds else paddedRect != null || isScreenRoot
    if (!ownsCurrentStep) return
    LaunchedEffect(state.failureTick) {
        if (state.failureTick != seenFailures) {
            showToast(context, R.string.tour_step_failed)
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned {
                containerSize = it.size
                boxOffsetInRoot = it.positionInRoot()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        // Drawing (Canvas) and offset placement below all happen in coordinates LOCAL to
        // this Box, unlike the root-relative Rects from TourGuideState — convert once here.
        val toLocal = { it: Rect ->
            Rect(
                it.left - boxOffsetInRoot.x, it.top - boxOffsetInRoot.y,
                it.right - boxOffsetInRoot.x, it.bottom - boxOffsetInRoot.y,
            )
        }
        val localRect = paddedRect?.let(toLocal)
        // What gets cut out: one box around every target, or each target on its own.
        val cutouts = if (step.separateBlocks) {
            state.currentTargetRects.map {
                toLocal(Rect(it.left - padPx, it.top - padPx, it.right + padPx, it.bottom + padPx))
            }
        } else {
            listOfNotNull(localRect)
        }

        run {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
            ) {
                drawRect(color = Color.Black.copy(alpha = 0.68f))
                cutouts.forEach { cut ->
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(cut.left, cut.top),
                        size = Size(cut.width, cut.height),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        blendMode = BlendMode.Clear,
                    )
                }
            }
        }

        if (cutouts.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                cutouts.forEach { cut -> drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(cut.left, cut.top),
                    size = Size(cut.width, cut.height),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = with(density) { 1.dp.toPx() }),
                ) }
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
                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.stepNumber.toString(),
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
                            .fillMaxWidth((state.stepNumber / state.stepTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp)),
                    )
                }
                val compactPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.tour_step_of, state.stepNumber, state.stepTotal),
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { state.end() },
                        enabled = !state.stepBusy,
                        contentPadding = compactPadding,
                    ) {
                        Text(
                            text = stringResource(R.string.tour_skip),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Only the Dashboard section is pure info and lets the user step back;
                    // later sections run real demo actions that can't be undone.
                    if (step.section == TourSection.DASHBOARD && state.canGoBack) {
                        TextButton(
                            onClick = { state.back() },
                            enabled = !state.stepBusy,
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
                        onClick = { state.runStep(step.id) { onNext?.invoke() } },
                        enabled = !state.stepBusy,
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
