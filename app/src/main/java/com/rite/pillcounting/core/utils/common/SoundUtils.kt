package com.rite.pillcounting.core.utils.common

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.util.Locale

/**
 * Audio helpers for the app.
 *
 * 1. [TextToSpeech] voiceover that respects the device's system volume. By
 *    default a TTS engine can play at its own fixed level regardless of the
 *    volume slider — lowering (or muting) the system volume has no effect.
 *    Routing speech to the media stream makes its output track the media-volume
 *    level (and lets the hardware volume keys adjust it while it is speaking),
 *    so it lowers with the slider and goes silent at zero.
 *
 * 2. Short, asset-free feedback cues used by the scanning flows
 *    ([playCaptureSound] / [playBarcodeSound]); they follow the same media-volume
 *    behavior so they go silent when the slider is muted.
 */
object SoundUtils {

    private val logger = AppLogger("SoundUtils")

    /** Lazily-built shutter player; kept alive for the process so repeated
     *  captures don't reload the system sample each time. */
    private val mediaActionSound by lazy { MediaActionSound() }

    /** Relative beep volume (0–100) for the barcode tone. */
    private const val BEEP_VOLUME = 80

    /** Beep duration in ms. Short, like a handheld scanner's confirmation chirp. */
    private const val BEEP_DURATION_MS = 150

    /** True when the media-volume slider is at zero — used to honor "muted". */
    private fun isMediaMuted(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

    /**
     * Play the camera shutter click for a photo capture. Skipped when the media
     * volume is muted so it matches the rest of the app's volume behavior.
     */
    fun playCaptureSound(context: Context) {
        if (isMediaMuted(context)) return
        try {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            logger.w("Could not play capture sound: ${e.message}")
        }
    }

    /**
     * Play a short confirmation beep for a successful barcode decode. Skipped
     * when the media volume is muted. A fresh [ToneGenerator] is used per call
     * and released after the tone so no native resource is held between scans.
     */
    fun playBarcodeSound(context: Context) {
        if (isMediaMuted(context)) return
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            // Release after the tone finishes; releasing immediately can cut it off.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { toneGenerator.release() },
                (BEEP_DURATION_MS + 50).toLong()
            )
        } catch (e: Exception) {
            logger.w("Could not play barcode sound: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // Shared TextToSpeech engine
    //
    // A TextToSpeech instance binds to the system TTS service asynchronously — its
    // onInit callback fires ~0.5–1s after construction. Building a fresh engine
    // every time a screen is entered means the first voiceover on that screen is
    // delayed by that init latency. Instead we keep ONE process-wide engine,
    // warmed up once at app start ([prewarmTts]), so screen entry finds it already
    // initialised and speaks immediately.
    // ---------------------------------------------------------------------------

    private var ttsEngine: TextToSpeech? = null

    /**
     * Whether the shared engine has finished initialising. Backed by Compose
     * snapshot state so a composable reading it recomposes (and re-runs its
     * speak effect) the moment the engine becomes ready — covering the rare case
     * where a screen is entered before prewarm completes.
     */
    var isTtsReady by mutableStateOf(false)
        private set

    /**
     * Build and initialise the shared TTS engine if it doesn't exist yet.
     * Idempotent and safe to call from app start and again on screen entry.
     * Uses the application context so the engine outlives any single Activity.
     */
    @Synchronized
    fun prewarmTts(context: Context) {
        if (ttsEngine != null) return
        val appContext = context.applicationContext
        ttsEngine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine?.language = Locale.US
                ttsEngine?.routeToMediaStream()
                isTtsReady = true
                logger.i("Shared TTS engine ready")
            } else {
                logger.w("Shared TTS engine init failed: status=$status")
            }
        }
    }

    /**
     * Speak [text] on the shared engine at the current system media volume.
     * Lazily prewarms the engine if needed; no-ops silently until it is ready
     * (the [isTtsReady] state change will trigger the caller's speak effect).
     */
    fun speak(context: Context, text: String, utteranceId: String) {
        if (ttsEngine == null) prewarmTts(context)
        if (!isTtsReady) return
        ttsEngine?.speakAtSystemVolume(context, text, utteranceId)
    }

    /**
     * Stop any in-progress utterance without tearing down the engine. Call from a
     * screen's onDispose so a title isn't still being spoken after leaving, while
     * keeping the engine warm for the next screen.
     */
    fun stopSpeaking() {
        ttsEngine?.stop()
    }

    /**
     * Route this engine's speech to the media stream so its output level follows
     * the system media volume and the hardware volume keys adjust it while it is
     * speaking. Safe to call once the engine is ready.
     */
    fun TextToSpeech.routeToMediaStream() {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    /**
     * Speak [text] at the current system media volume (the engine must already be
     * routed to the media stream via [routeToMediaStream]). Skips dispatching when
     * the media volume is muted, and flushes any in-progress utterance first.
     */
    fun TextToSpeech.speakAtSystemVolume(
        context: Context,
        text: String,
        utteranceId: String,
    ) {
        stop()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val muted = audioManager?.let {
            it.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        } ?: false
        // Media volume at 0 → don't speak at all (matches "muted" expectation even
        // on engines that might otherwise play a residual tone).
        if (muted) return
        speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
}
