package com.rite.pillcounting.core.utils.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.models.titleRes
import com.rite.pillcounting.core.utils.common.SoundUtils
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * The pill-shaped scanning-step title chip shared by the camera screens
 * ("Scan Rx Label", "Scan Photo ID", …), with optional voiceover of the title.
 *
 * @param isSoundOverride Whether to speak the title aloud (voiceover setting).
 * @param stepType Dispense-flow step whose title to show; only the entry/capture
 *   steps render a header. Pass null when using [titleResOverride] alone.
 * @param titleResOverride Explicit title, always shown; takes precedence over [stepType].
 */
@Composable
fun StepTitleWithSpeech(
    isSoundOverride: Boolean,
    stepType: StepState? = null,
    titleResOverride: Int? = null
) {
    val context = LocalContext.current
    val title = when {
        titleResOverride != null -> stringResource(titleResOverride)
        stepType != null -> stringResource(stepType.titleRes())
        else -> return
    }

    // The header title is only shown for these entry/capture steps. All other
    // steps (e.g. CONTAINER_INITIATE, TARGET_VERIFICATION) suppress it — they
    // carry their own on-screen affordances. An explicit title override (e.g. the
    // REGULAR "Scan open pills" prompt during COUNTING) is always shown.
    val showHeaderTitle = titleResOverride != null ||
        stepType == StepState.RX_LABEL ||
        stepType == StepState.SCAN ||
        stepType == StepState.VIAL

    // Reuse the process-wide TTS engine (warmed up at app start). Ensure it's
    // initialising in case this screen is the first thing to need it, and stop any
    // in-progress utterance on leave without tearing the shared engine down.
    DisposableEffect(context) {
        SoundUtils.prewarmTts(context)
        onDispose { SoundUtils.stopSpeaking() }
    }

    // For the allowed header steps, speak the step title aloud when the voiceover
    // setting (isSoundOverride) is enabled. Re-runs when the engine becomes ready
    // (SoundUtils.isTtsReady is observable) so an early entry still speaks.
    LaunchedEffect(title, SoundUtils.isTtsReady, showHeaderTitle, isSoundOverride) {
        if (SoundUtils.isTtsReady && isSoundOverride && showHeaderTitle) {
            SoundUtils.speak(
                context = context,
                text = title,
                utteranceId = "step_title_$title",
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
