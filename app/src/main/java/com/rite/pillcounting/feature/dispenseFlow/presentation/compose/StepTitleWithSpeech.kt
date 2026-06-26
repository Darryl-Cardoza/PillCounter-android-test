package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

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

    // The header title is only shown for these entry/capture steps. All other
    // steps (e.g. CONTAINER_INITIATE, TARGET_VERIFICATION) suppress it — they
    // carry their own on-screen affordances. An explicit title override (e.g. the
    // REGULAR "Scan open pills" prompt during COUNTING) is always shown.
    val showHeaderTitle = titleResOverride != null ||
        stepType == StepState.RX_LABEL ||
        stepType == StepState.SCAN ||
        stepType == StepState.VIAL

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

    // For the allowed header steps, speak the step title aloud when the voiceover
    // setting (isSoundOverride) is enabled.
    LaunchedEffect(title, isTtsReady, showHeaderTitle) {
        if (isTtsReady && isSoundOverride && showHeaderTitle) {
            tts?.stop()
            tts?.speak(
                title,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "step_title_$title"
            )
        }
    }

    // Render the title in the header for the allowed steps (e.g. "Scan Rx Label",
    // "Scan Container QR Code"). It stays visible for the duration of the step.
    if (showHeaderTitle) {
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
}
