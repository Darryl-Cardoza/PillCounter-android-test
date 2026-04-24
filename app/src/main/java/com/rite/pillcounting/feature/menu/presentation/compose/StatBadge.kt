package com.rite.pillcounting.feature.menu.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small pill-shaped badge that displays an icon and label text,
 * typically used for showing transaction status (e.g., "Completed", "Partial").
 *
 * Layout:
 * ```
 * [Icon] [Text]
 * ```
 *
 * - When [hasBackground] is `true`, the badge is styled with a rounded rectangle background.
 * - When [hasBackground] is `false`, only text and icon are rendered inline with minimal padding.
 *
 * Example:
 * ```
 * StatBadge(
 *     text = "21 completed",
 *     tint = MaterialTheme.colorScheme.primary,
 *     icon = R.drawable.tick,
 *     hasBackground = false
 * )
 * ```
 *
 * @param text The label text to display inside the badge.
 * @param tint The color applied to both the icon and text.
 * @param icon Resource ID of the icon to display.
 * @param hasBackground Whether to show a rounded background behind the badge.
 */
@Composable
fun StatBadge(
    text: String,
    tint: Color,
    icon: Int,
    hasBackground: Boolean,
    onBadgeClick: () -> Unit
) {
    val modifier = if (hasBackground) {
        Modifier
            .height(40.dp)
            .width(140.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 8.dp)
            .clickable(
                onClick = onBadgeClick,
                indication = null, // disables ripple
                interactionSource = remember { MutableInteractionSource() })
    } else {
        Modifier.padding(horizontal = 4.dp)
            .clickable(onClick = onBadgeClick)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null, // Decorative only
            tint = tint,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = text,
            fontSize = 14.sp,
            color = tint,
            maxLines = 1
        )
    }
}
