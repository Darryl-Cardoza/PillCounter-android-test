package com.dispensesure.retail.core.scanning.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.dispensesure.retail.ui.theme.AppTheme.dimens
import java.util.Locale

/**
 * "Counting paused" scrim with a Resume button, shown when the engine pauses itself
 * after a quiet minute. Only the button takes touches.
 */
@Composable
fun CountingPausedOverlay(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            // this is to disable the user to click the items below this overlay of resume.
            // Only the resume button has the touch.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.counting_paused).uppercase(Locale.ROOT),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            ActionButtonPrimary(
                text = stringResource(R.string.resume).uppercase(Locale.ROOT),
                onClick = onResume,
                modifier = Modifier.width(dimens.dialogButtonWidth),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
