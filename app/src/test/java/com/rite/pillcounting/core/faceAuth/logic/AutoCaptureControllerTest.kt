package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.Bitmap
import android.graphics.RectF
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.faceAuth.model.FaceGuidance
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureControllerTest {

    private val faceEngine = mockk<FaceEngine>()
    private val faceQualityGate = mockk<FaceQualityGate>()
    private val headPoseEstimator = mockk<HeadPoseEstimator>()
    private val controller = AutoCaptureController(faceEngine, faceQualityGate, headPoseEstimator)

    private val bitmap = mockk<Bitmap>(relaxed = true)
    private val faceBox = FaceBox(rect = RectF(0f, 0f, 100f, 100f), landmarks = FloatArray(10), score = 0.9f)

    @Test
    fun `emits guidance and never commits when no face is ever detected`() = runTest {
        var fakeNow = 0L
        controller.clock = { fakeNow }
        coEvery { faceEngine.detectPrimary(bitmap) } returns null

        // Set fakeNow BEFORE each emit so clock() reads the advanced time when the frame is processed.
        // The throttle (MIN_FRAME_INTERVAL_MS=150, lastProcessedAt=0) requires now >= 150 to process.
        val frames = flow {
            fakeNow = 200; emit(bitmap)
            fakeNow = 400; emit(bitmap)
        }

        val events = controller.run(FaceCaptureAngle.FRONT, frames, isFrontCamera = false).toList()

        assertTrue(events.all { it is AutoCaptureController.CaptureEvent.Guidance })
        assertEquals(2, events.size)
        assertEquals(FaceGuidance.NO_FACE, (events[0] as AutoCaptureController.CaptureEvent.Guidance).guidance)
    }

    @Test
    fun `emits guidance for a wrong pose without committing`() = runTest {
        var fakeNow = 0L
        controller.clock = { fakeNow }
        coEvery { faceEngine.detectPrimary(bitmap) } returns faceBox
        every { faceQualityGate.evaluate(bitmap, faceBox) } returns null
        every { headPoseEstimator.estimateYaw(faceBox.landmarks) } returns 0f
        every { headPoseEstimator.matchesAngle(0f, FaceCaptureAngle.TILT_LEFT, false) } returns false
        every { headPoseEstimator.guidanceFor(FaceCaptureAngle.TILT_LEFT) } returns FaceGuidance.TILT_MORE_LEFT

        // Advance past the throttle window before emitting.
        val frames = flow { fakeNow = 200; emit(bitmap) }

        val events = controller.run(FaceCaptureAngle.TILT_LEFT, frames, isFrontCamera = false).toList()

        assertEquals(1, events.size)
        assertEquals(
            FaceGuidance.TILT_MORE_LEFT,
            (events[0] as AutoCaptureController.CaptureEvent.Guidance).guidance
        )
    }

    @Test
    fun `commits the higher-scoring frame once the settle window elapses`() = runTest {
        var fakeNow = 0L
        controller.clock = { fakeNow }

        val weakFace = faceBox.copy(landmarks = FloatArray(10) { 1f })
        val strongFace = faceBox.copy(landmarks = FloatArray(10) { 2f })
        val bitmapWeak = mockk<Bitmap>(relaxed = true)
        val bitmapStrong = mockk<Bitmap>(relaxed = true)
        val tickBitmap = mockk<Bitmap>(relaxed = true)

        coEvery { faceEngine.detectPrimary(bitmapStrong) } returns strongFace
        coEvery { faceEngine.detectPrimary(bitmapWeak) } returns weakFace
        every { faceQualityGate.evaluate(bitmapStrong, strongFace) } returns null
        every { faceQualityGate.evaluate(bitmapWeak, weakFace) } returns null
        every { faceQualityGate.sharpnessScore(bitmapStrong, strongFace) } returns 100.0
        every { faceQualityGate.sharpnessScore(bitmapWeak, weakFace) } returns 30.0
        every { headPoseEstimator.estimateYaw(strongFace.landmarks) } returns 0f
        every { headPoseEstimator.estimateYaw(weakFace.landmarks) } returns 0f
        every { headPoseEstimator.matchesAngle(0f, FaceCaptureAngle.FRONT, false) } returns true
        every { headPoseEstimator.closeness(0f, FaceCaptureAngle.FRONT, false) } returns 0.9f
        coEvery { faceEngine.embed(bitmapStrong, strongFace) } returns floatArrayOf(1f)

        // Set fakeNow BEFORE each emit so the throttle (MIN_FRAME_INTERVAL_MS=150) doesn't skip frames.
        // bitmapStrong: processed at 200, best=(strongEmbed, bitmapStrong), deadline=200+900=1100
        // bitmapWeak:   processed at 500, lower score, best unchanged
        // tickBitmap:   read at 1100 >= deadline=1100 -> commit before processing the frame
        val frames = flow {
            fakeNow = 200; emit(bitmapStrong)
            fakeNow = 500; emit(bitmapWeak)
            fakeNow = 1100; emit(tickBitmap)
        }

        val events = controller.run(FaceCaptureAngle.FRONT, frames, isFrontCamera = false).toList()

        assertEquals(1, events.size)
        val committed = events[0] as AutoCaptureController.CaptureEvent.Committed
        assertEquals(1f, committed.embedding[0], 1e-4f)
    }
}
