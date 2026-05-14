package com.rite.pillcounting.feature.dashboard.presentation.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp

/**
 * A reusable chip-style component for displaying status indicators on the Dashboard.
 *
 * Typical usage:
 * - Completed/Partial counts with icons.
 * - Colored pill-shaped chips to match the app's theme.
 *
 * @param text The label displayed inside the chip (e.g., "5 Completed").
 * @param backgroundColor Background color of the chip container.
 * @param textColor Color applied to the text.
 * @param iconRes Drawable resource ID for the leading icon.
 * @param iconTint Optional tint color applied to the icon (null = no tint).
 * @param width Optional fixed width; scaled via [responsiveDp] for phone/tablet. Null = wrap content.
 */
@Composable
fun StatusChip(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    iconRes: Int,
    iconTint: Color? = null,
    width: Dp? = null
) {
    val widthModifier = if (width != null)
        Modifier.width(responsiveDp(width))
    else
        Modifier.fillMaxWidth()

    Row(
        modifier = widthModifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(percent = 50) // pill shape
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.width(responsiveDp(8.dp)))

        // Leading icon
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(25.dp),
            colorFilter = iconTint?.let { ColorFilter.tint(it) }
        )

        Spacer(modifier = Modifier.width(responsiveDp(8.dp)))

        // Status text
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1
        )

        Spacer(modifier = Modifier.width(responsiveDp(8.dp)))
    }
}
