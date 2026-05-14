package com.rite.pillcounting.feature.pillCountScan.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.ui.theme.AppTheme


@Composable
fun AddNoteDialog(
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onSave: (String) -> Unit,
    showSkip: Boolean = true
) {
    val dimens = AppTheme.dimens
    var noteText by rememberSaveable { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    // Adjust width based on orientation
    val dialogWidth = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        screenWidth * 0.6f // 70% of width in landscape
    } else {
        screenWidth * 0.85f // 90% of width in portrait
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false // allows your width modifier to take effect
        )
    ) {
        Card(
            shape = RoundedCornerShape(dimens.medium),
            modifier = Modifier
                .width(dialogWidth)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .background(AppTheme.extendedColors.primaryBackground)
                    .padding(dimens.medium)
            ) {
                // Top Row: Heading + Cross button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.add_note).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.extendedColors.textColor
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close, contentDescription = "Close"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(dimens.medium))

                // Text area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(
                            color = AppTheme.extendedColors.inputBackground,
                            shape = RoundedCornerShape(dimens.small)
                        )
                        .padding(dimens.small)
                ) {
                    BasicTextField(
                        value = noteText,
                        onValueChange = {
                            noteText = it
                            if (showError) showError = false
                        },
                        textStyle = TextStyle(
                            color = AppTheme.extendedColors.textColor,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(AppTheme.extendedColors.textColor),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                }

                if (showError) {
                    Text(
                        text = stringResource(R.string.add_note_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = dimens.extraSmall)
                    )
                }

                Spacer(modifier = Modifier.height(dimens.extraLarge))

                // Buttons Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (showSkip) {
                        HollowButton(
                            text = stringResource(R.string.skip).uppercase(),
                            onClick = onSkip,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(dimens.medium))
                    }
                    ActionButtonPrimary(
                        text = stringResource(R.string.save).uppercase(),
                        onClick = {
                            if (noteText.isBlank()) {
                                showError = true
                            } else {
                                onSave(noteText.trim())
                            }
                        },
                    )
                }
            }
        }
    }
}
