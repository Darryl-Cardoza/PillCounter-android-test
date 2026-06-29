package com.rite.pillcounting.core.utils.compose

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.models.StepStatus
import com.rite.pillcounting.core.models.titleRes
import com.rite.pillcounting.core.utils.common.TtsUtils.routeToMediaStream
import com.rite.pillcounting.core.utils.common.TtsUtils.speakAtSystemVolume
import kotlinx.coroutines.delay
import java.util.Locale

// How long a tapped step's title bubble stays visible.
private const val STEP_TOOLTIP_VISIBLE_MS = 3000L

@Composable
fun WorkflowStepper(
    steps: List<StepState>,
    currentStep: StepState,
    isVoiceOverEnabled: Boolean = false,
    circleSize: Dp = 35.dp,
    // When true, the current step's title bubble is revealed automatically on
    // entry (and whenever the current step changes) — used where the persistent
    // header title is suppressed, so the user still sees (and, with voiceover on,
    // hears) the step name once.
    autoRevealCurrentStep: Boolean = false,
    // Per-step title overrides. A step present here uses the mapped string instead
    // of its default [titleRes] for both the bubble and the spoken voiceover —
    // e.g. stock count shows "Scan Open Pills" on the counting step.
    titleOverrides: Map<StepState, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {

    val currentIndex = steps.indexOf(currentStep).coerceAtLeast(0)

    // Resolve each step's display title once (applying any override), so the
    // bubble and the spoken voiceover stay in sync.
    val resolvedTitles = steps.map { step ->
        stringResource(titleOverrides[step] ?: step.titleRes())
    }
    val currentTitle = resolvedTitles.getOrNull(currentIndex)

    // Single TextToSpeech engine for the whole stepper: tapping any step speaks
    // that step's title aloud. Created/destroyed with the composable.
    val context = LocalContext.current
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Set the language on the captured local (assigned before this async
                // init callback fires) rather than the `tts` state var.
                engine?.language = Locale.US
                // Route to the media stream so the volume keys / system volume
                // control the voiceover instead of it playing at a fixed level.
                engine?.routeToMediaStream()
                isTtsReady = true
            }
        }
        tts = engine
        onDispose {
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }

    // Which step's bubble is currently shown (-1 = none). The nonce restarts the
    // auto-hide timer even when the same step is tapped again.
    var tooltipIndex by remember { mutableIntStateOf(-1) }
    var clickNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(clickNonce) {
        if (tooltipIndex >= 0) {
            delay(STEP_TOOLTIP_VISIBLE_MS)
            tooltipIndex = -1
        }
    }

    // Reveal the current step's title bubble automatically when requested (on
    // entry and on every current-step change), then let the auto-hide timer above
    // dismiss it after STEP_TOOLTIP_VISIBLE_MS. Speak it aloud too when voiceover
    // is enabled (waits for the TTS engine to finish initialising).
    LaunchedEffect(autoRevealCurrentStep, currentIndex, isTtsReady) {
        if (autoRevealCurrentStep) {
            tooltipIndex = currentIndex
            clickNonce++
            if (isVoiceOverEnabled && isTtsReady && currentTitle != null) {
                tts?.speakAtSystemVolume(
                    context = context,
                    text = currentTitle,
                    utteranceId = "step_title_auto_$currentIndex",
                )
            }
        }
    }

    Row(
        // fillMaxWidth first so a width constraint supplied by the caller via
        // [modifier] (e.g. wrapContentWidth when embedded inline in a row) wins.
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {

        steps.forEachIndexed { index, step ->

            val state = when {
                index < currentIndex -> StepStatus.DONE
                index == currentIndex -> StepStatus.ACTIVE
                else -> StepStatus.PENDING
            }

            val title = resolvedTitles[index]

            StepCircle(
                step = step,
                state = state,
                title = title,
                showTooltip = tooltipIndex == index,
                circleSize = circleSize,
                onClick = {
                    // Always show the title tooltip. Only speak it aloud when the
                    // voiceover setting is enabled; otherwise just show the text.
                    tooltipIndex = index
                    clickNonce++
                    if (isVoiceOverEnabled && isTtsReady) {
                        tts?.speakAtSystemVolume(
                            context = context,
                            text = title,
                            utteranceId = "step_title_$index",
                        )
                    }
                }
            )

            if (index < steps.lastIndex) {

                Spacer(modifier = Modifier.width(6.dp))

                StepArrow()

                Spacer(modifier = Modifier.width(6.dp))
            }
        }
    }
}
