package com.rite.pillcounting.core.utils.compose

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.ui.theme.AppTheme

data class DialogField(
    val label: String,
    val value: String
)

enum class ContainerStatus {
    SEALED,
    OPENED
}

@Composable
fun LabelScannedSuccessfullyDialog(
    fields: List<DialogField>,
    selectedContainerStatus: ContainerStatus,
    onContainerStatusChange: (ContainerStatus) -> Unit,
    title: String = stringResource(R.string.label_scanned_successfully),
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    modifier: Modifier = Modifier,
    showSealedButtons: Boolean = false,
    bucketList: List<String> = emptyList(),
    showBucketSelector: Boolean = false,
    onBucketSelected: (String) -> Unit = {}
) {
    val visibleFields = fields.filter { it.value.isNotBlank() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val defaultBucket = remember(bucketList) {
        bucketList.firstOrNull().orEmpty()
    }
    var localSelectedBucket by remember { mutableStateOf(defaultBucket) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = modifier.fillMaxWidth(if (isLandscape) 0.95f else 0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = AppTheme.extendedColors.primaryBackground
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Text(
                    text = title,
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                )

                if (isLandscape && visibleFields.size >= 4) {
                    LandscapeFieldsLayout(fields = visibleFields)
                } else {
                    PortraitFieldsLayout(fields = visibleFields)
                }
                if (showSealedButtons) {

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.select_container_status),
                        color = AppTheme.extendedColors.textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    ContainerStatusSlider(
                        selectedStatus = selectedContainerStatus,
                        onStatusSelected = onContainerStatusChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (showBucketSelector && bucketList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.select_bucket),
                        color = AppTheme.extendedColors.textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    BucketDropdownField(
                        bucketList = bucketList,
                        selectedBucket = localSelectedBucket,
                        onBucketSelected = { localSelectedBucket = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HollowButton(
                        text = stringResource(R.string.cancel).uppercase(),
                        onClick = onCancel,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(50))
                    )

                    ActionButtonPrimary(
                        text = stringResource(R.string.add).uppercase(),
                        onClick = {
                            if (showBucketSelector) onBucketSelected(localSelectedBucket)
                            onProceed()
                        },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}

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
private fun PortraitFieldsLayout(fields: List<DialogField>) {
    Column {
        fields.forEachIndexed { index, field ->
            InfoField(
                label = field.label,
                value = field.value
            )

            if (index != fields.lastIndex) {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun LandscapeFieldsLayout(fields: List<DialogField>) {
    val leftColumn = fields.take(2)
    val rightColumn = fields.drop(2).take(2)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            leftColumn.forEach { field ->
                InfoField(
                    label = field.label,
                    value = field.value
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            rightColumn.forEach { field ->
                InfoField(
                    label = field.label,
                    value = field.value
                )
            }
        }
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
            fontWeight = FontWeight.Normal
        )

        Text(
            text = value,
            color = AppTheme.extendedColors.textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
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