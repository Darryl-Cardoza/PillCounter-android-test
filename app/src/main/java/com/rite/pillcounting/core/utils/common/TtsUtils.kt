package com.rite.pillcounting.core.utils.common

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech

/**
 * Helpers that make [TextToSpeech] voiceover respect the device's system volume.
 *
 * By default a TTS engine can play at its own fixed level regardless of the
 * volume slider — lowering (or muting) the system volume has no effect. Routing
 * speech to the media stream makes its output track the media-volume level (and
 * lets the hardware volume keys adjust it while it is speaking), so it lowers
 * with the slider and goes silent at zero.
 */
object TtsUtils {

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
