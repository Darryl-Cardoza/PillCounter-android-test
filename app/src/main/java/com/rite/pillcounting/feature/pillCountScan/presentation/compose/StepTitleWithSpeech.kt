package com.rite.pillcounting.feature.pillCountScan.presentation.compose

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.models.titleRes
import com.rite.pillcounting.ui.theme.AppTheme
import java.util.Locale

@Composable
fun StepTitleWithSpeech(
    stepType: StepState,
    isSoundOverride: Boolean,
    titleResOverride: Int? = null
) {
    val context = LocalContext.current
    val title = if (titleResOverride != null) stringResource(titleResOverride) else stringResource(stepType.titleRes())

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
            }
        }

        tts = ttsInstance

        onDispose {
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }

    LaunchedEffect(title, isTtsReady) {
        if (isTtsReady && isSoundOverride) {
            tts?.stop()
            tts?.speak(
                title,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "step_title_$title"
            )
        }
    }

    Box(
        modifier = Modifier
            .background(
                AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.8f),
                shape = RoundedCornerShape(50.dp)
            )
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            color = AppTheme.extendedColors.textColor,
            fontSize = 16.sp
        )
    }
}