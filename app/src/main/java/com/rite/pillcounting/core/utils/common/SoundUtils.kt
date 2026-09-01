package com.rite.pillcounting.core.utils.common

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.util.Locale

/**
 * The single owner of every sound this app produces. Four of them:
 *
 * 1. [speak] — TextToSpeech voiceover of step titles.
 * 2. [playCaptureSound] — the vial-capture shutter click.
 * 3. [playBarcodeSound] — barcode / ID decode confirmation.
 * 4. [playCountSound] — the count-confirmed cue.
 *
 * Sounds 1, 3 and 4 track the device's media-volume slider and fall silent at
 * zero. Speech gets that for free by being routed to the media stream
 * ([routeToMediaStream]); the two tones cannot, because [ToneGenerator] fixes
 * its level at construction as a percentage of the device MAXIMUM rather than
 * of the slider — so [mediaVolumePercent] computes that percentage for them.
 *
 * Sound 2 is the deliberate exception: a capture must always be audible, so it
 * plays through [MediaActionSound] at the fixed system level and is NOT silenced
 * when the slider is at zero.
 *
 * Nothing here re-implements volume as an on/off decision. An earlier version
 * gated each sound on `getStreamVolume(...) == 0`, which is why the app used to
 * be either silent or at full loudness with nothing in between.
 *
 * Speech also takes transient ducking audio focus so it is intelligible over
 * other apps, and stops when something else (an incoming call) takes focus.
 */
object SoundUtils {

    private val logger = AppLogger("SoundUtils")

    /** Lazily-built shutter player; kept alive for the process so repeated
     *  captures don't reload the system sample each time. */
    private val mediaActionSound by lazy { MediaActionSound() }

    /** Beep duration in ms. Short, like a handheld scanner's confirmation chirp. */
    private const val BEEP_DURATION_MS = 150

    /**
     * The current media-volume level as a 0–100 percentage of the device maximum.
     *
     * [ToneGenerator] takes its level as a percentage of the device maximum,
     * fixed at construction — it is not scaled by the volume slider the way a
     * normal media stream is. So the scaling the audio system would otherwise do
     * for us has to be computed here. This is the single source of volume truth
     * for the cues that scale.
     *
     * Returns 0 when the AudioManager is unavailable or reports a non-positive
     * maximum; callers treat 0 as "don't play".
     */
    internal fun mediaVolumePercent(context: Context): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return 0
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / max
    }

    /**
     * Play the camera shutter click for a photo capture.
     *
     * Deliberately NOT volume-scaled and deliberately not silenced when the
     * media slider is at zero: a capture must always be audible. [MediaActionSound]
     * has no volume parameter at all, so this plays at the fixed system level
     * whatever the slider says — that is the accepted trade for always being heard.
     */
    fun playCaptureSound() {
        try {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            logger.w("Could not play capture sound: ${e.message}")
        }
    }

    /**
     * Play [toneType] at the current media-volume level and release the native
     * generator once it has finished.
     *
     * A fresh [ToneGenerator] is built per call because its level is fixed at
     * construction: reusing one would pin every later cue to the slider position
     * that happened to be in effect when it was first built.
     */
    private fun playTone(context: Context, toneType: Int) {
        val volumePercent = mediaVolumePercent(context)
        if (volumePercent == 0) return
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, volumePercent)
            toneGenerator.startTone(toneType, BEEP_DURATION_MS)
            // Release after the tone finishes; releasing immediately can cut it off.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { toneGenerator.release() },
                (BEEP_DURATION_MS + 50).toLong()
            )
        } catch (e: Exception) {
            logger.w("Could not play tone $toneType: ${e.message}")
        }
    }

    /**
     * Play a short confirmation beep for a successful barcode decode, scaled to
     * the media-volume slider and silent when it is at zero.
     */
    fun playBarcodeSound(context: Context) =
        playTone(context, ToneGenerator.TONE_PROP_BEEP)

    /**
     * Play the count-confirmed cue, scaled to the media-volume slider and silent
     * when it is at zero.
     *
     * Uses a different tone from [playBarcodeSound] so "count locked in" and
     * "barcode read" stay distinguishable by ear during a fast workflow.
     */
    fun playCountSound(context: Context) =
        playTone(context, ToneGenerator.TONE_PROP_PROMPT)

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

    /** Cached at prewarm from the application context, so focus can be abandoned
     *  from callbacks that have no Context of their own. */
    private var audioManager: AudioManager? = null

    /** Built once and reused: abandoning focus requires the same instance that
     *  requested it. */
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Speech is the only thing we interrupt other apps for, so it is also the
     * only thing we let other apps interrupt. On any loss we stop talking rather
     * than speak over an incoming call.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stopSpeaking()
        }
    }

    /**
     * Ask other apps to duck while we speak. TRANSIENT_MAY_DUCK rather than
     * TRANSIENT: utterances are two- or three-word step titles, and fully pausing
     * a tech's music for each one would be worse than the interruption it avoids.
     */
    private fun requestSpeechFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        manager.requestAudioFocus(request)
    }

    private fun abandonSpeechFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        manager.abandonAudioFocusRequest(request)
    }

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
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ttsEngine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine?.language = Locale.US
                ttsEngine?.routeToMediaStream()
                ttsEngine?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) = abandonSpeechFocus()

                        @Deprecated("Required override; the String overload is deprecated")
                        override fun onError(utteranceId: String?) = abandonSpeechFocus()

                        // Deliberately NOT abandoning here. speak() calls stop()
                        // before requesting focus, and this callback is delivered
                        // asynchronously — abandoning would race with, and cancel,
                        // the focus request that immediately follows.
                        override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
                    }
                )
                isTtsReady = true
                logger.i("Shared TTS engine ready")
            } else {
                logger.w("Shared TTS engine init failed: status=$status")
            }
        }
    }

    /**
     * Speak [text] on the shared engine. Lazily prewarms the engine if needed and
     * no-ops silently until it is ready (the [isTtsReady] state change will
     * re-trigger the caller's speak effect).
     *
     * Loudness is not decided here. The engine is routed to the media stream by
     * [routeToMediaStream], so the system scales the output against the media
     * slider and silences it at zero — the same as any other media audio.
     */
    fun speak(context: Context, text: String, utteranceId: String) {
        if (ttsEngine == null) prewarmTts(context)
        if (!isTtsReady) return
        val engine = ttsEngine ?: return
        engine.stop()
        requestSpeechFocus()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Stop any in-progress utterance without tearing down the engine, and release
     * audio focus so other apps stop ducking. Call from a screen's onDispose so a
     * title isn't still being spoken after leaving, while keeping the engine warm
     * for the next screen.
     */
    fun stopSpeaking() {
        ttsEngine?.stop()
        abandonSpeechFocus()
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
}
