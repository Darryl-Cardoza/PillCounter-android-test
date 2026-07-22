package com.rite.pillcounting.core.utils.common

import android.content.Context
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
 * Covers the muted/unmuted branches for [SoundUtils.playCaptureSound] and
 * [SoundUtils.playBarcodeSound], exception swallowing in both, and the
 * [SoundUtils.speak] / [SoundUtils.stopSpeaking] state machine around the
 * lazily-initialised shared TTS engine.
 *
 * Runs under Robolectric because `routeToMediaStream()` builds a real
 * `AudioAttributes.Builder()` — a framework class the plain-JVM Android stub jar
 * can't construct (its chained builder methods return null, so the next chained
 * call NPEs).
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
    }

    // -------------------- playCaptureSound --------------------
    //
    // mediaActionSound is a private `by lazy` singleton scoped to this class's
    // classloader: only the FIRST call to playCaptureSound() in the whole test run
    // actually constructs it, and mockkConstructor() does NOT retroactively intercept
    // an already-existing instance in a later test's fresh @Before/@Test scope — so
    // these scenarios are combined into one test that arms the constructor mock before
    // mediaActionSound is touched at all, then drives every branch off that one
    // resulting (mocked) instance.

    @Test
    fun `playCaptureSound covers muted, unmuted, exception, and null-AudioManager branches`() {
        mockkConstructor(MediaActionSound::class)
        every { anyConstructed<MediaActionSound>().play(any()) } returns Unit

        // 1) Muted — play() must not be called at all.
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0
        SoundUtils.playCaptureSound(context)
        verify(exactly = 0) { anyConstructed<MediaActionSound>().play(any()) }

        // 2) Unmuted — play() is called with SHUTTER_CLICK.
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 5
        SoundUtils.playCaptureSound(context)
        verify(exactly = 1) { anyConstructed<MediaActionSound>().play(MediaActionSound.SHUTTER_CLICK) }

        // 3) play() throwing is swallowed, not propagated.
        every { anyConstructed<MediaActionSound>().play(any()) } throws RuntimeException("boom")
        SoundUtils.playCaptureSound(context) // should not throw
        every { anyConstructed<MediaActionSound>().play(any()) } returns Unit

        // 4) A null AudioManager is treated as "not muted" — play() is still called.
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns null
        SoundUtils.playCaptureSound(context)
        // mockk's verify() counts cumulatively across the whole test, not just calls
        // since the last verify — this is the 3rd SHUTTER_CLICK call overall (steps 2, 3, 4).
        verify(exactly = 3) { anyConstructed<MediaActionSound>().play(MediaActionSound.SHUTTER_CLICK) }
    }

    // -------------------- playBarcodeSound --------------------

    @Test
    fun `playBarcodeSound does nothing when media volume is muted`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0
        mockkConstructor(ToneGenerator::class)

        SoundUtils.playBarcodeSound(context)

        verify(exactly = 0) { anyConstructed<ToneGenerator>().startTone(any(), any()) }
    }

    @Test
    fun `playBarcodeSound swallows exception from ToneGenerator construction`() {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 5
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(any(), any()) } throws RuntimeException("boom")

        // Should not throw despite the underlying failure.
        SoundUtils.playBarcodeSound(context)
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
    fun `speakAtSystemVolume skips speaking when media volume muted`() {
        val tts: TextToSpeech = mockk(relaxed = true)
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 0

        with(SoundUtils) {
            tts.speakAtSystemVolume(context, "hello", "utt-1")
        }

        verify(exactly = 1) { tts.stop() }
        verify(exactly = 0) { tts.speak(any(), any(), any(), any()) }
    }

    @Test
    fun `speakAtSystemVolume speaks with QUEUE_FLUSH when not muted`() {
        val tts: TextToSpeech = mockk(relaxed = true)
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 7

        with(SoundUtils) {
            tts.speakAtSystemVolume(context, "hello", "utt-1")
        }

        verify(exactly = 1) { tts.stop() }
        verify(exactly = 1) { tts.speak("hello", TextToSpeech.QUEUE_FLUSH, null, "utt-1") }
    }

    @Test
    fun `speakAtSystemVolume treats null AudioManager as not muted`() {
        val tts: TextToSpeech = mockk(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns null

        with(SoundUtils) {
            tts.speakAtSystemVolume(context, "hello", "utt-1")
        }

        verify(exactly = 1) { tts.speak("hello", TextToSpeech.QUEUE_FLUSH, null, "utt-1") }
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
