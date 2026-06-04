package com.rite.pillcounting.core.utils.compose

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.ui.theme.AppTheme

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun ContainerStatusSlider(
    selectedStatus: ContainerStatus,
    onStatusSelected: (ContainerStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        val totalWidth = maxWidth
        val thumbWidth = totalWidth / 2
        val targetOffset = if (selectedStatus == ContainerStatus.SEALED) 0.dp else thumbWidth
        val animatedOffset by animateDpAsState(targetValue = targetOffset, label = "sliderOffset")

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .offset(x = animatedOffset)
                    .width(thumbWidth)
                    .height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondary)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusItem(
                    text = stringResource(R.string.container_status_sealed),
                    isSelected = selectedStatus == ContainerStatus.SEALED,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatusSelected(ContainerStatus.SEALED) }
                )

                StatusItem(
                    text = stringResource(R.string.container_status_opened),
                    isSelected = selectedStatus == ContainerStatus.OPENED,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatusSelected(ContainerStatus.OPENED) }
                )
            }
        }
    }
}

@Composable
private fun StatusItem(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun InfoField(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.extendedColors.secondaryBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Cap the value at 2 lines with ellipsis. Without this, narrow drawer
        // widths force long drug names (e.g. "Tramadol Hydrochloride") to wrap
        // onto 3+ lines, with character-level breaks mid-word
        // ("Hydrochlor / ide"). Two lines is enough for almost every real value
        // and keeps the row height predictable so the slider/buttons stay
        // visible below.
        Text(
            text = value,
            color = AppTheme.extendedColors.textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BucketDropdownField(
    bucketList: List<String>,
    selectedBucket: String,
    onBucketSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedBucket,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = AppTheme.extendedColors.textColor
            ),
            shape = RoundedCornerShape(10.dp),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                unfocusedBorderColor = AppTheme.extendedColors.secondaryBackground,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = AppTheme.extendedColors.secondaryBackground,
                focusedContainerColor = AppTheme.extendedColors.secondaryBackground,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Dropdown"
                )
            }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            bucketList.forEach { bucket ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = bucket,
                            color = AppTheme.extendedColors.textColor
                        )
                    },
                    onClick = {
                        onBucketSelected(bucket)
                        expanded = false
                    }
                )
            }
        }
    }
}