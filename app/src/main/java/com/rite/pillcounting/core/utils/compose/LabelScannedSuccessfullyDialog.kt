package com.rite.pillcounting.core.utils.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun LabelScannedSuccessfullyDialog(
    fields: List<DialogField>,
    title: String = stringResource(R.string.label_scanned_successfully),
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleFields = fields.filter { it.value.isNotBlank() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
                        text = stringResource(R.string.proceed).uppercase(),
                        onClick = onProceed,
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
            modifier =
                Modifier.weight(1f),
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
private fun InfoField(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.extendedColors.secondaryBackground)
            .padding(horizontal = 16.dp, vertical = 4.dp)
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
