package com.rite.pillcounting.feature.pillCountScan.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.ui.theme.AppTheme


@Composable
fun PillCountTextField(
    pillCount: String,
    onPillCountChange: (String) -> Unit,
    boxCount: Int = 4,
    cornerRadius: Dp = 8.dp,
    boxBackground: Color = AppTheme.extendedColors.inputBackground,
    textColor: Color = AppTheme.extendedColors.textColor
) {
    val dimens = AppTheme.dimens
    val focusRequester = remember { FocusRequester() }

    var tfValue by remember {
        mutableStateOf(TextFieldValue(text = pillCount, selection = TextRange(pillCount.length)))
    }

    LaunchedEffect(pillCount) {
        tfValue = tfValue.copy(text = pillCount, selection = TextRange(pillCount.length))
    }

    val configuration = LocalConfiguration.current

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val displayedValue = pillCount.padStart(boxCount, '0').takeLast(boxCount)

    BasicTextField(
        value = tfValue,

        onValueChange = { newTf ->
            val digits = newTf.text.filter { it.isDigit() }

            val limited = if (digits.length <= boxCount) digits else pillCount

            if (limited != pillCount) {
                onPillCountChange(limited)
            }

            tfValue = tfValue.copy(text = limited, selection = TextRange(limited.length))
        },
        modifier = Modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Backspace) {
                    if (pillCount.isNotEmpty()) {
                        onPillCountChange(pillCount.dropLast(1))
                        true
                    } else false
                } else false
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        textStyle = TextStyle(
            color = Color.Transparent, // keep input hidden
            fontSize = 54.sp
        ),
        cursorBrush = SolidColor(Color.Transparent),
        decorationBox = { innerTextField ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimens.small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                displayedValue.forEach { digit ->
                    Box(
                        modifier = Modifier
                            .height(responsiveDp(70.dp))
                            .width(responsiveDp(50.dp))
                            .background(
                                boxBackground,
                                shape = RoundedCornerShape(cornerRadius)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = digit.toString(),
                            color = textColor,
                            fontSize = if(isLandscape)30.sp else 24.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Box(Modifier.width(1.dp)) {
                    innerTextField()
                }
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}



