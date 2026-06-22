package com.rite.pillcounting.feature.menu.presentation.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.constants.LocalDimens
import com.rite.pillcounting.ui.theme.AppTheme.extendedColors

/**
 * A simple reusable row for displaying a menu option in the navigation drawer or menu screen.
 *
 * Layout:
 * ```
 * [Icon] Title [Optional trailing text]
 * ```
 *
 * Features:
 * - **Clickable Row** → invokes [onClick] when tapped.
 * - **Leading Icon** → represents the menu item visually.
 * - **Trailing Text** (optional) → typically used for contextual info like
 *   "3 months" in History or a version label.
 * - If [onClick] is null, the row is **not clickable**.
 *
 * Example:
 * ```
 * SimpleMenuRow(
 *     navController = navController,
 *     icon = R.drawable.settings,
 *     iconTint = MaterialTheme.colorScheme.secondary,
 *     title = stringResource(R.string.settings),
 *     onClick = { navController.navigate(Screen.Settings.route) }
 * )
 * ```
 *
 * @param navController The navigation controller to handle navigation actions.
 * @param icon Drawable resource ID for the leading icon.
 * @param iconTint Color applied to the leading icon.
 * @param title Main text label for the menu item.
 * @param trailingText Optional trailing text (e.g., `"3 months"`).
 * @param onClick Optional action invoked when the row is tapped.
 */
@Composable
fun SimpleMenuRow(
    navController: NavController,
    icon: Int,
    iconTint: Color,
    title: String,
    trailingText: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val dimens = LocalDimens.current

    // When disabled, the row stays tappable (so callers can surface a toast) but
    // is visually dimmed to read as inactive.
    val disabledAlpha = 0.4f
    val resolvedIconTint = if (enabled) iconTint else iconTint.copy(alpha = disabledAlpha)
    val resolvedTextColor =
        if (enabled) extendedColors.textColor else extendedColors.textColor.copy(alpha = disabledAlpha)

    Spacer(modifier = Modifier.height(dimens.menuRowSpacing))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(start = 12.dp, top = dimens.menuRowVerticalPadding, bottom = dimens.menuRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left section: Icon + Title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = title,
                tint = resolvedIconTint,
                modifier = Modifier.size(responsiveDp(28.dp))
            )
            Spacer(modifier = Modifier.width(responsiveDp(12.dp)))
            Text(
                text = title,
                fontSize = 16.sp,
                color = resolvedTextColor
            )
        }

        // Right section: optional trailing text
        trailingText?.let {
            Text(
                text = it,
                fontSize = 16.sp,
                color = if (enabled) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.secondary.copy(alpha = disabledAlpha),
                modifier = Modifier.padding(end = 12.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(dimens.menuRowSpacing))
}
