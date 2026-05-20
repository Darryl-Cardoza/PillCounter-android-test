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
import androidx.compose.foundation.layout.size
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
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDpForAddNoteDialog
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // Block accidental dismissals — tapping outside the camera viewport
            // was closing this mid-flow and losing the note. Only the explicit
            // Close (X), Skip, and Save buttons should dismiss it.
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(dimens.medium),
            modifier = Modifier
                .width(responsiveDpForAddNoteDialog(300.dp))
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .background(AppTheme.extendedColors.primaryBackground)
                    .padding(bottom = dimens.medium, start = dimens.medium, end = dimens.medium, top = dimens.small)
            ) {
                // Top Row: Heading + Cross button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.add_note).uppercase(),
                        fontSize = 18.sp,
                        color = AppTheme.extendedColors.textColor,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close, contentDescription = "Close",
                            modifier = Modifier.size(responsiveDp(24.dp))
                        )
                    }
                }

                // Text area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(responsiveDpForAddNoteDialog(180.dp))
                        .background(
                            color = AppTheme.extendedColors.inputBackground,
                            shape = RoundedCornerShape(dimens.small)
                        )
                        .padding(bottom = dimens.small, start = dimens.small, end = dimens.small)
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
                            .padding(15.dp),
                        decorationBox = { innerTextField ->
                            if (noteText.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.type_here),
                                    style = TextStyle(
                                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.4f),
                                        fontSize = 16.sp
                                    ),
//                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                            innerTextField()
                        }
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

                Spacer(modifier = Modifier.height(dimens.medium))

                // Buttons Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
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
