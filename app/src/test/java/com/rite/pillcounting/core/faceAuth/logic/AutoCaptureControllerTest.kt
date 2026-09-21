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
    private val strayBitmap = mockk<Bitmap>(relaxed = true)
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
        every { headPoseEstimator.effectiveYaw(any(), any()) } answers { firstArg() }
        every { headPoseEstimator.matchesAngle(0f, FaceCaptureAngle.TILT_LEFT, false, 0f) } returns false
        every { headPoseEstimator.guidanceFor(FaceCaptureAngle.TILT_LEFT, any()) } returns FaceGuidance.TILT_MORE_LEFT

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
        every { headPoseEstimator.effectiveYaw(any(), any()) } answers { firstArg() }
        every { headPoseEstimator.matchesAngle(0f, FaceCaptureAngle.FRONT, false, 0f) } returns true
        every { headPoseEstimator.closeness(0f, FaceCaptureAngle.FRONT, false, 0f) } returns 0.9f
        coEvery { faceEngine.embed(bitmapStrong, strongFace) } returns floatArrayOf(1f)

        // The pose must hold before any frame is eligible, so the strong frame repeats
        // until the tracker is satisfied; only then does it become the candidate and
        // start the settle window. The weak frame scores lower and is ignored.
        val frames = flow {
            repeat(PoseStabilityTracker.REQUIRED_STABLE_FRAMES) { fakeNow += 200; emit(bitmapStrong) }
            fakeNow += 200; emit(bitmapWeak)
            fakeNow += AutoCaptureController.SETTLE_WINDOW_MS; emit(tickBitmap)
        }

        val events = controller.run(FaceCaptureAngle.FRONT, frames, isFrontCamera = false).toList()

        val committed = events.filterIsInstance<AutoCaptureController.CaptureEvent.Committed>().single()
        assertEquals(1f, committed.embedding[0], 1e-4f)
    }

    @Test
    fun `the steady yaw averages every passing frame, not just the winner`() = runTest {
        // The winning frame reads 0.30 but the head actually sat around 0.20. Taking
        // the winner alone would skew both tilts for the rest of the enrollment.
        var fakeNow = 0L
        controller.clock = { fakeNow }
        val restFace = faceBox.copy(landmarks = FloatArray(10) { 1f })
        val peakFace = faceBox.copy(landmarks = FloatArray(10) { 2f })
        val restBitmap = mockk<Bitmap>(relaxed = true)
        val tickBitmap = mockk<Bitmap>(relaxed = true)

        coEvery { faceEngine.detectPrimary(restBitmap) } returns restFace
        coEvery { faceEngine.detectPrimary(bitmap) } returns peakFace
        every { faceQualityGate.evaluate(restBitmap, restFace) } returns null
        every { faceQualityGate.evaluate(bitmap, peakFace) } returns null
        every { faceQualityGate.sharpnessScore(restBitmap, restFace) } returns 30.0
        every { faceQualityGate.sharpnessScore(bitmap, peakFace) } returns 200.0
        every { headPoseEstimator.estimateYaw(restFace.landmarks) } returns 0.10f
        every { headPoseEstimator.estimateYaw(peakFace.landmarks) } returns 0.30f
        every { headPoseEstimator.effectiveYaw(any(), any()) } answers { firstArg() }
        every { headPoseEstimator.matchesAngle(any(), FaceCaptureAngle.FRONT, false, 0f) } returns true
        every { headPoseEstimator.closeness(any(), any(), any(), any()) } returns 0.9f
        coEvery { faceEngine.embed(bitmap, peakFace) } returns floatArrayOf(1f)
        coEvery { faceEngine.embed(restBitmap, restFace) } returns floatArrayOf(2f)

        // Three frames at 0.10, one at 0.30 (which wins on sharpness): mean 0.15.
        val frames = flow {
            repeat(3) { fakeNow += 200; emit(restBitmap) }
            fakeNow += 200; emit(bitmap)
            fakeNow += AutoCaptureController.SETTLE_WINDOW_MS; emit(tickBitmap)
        }

        val events = controller.run(FaceCaptureAngle.FRONT, frames, isFrontCamera = false).toList()

        val committed = events.filterIsInstance<AutoCaptureController.CaptureEvent.Committed>().single()
        assertEquals(0.15f, committed.steadyYaw, 1e-4f)
    }

    @Test
    fun `one stray passing frame among failures never commits`() = runTest {
        // Bug 1's regression test. Exactly one frame passes the pose check and every
        // other frame fails; before the stability requirement this committed the step.
        var fakeNow = 0L
        controller.clock = { fakeNow }
        val strayFace = faceBox.copy(landmarks = FloatArray(10) { 7f })

        coEvery { faceEngine.detectPrimary(bitmap) } returns faceBox
        coEvery { faceEngine.detectPrimary(strayBitmap) } returns strayFace
        every { faceQualityGate.evaluate(bitmap, faceBox) } returns null
        every { faceQualityGate.evaluate(strayBitmap, strayFace) } returns null
        every { faceQualityGate.sharpnessScore(strayBitmap, strayFace) } returns 100.0
        every { headPoseEstimator.estimateYaw(faceBox.landmarks) } returns 0f
        every { headPoseEstimator.estimateYaw(strayFace.landmarks) } returns 0.3f
        every { headPoseEstimator.effectiveYaw(any(), any()) } answers { firstArg() }
        every { headPoseEstimator.matchesAngle(0f, FaceCaptureAngle.TILT_LEFT, false, 0f) } returns false
        every { headPoseEstimator.matchesAngle(0.3f, FaceCaptureAngle.TILT_LEFT, false, 0f) } returns true
        every { headPoseEstimator.closeness(any(), any(), any(), any()) } returns 1f
        every { headPoseEstimator.guidanceFor(FaceCaptureAngle.TILT_LEFT, any()) } returns FaceGuidance.TILT_MORE_LEFT

        val frames = flow {
            repeat(20) { index ->
                fakeNow += 200
                emit(if (index == 10) strayBitmap else bitmap)
            }
        }

        val events = controller.run(FaceCaptureAngle.TILT_LEFT, frames, isFrontCamera = false).toList()

        assertTrue(events.none { it is AutoCaptureController.CaptureEvent.Committed })
    }

    @Test
    fun `guidance gets firmer once the user has been stuck on one angle`() = runTest {
        var fakeNow = 0L
        controller.clock = { fakeNow }

        coEvery { faceEngine.detectPrimary(bitmap) } returns faceBox
        every { faceQualityGate.evaluate(bitmap, faceBox) } returns null
        every { headPoseEstimator.estimateYaw(faceBox.landmarks) } returns 0f
        every { headPoseEstimator.effectiveYaw(any(), any()) } answers { firstArg() }
        every { headPoseEstimator.matchesAngle(0f, FaceCaptureAngle.TILT_LEFT, false, 0f) } returns false
        every { headPoseEstimator.guidanceFor(FaceCaptureAngle.TILT_LEFT, false) } returns FaceGuidance.TILT_MORE_LEFT
        every { headPoseEstimator.guidanceFor(FaceCaptureAngle.TILT_LEFT, true) } returns FaceGuidance.TILT_FURTHER_LEFT

        val frames = flow {
            fakeNow = 200; emit(bitmap)
            fakeNow = 200 + AutoCaptureController.ESCALATE_AFTER_MS; emit(bitmap)
        }

        val events = controller.run(FaceCaptureAngle.TILT_LEFT, frames, isFrontCamera = false).toList()
        val hints = events.filterIsInstance<AutoCaptureController.CaptureEvent.Guidance>().map { it.guidance }

        assertEquals(listOf(FaceGuidance.TILT_MORE_LEFT, FaceGuidance.TILT_FURTHER_LEFT), hints)
    }

    @Test
    fun `a broken track ends the step so the scan can start over`() = runTest {
        var fakeNow = 0L
        controller.clock = { fakeNow }
        val trackGate = mockk<FaceTrackContinuityGate>()
        every { trackGate.observe(any()) } returns Unit
        every { trackGate.isBroken } returns true

        coEvery { faceEngine.detectPrimary(bitmap) } returns faceBox

        val frames = flow {
            fakeNow = 200; emit(bitmap)
            fakeNow = 400; emit(bitmap)
        }

        val events = controller.run(
            FaceCaptureAngle.TILT_LEFT, frames, isFrontCamera = false, trackGate = trackGate
        ).toList()

        assertEquals(listOf(AutoCaptureController.CaptureEvent.TrackBroken), events)
    }
}
