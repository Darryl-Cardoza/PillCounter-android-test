package com.rite.pillcounting.feature.menu.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.constants.LocalDimens
import com.rite.pillcounting.ui.theme.AppTheme.extendedColors

/**
 * A reusable row layout for displaying a menu item with optional statistics.
 *
 * Layout:
 * ```
 * [Icon] Title [Stats (Completed/Partial)]
 * ```
 *
 * - In **portrait mode**, stats appear in a separate row beneath the title.
 * - In **landscape mode**, stats appear inline on the right side.
 *
 * Typical use cases:
 * - Displaying "Fixed Count" or "Regular Count" in the app menu with badges
 *   showing how many counts are completed or partial.
 *
 * Example:
 * ```
 * MenuItemRow(
 *     icon = R.drawable.fixed_count,
 *     title = stringResource(R.string.fixed_count),
 *     completed = "21 completed",
 *     partial = "10 partial",
 *     iconTint = MaterialTheme.colorScheme.secondary,
 *     completedTint = MaterialTheme.colorScheme.secondary,
 *     partialTint = MaterialTheme.colorScheme.secondary,
 *     completedIcon = R.drawable.tick,
 *     partialIcon = R.drawable.partial
 * )
 * ```
 *
 * @param icon Resource ID of the leading icon.
 * @param title The main label text for this menu item.
 * @param completed Optional text to show for completed counts (e.g., `"21 completed"`).
 * @param partial Optional text to show for partial counts (e.g., `"10 partial"`).
 * @param iconTint Color applied to the leading icon.
 * @param completedTint Color applied to the completed badge.
 * @param partialTint Color applied to the partial badge.
 * @param completedIcon Resource ID of the icon shown in the completed badge.
 * @param partialIcon Resource ID of the icon shown in the partial badge.
 */
@Composable
fun MenuItemRow(
    icon: Int,
    title: String,
    completed: String? = null,
    partial: String? = null,
    iconTint: Color,
    completedTint: Color,
    partialTint: Color,
    completedIcon: Int,
    partialIcon: Int,
    mainClick: () -> Unit,
    onPartialClick: () -> Unit,
    onCompletedClick: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = dimens.menuRowVerticalPadding, bottom = dimens.menuRowVerticalPadding)
            .clickable(onClick = mainClick)
    ) {
        Spacer(modifier = Modifier.height(dimens.menuRowSpacing))

        // ───────────────── Row: Icon + Title (and stats in landscape) ─────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(responsiveDp(28.dp))
            )

            Spacer(modifier = Modifier.width(responsiveDp(12.dp)))

            Text(
                text = title,
                fontSize = 16.sp,
                color = extendedColors.textColor
            )

            // Landscape: show stats inline to the right
            if (!isPortrait && completed != null && partial != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    StatBadge(
                        text = completed,
                        tint = completedTint,
                        icon = completedIcon,
                        hasBackground = true,
                        onBadgeClick = {onCompletedClick()},
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatBadge(
                        text = partial,
                        tint = partialTint,
                        icon = partialIcon,
                        hasBackground = true,
                        onBadgeClick = { onPartialClick() },
                    )
                }
            }
        }

        // Portrait: show stats in a separate row below the title
        if (isPortrait && completed != null && partial != null) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatBadge(
                    text = completed,
                    tint = completedTint,
                    icon = completedIcon,
                    hasBackground = true,
                    onBadgeClick = onCompletedClick,
                )
                StatBadge(
                    text = partial,
                    tint = partialTint,
                    icon = partialIcon,
                    hasBackground = true,
                    onBadgeClick = onPartialClick,
                )
            }
        }

        Spacer(modifier = Modifier.height(dimens.menuRowSpacing))
    }
}
