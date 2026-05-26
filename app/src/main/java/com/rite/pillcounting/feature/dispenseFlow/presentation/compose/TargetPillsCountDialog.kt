package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.FilledButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun TargetPillsCountDialog(onDismiss: () -> Unit, onOkay: (Int) -> Unit) {
    val dimens = AppTheme.dimens

    var count by rememberSaveable { mutableStateOf("") }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        )

    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = AppTheme.extendedColors.primaryBackground
            )
        ) {
            Column(
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = dimens.medium, top = dimens.medium, end = dimens.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween

                ) {
                    Text(
                        text = stringResource(R.string.pill_required).uppercase(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.extendedColors.textColor,
                        modifier = Modifier
                            .wrapContentWidth(Alignment.Start)
                    )

                    // Close button
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = AppTheme.extendedColors.textColor
                        )
                    }
                }


                Spacer(modifier = Modifier.height(dimens.small))

                Row(modifier = Modifier.padding(dimens.medium)) {
                    PillCountTextField(
                        pillCount = count,
                        onPillCountChange = { count = it },
                        boxCount = 4
                    )
                }



                Spacer(modifier = Modifier.height(responsiveDp(dimens.medium)))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(responsiveDp(dimens.medium)),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        bottom = responsiveDp(dimens.xxLarge),
                        top = dimens.small,
                        start = dimens.xxLarge,
                        end = dimens.xxLarge
                    )
                ) {
                    HollowButton(
                        text = stringResource(R.string.cancel).uppercase(),
                        onClick = onDismiss,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        buttonHeightDefault = dimens.huge
                    )

                    FilledButton(
                        text = stringResource(R.string.ok).uppercase(),
                        onClick = { onOkay(count.toInt()) },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        buttonHeightDefault = dimens.huge
                    )
                }

            }

        }

    }
}