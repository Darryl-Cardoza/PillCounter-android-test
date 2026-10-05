package com.dispensesure.retail.core.utils.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.ui.theme.AppTheme

/** "Email Address" label above an outlined single-line email input. [onDone] runs on the keyboard's Done key. */
@Composable
fun AuthEmailField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = AppTheme.extendedColors.textColor
    val textSize = responsiveSp(8.sp)
    val fieldShape = RoundedCornerShape(responsiveDp(8.dp))

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.email_address),
            color = textColor,
            fontSize = textSize,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(responsiveDp(6.dp)))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            textStyle = TextStyle(color = textColor, fontSize = textSize),
            cursorBrush = SolidColor(textColor),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppTheme.dimens.buttonHeight)
                        .border(1.dp, textColor.copy(alpha = 0.25f), fieldShape)
                        .padding(horizontal = responsiveDp(12.dp)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.email_placeholder),
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = textSize,
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}
