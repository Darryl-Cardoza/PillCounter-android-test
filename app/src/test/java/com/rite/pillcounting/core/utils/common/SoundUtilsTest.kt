package com.rite.pillcounting.core.utils.common

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SoundUtils].
 *
 * Covers the media-volume scaling shared by the two tone cues, the capture
 * click's deliberate exemption from it, the [SoundUtils.speak] /
 * [SoundUtils.stopSpeaking] state machine around the lazily-initialised shared
 * TTS engine, and the audio-focus lifecycle.
 *
 * These prove the WIRING only — that each sound asks for the level it should.
 * They cannot prove audibility; that is the manual checklist in
 * plans/audio-volume-bug/plan/audio-volume-bug.md section 7.
 *
 * Runs under Robolectric because `routeToMediaStream()` and the focus request
 * build real `AudioAttributes` — framework classes the plain-JVM Android stub
 * jar can't construct (its chained builder methods return null, so the next
 * chained call NPEs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SoundUtilsTest {

    private val context: Context = mockk(relaxed = true)
    private val audioManager: AudioManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.println(any(), any(), any()) } returns 0
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        resetTtsState()
    }

    @After
    fun tearDown() {
        unmockkAll()
        resetTtsState()
    }

    /** Reset SoundUtils' private static state so tests don't leak into each other. */
    private fun resetTtsState() {
        val ttsField = SoundUtils::class.java.getDeclaredField("ttsEngine")
        ttsField.isAccessible = true
        ttsField.set(SoundUtils, null)

        val readyField = SoundUtils::class.java.getDeclaredField("isTtsReady\$delegate")
        readyField.isAccessible = true
        val state = readyField.get(SoundUtils) as androidx.compose.runtime.MutableState<Boolean>
        state.value = false

        // SoundUtils.mediaActionSound is a `by lazy` singleton scoped to this class's
        // classloader; it isn't reset here (Kotlin's Lazy delegate can't be swapped
        // via reflection — see the playCaptureSound tests below for how construction
        // is intercepted instead via mockkConstructor).

        val audioManagerField = SoundUtils::class.java.getDeclaredField("audioManager")
        audioManagerField.isAccessible = true
        audioManagerField.set(SoundUtils, null)

        val focusField = SoundUtils::class.java.getDeclaredField("focusRequest")
        focusField.isAccessible = true
        focusField.set(SoundUtils, null)
    }

    /** Inject the mocked AudioManager that SoundUtils caches at prewarm time. */
    private fun installAudioManager() {
        val field = SoundUtils::class.java.getDeclaredField("audioManager")
        field.isAccessible = true
        field.set(SoundUtils, audioManager)
    }

    /** Inject [tts] as the shared engine and mark it ready, bypassing the async
     *  onInit callback that a real TextToSpeech would deliver. */
    private fun installReadyEngine(tts: TextToSpeech) {
        val ttsField = SoundUtils::class.java.getDeclaredField("ttsEngine")
        ttsField.isAccessible = true
        ttsField.set(SoundUtils, tts)

        val readyField = SoundUtils::class.java.getDeclaredField("isTtsReady\$delegate")
        readyField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = readyField.get(SoundUtils) as androidx.compose.runtime.MutableState<Boolean>
        state.value = true
    }

    // -------------------- mediaVolumePercent --------------------

    @Test
    fun `mediaVolumePercent scales current volume against the device maximum`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15

        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 15
        assertEquals(100, SoundUtils.mediaVolumePercent(context))

        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 7
        assertEquals(46, SoundUtils.mediaVolumePercent(context))

        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 3
        assertEquals(20, SoundUtils.mediaVolumePercent(context))

        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0
        assertEquals(0, SoundUtils.mediaVolumePercent(context))
    }

    @Test
    fun `mediaVolumePercent returns zero when AudioManager is unavailable`() {
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns null

        assertEquals(0, SoundUtils.mediaVolumePercent(context))
    }

    @Test
    fun `mediaVolumePercent returns zero when the device reports no maximum`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 0
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 5

        assertEquals(0, SoundUtils.mediaVolumePercent(context))
    }

    // -------------------- playCaptureSound --------------------
    //
    // mediaActionSound is a private `by lazy` singleton scoped to this class's
    // classloader: only the FIRST call to playCaptureSound() in the whole test run
    // actually constructs it, and mockkConstructor() does NOT retroactively intercept
    // an already-existing instance in a later test's fresh @Before/@Test scope — so
    // every scenario is driven from one test that arms the constructor mock before
    // mediaActionSound is touched at all.

    @Test
    fun `playCaptureSound plays even when media volume is muted`() {
        mockkConstructor(MediaActionSound::class)
        every { anyConstructed<MediaActionSound>().play(any()) } returns Unit

        // Muted device — the capture click must STILL fire. This is the
        // deliberate exception to "everything follows the media slider".
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0
        SoundUtils.playCaptureSound()
        verify(exactly = 1) {
            anyConstructed<MediaActionSound>().play(MediaActionSound.SHUTTER_CLICK)
        }

        // Unmuted — fires at the same fixed level; MediaActionSound has no knob.
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 15
        SoundUtils.playCaptureSound()
        verify(exactly = 2) {
            anyConstructed<MediaActionSound>().play(MediaActionSound.SHUTTER_CLICK)
        }

        // A throwing play() is swallowed, not propagated.
        every { anyConstructed<MediaActionSound>().play(any()) } throws RuntimeException("boom")
        SoundUtils.playCaptureSound()
    }

    // -------------------- playBarcodeSound --------------------

    // NOTE ON COVERAGE: the volume actually handed to ToneGenerator is a
    // CONSTRUCTOR argument, and MockK's constructedWith() arg-matching does not
    // bind against the Robolectric-instrumented ToneGenerator. So these tests
    // assert the tone, the duration, and the silent-at-zero boundary; the
    // arithmetic itself is covered directly by the mediaVolumePercent tests
    // above, and that percentage reaching the constructor is a single line in
    // playTone(). Audible proof is the manual checklist (plan section 7 step 3).

    @Test
    fun `playBarcodeSound plays its tone when the media slider is above zero`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 7
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(any(), any()) } returns true

        SoundUtils.playBarcodeSound(context)

        verify(exactly = 1) {
            anyConstructed<ToneGenerator>().startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }

    @Test
    fun `playBarcodeSound is silent when the media slider is at zero`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0
        mockkConstructor(ToneGenerator::class)

        SoundUtils.playBarcodeSound(context)

        verify(exactly = 0) { anyConstructed<ToneGenerator>().startTone(any(), any()) }
    }

    @Test
    fun `playBarcodeSound swallows exceptions from the tone generator`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 5
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(any(), any()) } throws
            RuntimeException("boom")

        // Should not throw despite the underlying failure.
        SoundUtils.playBarcodeSound(context)
    }

    // -------------------- playCountSound --------------------

    @Test
    fun `playCountSound uses a tone distinct from the barcode beep`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 10
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 5
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(any(), any()) } returns true

        SoundUtils.playCountSound(context)

        verify(exactly = 1) {
            anyConstructed<ToneGenerator>().startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
        }
        // Must NOT reuse the barcode tone, or the two cues are indistinguishable.
        verify(exactly = 0) {
            anyConstructed<ToneGenerator>().startTone(ToneGenerator.TONE_PROP_BEEP, any())
        }
    }

    @Test
    fun `playCountSound is silent when the media slider is at zero`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 10
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0
        mockkConstructor(ToneGenerator::class)

        SoundUtils.playCountSound(context)

        verify(exactly = 0) { anyConstructed<ToneGenerator>().startTone(any(), any()) }
    }

    // -------------------- prewarmTts / speak / stopSpeaking --------------------

    @Test
    fun `prewarmTts is idempotent and only constructs one engine`() {
        val ttsField = SoundUtils::class.java.getDeclaredField("ttsEngine")
        ttsField.isAccessible = true

        SoundUtils.prewarmTts(context)
        val engineAfterFirstCall = ttsField.get(SoundUtils)

        SoundUtils.prewarmTts(context)
        val engineAfterSecondCall = ttsField.get(SoundUtils)

        // Second call short-circuits on the already-assigned ttsEngine field, so
        // the same instance survives rather than a new one being constructed.
        assertEquals(engineAfterFirstCall, engineAfterSecondCall)
    }

    @Test
    fun `speak does not dispatch when tts is not yet ready`() {
        mockkConstructor(TextToSpeech::class)

        SoundUtils.speak(context, "hello", "utt-1")

        verify(exactly = 0) {
            anyConstructed<TextToSpeech>().speak(any(), any(), any(), any())
        }
    }

    @Test
    fun `stopSpeaking is a no-op when engine was never created`() {
        // No engine constructed; should not throw.
        SoundUtils.stopSpeaking()
    }

    @Test
    fun `speak dispatches to the engine once ready, regardless of slider position`() {
        val tts: TextToSpeech = mockk(relaxed = true)
        installReadyEngine(tts)
        // Slider at zero: speech must still be DISPATCHED. Silencing it is the
        // media stream's job, not ours — the old early-return here is exactly
        // what made the voiceover all-or-nothing.
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0

        SoundUtils.speak(context, "hello", "utt-1")

        verify(exactly = 1) { tts.speak("hello", TextToSpeech.QUEUE_FLUSH, null, "utt-1") }
    }

    @Test
    fun `speak flushes any in-progress utterance first`() {
        val tts: TextToSpeech = mockk(relaxed = true)
        installReadyEngine(tts)

        SoundUtils.speak(context, "hello", "utt-1")

        verify(exactly = 1) { tts.stop() }
    }

    // -------------------- audio focus --------------------

    @Test
    fun `speak requests transient ducking focus`() {
        val tts: TextToSpeech = mockk(relaxed = true)
        installReadyEngine(tts)
        installAudioManager()

        SoundUtils.speak(context, "hello", "utt-1")

        val request = slot<AudioFocusRequest>()
        verify(exactly = 1) { audioManager.requestAudioFocus(capture(request)) }
        // MAY_DUCK, not TRANSIENT: a two-word step title should dip a tech's
        // music, not stop and restart it.
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            request.captured.focusGain
        )
    }

    @Test
    fun `stopSpeaking abandons focus`() {
        val tts: TextToSpeech = mockk(relaxed = true)
        installReadyEngine(tts)
        installAudioManager()
        SoundUtils.speak(context, "hello", "utt-1")

        SoundUtils.stopSpeaking()

        verify(exactly = 1) { audioManager.abandonAudioFocusRequest(any()) }
    }

    @Test
    fun `losing focus stops the utterance`() {
        val tts: TextToSpeech = mockk(relaxed = true)
        installReadyEngine(tts)
        installAudioManager()
        SoundUtils.speak(context, "hello", "utt-1")

        // AudioFocusRequest exposes no public getter for its listener, so the
        // listener SoundUtils registered is read back off the object directly.
        val listenerField = SoundUtils::class.java.getDeclaredField("focusListener")
        listenerField.isAccessible = true
        val listener = listenerField.get(SoundUtils) as AudioManager.OnAudioFocusChangeListener

        // An incoming call takes focus; our voiceover must shut up rather than
        // talk over it.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        verify(atLeast = 1) { audioManager.abandonAudioFocusRequest(any()) }
        verify(atLeast = 2) { tts.stop() }
    }

    @Test
    fun `routeToMediaStream sets media usage and speech content type audio attributes`() {
        val tts: TextToSpeech = mockk(relaxed = true)

        with(SoundUtils) {
            tts.routeToMediaStream()
        }

        verify(exactly = 1) { tts.setAudioAttributes(any()) }
    }
}
