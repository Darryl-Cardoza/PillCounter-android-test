package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp

/**
 * A reusable action icon for headers (e.g., export, delete, filter, search).
 *
 * @param iconRes Resource ID of the icon to display.
 * @param contentDescription Accessibility description for the icon.
 * @param onClick Action to perform when the icon is clicked.
 * @param modifier Modifier to apply to the icon (default adds size & padding).
 */
@Composable
fun ActionIcon(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Icon(
        painter = painterResource(id = iconRes),
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(responsiveDp(25.dp))
            .clickable {onClick}
    )
}
