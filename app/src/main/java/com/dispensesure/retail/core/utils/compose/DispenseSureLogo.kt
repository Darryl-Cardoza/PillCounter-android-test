package com.dispensesure.retail.core.utils.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.ui.theme.AppTheme

/** App mark tinted with the theme primary, next to the "DISPENSE / SURE" wordmark. */
@Composable
fun DispenseSureLogo(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val wordmarkSize = responsiveSp(12.sp)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = stringResource(R.string.app_name),
            tint = primary,
            modifier = Modifier.size(responsiveDp(56.dp))
        )
        Spacer(Modifier.width(responsiveDp(4.dp)))
        Column {
            Text(
                text = stringResource(R.string.wordmark_dispense),
                color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f),
                fontSize = wordmarkSize,
                lineHeight = wordmarkSize,
            )
            Text(
                text = stringResource(R.string.wordmark_sure),
                color = primary,
                fontSize = wordmarkSize,
                lineHeight = wordmarkSize,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
