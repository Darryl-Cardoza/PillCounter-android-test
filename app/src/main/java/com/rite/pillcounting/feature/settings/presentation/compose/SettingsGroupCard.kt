package com.rite.pillcounting.feature.settings.presentation.compose

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.constants.LocalDimens
import com.dispensesure.retail.ui.theme.LocalExtendedColors

/**
 * Collapsible Settings group: title + chevron header, then [rows] with a divider above each.
 * The caller owns [expanded] so only one card is open at a time.
 */
@Composable
fun SettingsGroupCard(
    @StringRes titleRes: Int,
    expanded: Boolean,
    onHeaderClick: () -> Unit,
    rows: List<@Composable () -> Unit>,
) {
    val extendedColors = LocalExtendedColors.current
    val dimens = LocalDimens.current

    val chevronDeg by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.settingsCardCornerRadius))
            .background(extendedColors.primaryBackground)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onHeaderClick)
                .padding(vertical = dimens.settingRowVerticalPadding, horizontal = dimens.pagePadding)
        ) {
            Text(
                text = stringResource(titleRes),
                color = extendedColors.textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(responsiveDp(22.dp))
                    .rotate(chevronDeg)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(180))
        ) {
            Column {
                rows.forEach { row ->
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = dimens.pagePadding),
                        color = extendedColors.secondaryBackground.copy(alpha = 0.4f)
                    )
                    row()
                }
            }
        }
    }
}
