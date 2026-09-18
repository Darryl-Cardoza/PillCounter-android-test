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

/**
 * Full-screen spotlight tour: dims everything except [TourGuideState]'s current target
 * (cut out of the scrim via [BlendMode.Clear]), rings it, and shows a tooltip with
 * Skip/Back/Next controls. Place this as the last child of the screen's root [Box] so
 * it draws above the content it is pointing at.
 */
@Composable
fun TourGuideOverlay(state: TourGuideState, onFinished: () -> Unit = {}, modifier: Modifier = Modifier) {
    if (!state.isActive) return
    val step = state.currentStep ?: return

    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val accentColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerSize = it.size }
            // Swallow every tap on the scrim so the dashboard underneath can't be
            // navigated away from while the tour is up. The tooltip's own Skip/Back/Next
            // buttons are descendants that consume their taps first, so they still work.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        val rawRect = state.currentTargetRect
        val padPx = with(density) { SpotlightPadding.toPx() }
        val cornerPx = with(density) { SpotlightCornerRadius.toPx() }
        val paddedRect = rawRect?.let {
            Rect(it.left - padPx, it.top - padPx, it.right + padPx, it.bottom + padPx)
        }

        // Scrim with a cutout hole around the target — drawn offscreen so BlendMode.Clear
        // punches through this layer only, not the content underneath.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            drawRect(color = Color.Black.copy(alpha = 0.68f))
            if (paddedRect != null) {
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(paddedRect.left, paddedRect.top),
                    size = Size(paddedRect.width, paddedRect.height),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    blendMode = BlendMode.Clear,
                )
            }
        }

        if (paddedRect != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(paddedRect.left, paddedRect.top),
                    size = Size(paddedRect.width, paddedRect.height),
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
        if (paddedRect == null) {
            tooltipX = (containerSize.width - tooltipWidthPx) / 2f
            tooltipY = (containerSize.height - estimatedHeightPx) / 2f
        } else if (isLandscape) {
            val roomRight = containerSize.width - paddedRect.right
            val roomLeft = paddedRect.left
            val spaceRight = roomRight >= (tooltipWidthPx + marginPx)
            val spaceLeft = roomLeft >= (tooltipWidthPx + marginPx)
            tooltipX = when {
                spaceRight -> paddedRect.right + marginPx
                spaceLeft -> paddedRect.left - marginPx - tooltipWidthPx
                // Neither side has full clearance — push to whichever edge has more room
                // instead of centring, which would land the tooltip right back on the
                // target it's supposed to stay clear of.
                roomRight >= roomLeft -> (containerSize.width - tooltipWidthPx - marginPx).coerceAtLeast(marginPx)
                else -> marginPx
            }
            tooltipY = ((paddedRect.top + paddedRect.bottom) / 2f - estimatedHeightPx / 2f)
                .coerceIn(marginPx, (containerSize.height - estimatedHeightPx - marginPx).coerceAtLeast(marginPx))
        } else {
            tooltipX = ((containerSize.width - tooltipWidthPx) / 2f)
                .coerceIn(marginPx, (containerSize.width - tooltipWidthPx - marginPx).coerceAtLeast(marginPx))
            val spaceBelow = (containerSize.height - paddedRect.bottom) >= (estimatedHeightPx + marginPx)
            tooltipY = if (spaceBelow) {
                paddedRect.bottom + marginPx
            } else {
                (paddedRect.top - marginPx - estimatedHeightPx).coerceAtLeast(marginPx)
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
                        text = step.title,
                        color = extendedColors.textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = step.description,
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
