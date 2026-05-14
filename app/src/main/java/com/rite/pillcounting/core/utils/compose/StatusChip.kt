package com.rite.pillcounting.core.utils.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Pill-shaped selectable chip used for filter/toggle rows.
 *
 * @param label The chip label text.
 * @param isSelected Whether this chip is currently selected (fills with primary color).
 * @param onClick Called when the chip is tapped.
 * @param count Optional count appended as "(n)" after the label.
 */
@Composable
fun StatusChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    count: Int? = null
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else AppTheme.extendedColors.secondaryBackground
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = if (count != null)
                stringResource(R.string.toggle_with_count, label, count)
            else
                label,
            color = if (isSelected) Color.White else AppTheme.extendedColors.textColor,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp
        )
    }
}
