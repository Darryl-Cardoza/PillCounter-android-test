package com.rite.pillcounting.feature.settings.presentation.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.utils.constants.LocalDimens
import com.rite.pillcounting.ui.theme.LocalExtendedColors

/** Alpha for a dimmed (disabled) setting's text. */
const val SETTINGS_DISABLED_ALPHA = 0.4f

/**
 * One Settings row: label, optional trailing [value], optional trailing switch when
 * [checked] is set. A disabled row is dimmed and a tap goes to [onDisabledClick] instead.
 */
@Composable
fun SettingsRow(
    @StringRes labelRes: Int,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    value: (@Composable () -> Unit)? = null,
) {
    val extendedColors = LocalExtendedColors.current
    val dimens = LocalDimens.current

    val textColor =
        if (enabled) extendedColors.textColor else extendedColors.textColor.copy(alpha = SETTINGS_DISABLED_ALPHA)
    val rowClick = if (enabled) onClick else onDisabledClick

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rowClick != null) Modifier.clickable { rowClick() } else Modifier)
            .padding(
                start = dimens.pagePadding + dimens.small,
                end = dimens.pagePadding,
                top = dimens.settingRowVerticalPadding,
                bottom = dimens.settingRowVerticalPadding
            )
    ) {
        Text(
            text = stringResource(labelRes),
            fontSize = 16.sp,
            color = textColor,
            modifier = Modifier.weight(1f)
        )

        if (value != null) {
            Spacer(Modifier.width(12.dp))
            value()
        }

        if (checked != null) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = Color.White,
                    uncheckedBorderColor = Color.Transparent,
                    checkedBorderColor = Color.Transparent
                ),
                thumbContent = null
            )
        }
    }
}

/** Pink value shown at the end of a row, e.g. the chosen history period. */
@Composable
fun SettingsValueText(text: String, enabled: Boolean = true) {
    val color = MaterialTheme.colorScheme.secondary
    Text(
        text = text,
        fontSize = 14.sp,
        color = if (enabled) color else color.copy(alpha = SETTINGS_DISABLED_ALPHA)
    )
}
