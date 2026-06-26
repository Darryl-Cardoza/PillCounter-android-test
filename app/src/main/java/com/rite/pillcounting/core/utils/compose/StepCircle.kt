package com.rite.pillcounting.core.utils.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.models.StepStatus
import com.rite.pillcounting.core.models.icon
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun StepCircle(
    step: StepState,
    state: StepStatus,
    title: String = "",
    showTooltip: Boolean = false,
    circleSize: Dp = 35.dp,
    onClick: () -> Unit = {}
) {

    val backgroundColor = when (state) {
        StepStatus.DONE -> AppTheme.extendedColors.secondaryBackground
        StepStatus.ACTIVE -> AppTheme.extendedColors.secondaryBackground
        StepStatus.PENDING -> AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.4f)
    }

    // Icon scales with the circle (matches the original 16:35 ratio).
    val iconSize = circleSize * (16f / 35f)
    Box(
        modifier = Modifier
            .size(responsiveDp(circleSize))
            .clip(CircleShape)
            .background(backgroundColor, CircleShape)
            .clickable { onClick() }
            .then(
                if (state == StepStatus.ACTIVE)
                    Modifier.border(1.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = step.icon()),
            contentDescription = step.name,
            tint = AppTheme.extendedColors.textColor,
            modifier = Modifier.size(responsiveDp(iconSize))
        )

        // Speech-bubble tooltip shown above the icon when this step is tapped.
        // Rendered in a Popup so it escapes the bottom bar's bounds/clipping and
        // can float over the camera feed.
        if (showTooltip && title.isNotBlank()) {
            StepTooltipPopup(title = title)
        }
    }
}

/**
 * A rounded speech bubble with a downward-pointing tail, anchored just above the
 * step icon it is attached to (the [Popup] anchor is the icon's [Box]).
 */
@Composable
private fun StepTooltipPopup(title: String) {
    val bubbleColor = AppTheme.extendedColors.secondaryBackground
    val textColor = AppTheme.extendedColors.textColor

    val positionProvider = remember { AbovePopupPositionProvider() }

    Popup(
        popupPositionProvider = positionProvider,
        // Non-focusable so tapping the bubble doesn't steal focus / dismiss oddly.
        // Dismissal is driven by the auto-hide timer in WorkflowStepper.
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = title,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Downward triangle tail pointing at the icon below.
            Canvas(modifier = Modifier.size(width = 16.dp, height = 8.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path = path, color = bubbleColor)
            }
            // Small gap between the tail tip and the icon.
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Positions the popup centred horizontally over its anchor and fully above it
 * (its bottom edge meets the anchor's top edge), clamped into the window so the
 * bubble never runs off the left/right screen edge.
 */
private class AbovePopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = anchorBounds.top - popupContentSize.height
        return IntOffset(x, y)
    }
}
