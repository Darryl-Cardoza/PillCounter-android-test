package com.rite.pillcounting.feature.dispenseScan.presentation

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dispenseScan.presentation.viewmodel.DispenseStage
import java.util.Locale

/**
 * Drives voice-over prompts for the merged dispense screen.
 *
 * Conditions:
 *  A) Pills detected first → "Scan RX to add the pill count"
 *  B) RX scanned first → "Scan the container QR code"
 *  After RX → "Scan the container QR code"
 *  After NDC → "Add the pill count"
 *
 * Speech is throttled so we never speak the same prompt twice in a row.
 */
@Composable
fun DispenseVoicePrompt(
    stage: DispenseStage,
    pillsDetected: Boolean,
    isSoundEnabled: Boolean,
    isAnyOverlayShowing: Boolean,
) {
    val context = LocalContext.current
    val scanRxPrompt = stringResource(R.string.voice_scan_rx_to_add_count)
    val scanContainerPrompt = stringResource(R.string.voice_scan_container_qr)
    val addPillCountPrompt = stringResource(R.string.voice_add_the_pill_count)

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isReady by remember { mutableStateOf(false) }
    var lastSpoken by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isReady = true
            }
        }
        tts = instance
        onDispose {
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }

    val nextPrompt: String? = when {
        // While the RX bottomsheet or NDC popup is visible, stay quiet.
        isAnyOverlayShowing -> null
        stage == DispenseStage.PRE_RX && pillsDetected -> scanRxPrompt
        stage == DispenseStage.PRE_NDC -> scanContainerPrompt
        stage == DispenseStage.COUNTING && pillsDetected -> addPillCountPrompt
        else -> null
    }

    LaunchedEffect(nextPrompt, isReady) {
        val prompt = nextPrompt
        if (!isReady || !isSoundEnabled || prompt == null) return@LaunchedEffect
        if (prompt == lastSpoken) return@LaunchedEffect
        tts?.stop()
        tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "dispense_$prompt")
        lastSpoken = prompt
    }

    // Reset the dedupe key whenever the screen leaves a prompt state, so the same
    // prompt can be re-spoken if we re-enter the same state later.
    LaunchedEffect(nextPrompt) {
        if (nextPrompt == null) lastSpoken = null
    }
}
