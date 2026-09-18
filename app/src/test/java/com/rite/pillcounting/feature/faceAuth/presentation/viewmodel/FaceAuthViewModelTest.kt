package com.rite.pillcounting.feature.faceAuth.presentation.viewmodel

import android.content.Context
import android.graphics.Bitmap
import com.rite.pillcounting.core.faceAuth.data.FaceProfileRepository
import com.rite.pillcounting.core.faceAuth.logic.AutoCaptureController
import com.rite.pillcounting.core.faceAuth.logic.FaceEngine
import com.rite.pillcounting.core.faceAuth.logic.FaceQualityGate
import com.rite.pillcounting.core.faceAuth.logic.FaceTrackContinuityGate
import com.rite.pillcounting.core.faceAuth.logic.GalleryEntry
import com.rite.pillcounting.core.faceAuth.logic.HeadPoseEstimator
import com.rite.pillcounting.core.faceAuth.logic.SessionLockController
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.faceAuth.model.FaceGuidance
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.faceAuth.domain.model.RegistrationState
import com.rite.pillcounting.feature.faceAuth.domain.model.VerifyState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FaceAuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val fakeFace = FaceBox(rect = android.graphics.RectF(0f, 0f, 100f, 100f), landmarks = FloatArray(10), score = 0.9f)
    private val fakeBitmap = mockk<Bitmap>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        context: Context = mockk(relaxed = true),
        engine: FaceEngine = mockk(),
        repo: FaceProfileRepository = mockk(relaxed = true) { coEvery { findExistingMatch(any()) } returns null },
        sessionEmailProvider: SessionEmailProvider = mockk(relaxed = true),
        faceQualityGate: FaceQualityGate = mockk(),
        // Pose check passes by default so manual-capture tests exercise the paths they target.
        headPoseEstimator: HeadPoseEstimator = mockk {
            every { estimateYaw(any()) } returns 0f
            every { matchesAngle(any(), any(), any(), any()) } returns true
            every { effectiveYaw(any(), any()) } answers { if (secondArg()) -firstArg<Float>() else firstArg() }
        },
        autoCaptureController: AutoCaptureController = mockk(relaxed = true),
        sessionLockController: SessionLockController = mockk(relaxed = true),
        preferenceHelper: PreferenceHelper = mockk(relaxed = true),
        trackGate: FaceTrackContinuityGate = mockk(relaxed = true) { every { isBroken } returns false }
    ) = FaceAuthViewModel(context, engine, repo, sessionEmailProvider, faceQualityGate, headPoseEstimator, autoCaptureController, sessionLockController, preferenceHelper, trackGate)

    @Test
    fun `capturing all three angles enrolls the profile without a further call`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        val faceQualityGate = mockk<FaceQualityGate>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        coEvery { repo.registerProfile(any(), any(), any(), any(), any(), any()) } returns 1L
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns null
        coEvery { repo.findExistingMatch(any()) } returns null

        val vm = viewModel(engine = engine, repo = repo, faceQualityGate = faceQualityGate)
        vm.startRegistration("Bruce", "Wayne")
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_LEFT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_RIGHT)

        assertTrue(vm.registrationState.value is RegistrationState.Enrolled)
        // The last angle is the only trigger — nothing else may write a second profile.
        coVerify(exactly = 1) { repo.registerProfile(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `captureFrame rejects a frame the quality gate flags`() = runTest {
        val engine = mockk<FaceEngine>()
        val faceQualityGate = mockk<FaceQualityGate>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns FaceGuidance.HOLD_STILL

        val vm = viewModel(engine = engine, faceQualityGate = faceQualityGate)
        vm.startRegistration("Bruce", "Wayne")
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)

        val state = vm.registrationState.value
        assertTrue(state is RegistrationState.Rejected)
        assertEquals(FaceGuidance.HOLD_STILL, (state as RegistrationState.Rejected).reason)
    }

    @Test
    fun `auto-verify with a matching gallery entry reports Matched`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>()
        val faceQualityGate = mockk<FaceQualityGate>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns null
        coEvery { repo.loadGallery() } returns listOf(GalleryEntry(faceProfileId = 1L, vec = FloatArray(128) { 1f }))
        coEvery { repo.observeProfiles() } returns flowOf(emptyList())
        coEvery { repo.getProfile(1L) } returns
            FaceProfileEntity(id = 1L, firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L)
        coEvery { repo.markUsed(any(), any()) } returns Unit

        val vm = viewModel(engine = engine, repo = repo, faceQualityGate = faceQualityGate)
        vm.startVerify()
        vm.startAutoVerify(flowOf(fakeBitmap))

        val state = vm.verifyState.value
        assertTrue(state is VerifyState.Matched)
        assertEquals("Bruce", (state as VerifyState.Matched).firstName)
    }

    @Test
    fun `auto-verify with no matching gallery entry reports NotRecognized`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>()
        val faceQualityGate = mockk<FaceQualityGate>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns null
        coEvery { repo.loadGallery() } returns emptyList()
        coEvery { repo.observeProfiles() } returns flowOf(emptyList())

        val vm = viewModel(engine = engine, repo = repo, faceQualityGate = faceQualityGate)
        vm.startVerify()
        vm.startAutoVerify(flowOf(fakeBitmap))

        assertTrue(vm.verifyState.value is VerifyState.NotRecognized)
    }

    @Test
    fun `startAutoCapture commits all three angles and enrolls`() = runTest {
        val autoCaptureController = mockk<AutoCaptureController>()
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 1f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 2f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_RIGHT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 3f }, fakeBitmap))

        val vm = viewModel(autoCaptureController = autoCaptureController)
        vm.startRegistration("Bruce", "Wayne")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)

        assertTrue(vm.registrationState.value is RegistrationState.Enrolled)
    }

    @Test
    fun `the FRONT commit's yaw becomes the baseline handed to the next angle`() = runTest {
        val autoCaptureController = mockk<AutoCaptureController>()
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true, 0f, any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 1f }, fakeBitmap, steadyYaw = -0.05f))
        // The baseline must be the averaged, already-mirrored reading the controller
        // sends, passed straight through. Stubbed on -0.05f, so recomputing it from
        // anything else would never match and the verify below would fail.
        every { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true, -0.05f, any()) } returns
            flow { awaitCancellation() }

        val vm = viewModel(autoCaptureController = autoCaptureController)
        vm.startRegistration("Bruce", "Wayne")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)

        verify { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true, -0.05f, any()) }
    }

    @Test
    fun `an already-enrolled face pauses the enrollment on a warning`() = runTest {
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        val autoCaptureController = mockk<AutoCaptureController>(relaxed = true)
        coEvery { repo.findExistingMatch(any()) } returns
            (FaceProfileEntity(id = 7L, firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L) to 0.82f)
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 1f }, fakeBitmap))

        val vm = viewModel(repo = repo, autoCaptureController = autoCaptureController)
        vm.startRegistration("Clark", "Kent")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)

        val state = vm.registrationState.value
        assertTrue(state is RegistrationState.DuplicateWarning)
        assertEquals("Bruce", (state as RegistrationState.DuplicateWarning).firstName)
        // The two tilt angles were never started.
        verify(exactly = 0) { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), any(), any(), any()) }
    }

    @Test
    fun `continuing past the warning resumes at the next angle`() = runTest {
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        val autoCaptureController = mockk<AutoCaptureController>(relaxed = true)
        coEvery { repo.findExistingMatch(any()) } returns
            (FaceProfileEntity(id = 7L, firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L) to 0.82f)
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 1f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true, any(), any()) } returns
            flow { awaitCancellation() }

        val vm = viewModel(repo = repo, autoCaptureController = autoCaptureController)
        vm.startRegistration("Clark", "Kent")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)
        vm.continueAfterDuplicateWarning()

        assertTrue(vm.registrationState.value is RegistrationState.Capturing)
        verify { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true, any(), any()) }
    }

    @Test
    fun `continuing past the warning still enrolls once the remaining angles land`() = runTest {
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        val autoCaptureController = mockk<AutoCaptureController>(relaxed = true)
        coEvery { repo.findExistingMatch(any()) } returns
            (FaceProfileEntity(id = 7L, firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L) to 0.82f)
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 1f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 2f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_RIGHT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 3f }, fakeBitmap))

        val vm = viewModel(repo = repo, autoCaptureController = autoCaptureController)
        vm.startRegistration("Clark", "Kent")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)
        vm.continueAfterDuplicateWarning()

        // The warning pauses the scan; resuming it must still reach the same persist trigger.
        assertTrue(vm.registrationState.value is RegistrationState.Enrolled)
        coVerify(exactly = 1) { repo.registerProfile(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a broken track throws away the captured angles and restarts at FRONT`() = runTest {
        val autoCaptureController = mockk<AutoCaptureController>(relaxed = true)
        // FRONT commits, then the tilt step reports the face was lost. The scan must
        // go back to FRONT rather than keep a capture it can't attribute to one person.
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true, any(), any()) } returnsMany listOf(
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 1f }, fakeBitmap)),
            flow { awaitCancellation() }
        )
        every { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.TrackBroken)

        // Unbroken while FRONT is captured, broken when the tilt step reports it;
        // reset() then clears it, and the restarted FRONT step never completes.
        val trackGate = mockk<FaceTrackContinuityGate>(relaxed = true) {
            every { isBroken } returnsMany listOf(false, true, false)
        }
        val vm = viewModel(autoCaptureController = autoCaptureController, trackGate = trackGate)
        vm.startRegistration("Bruce", "Wayne")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)

        val state = vm.registrationState.value
        assertTrue(state is RegistrationState.Capturing)
        assertEquals(FaceCaptureAngle.FRONT, (state as RegistrationState.Capturing).angle)
        assertEquals(0, state.capturedCount)
        assertEquals(FaceGuidance.SAME_PERSON_REQUIRED, state.guidance)
    }

    @Test
    fun `startAutoCapture surfaces live guidance text while no candidate has cleared the gates yet`() = runTest {
        val autoCaptureController = mockk<AutoCaptureController>()
        // Non-completing flow: emits the guidance event then suspends so the collect never
        // returns and the loop never advances to TILT_LEFT (which would be unstubbed).
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true, any(), any()) } returns
            flow { emit(AutoCaptureController.CaptureEvent.Guidance(FaceGuidance.MOVE_CLOSER)); awaitCancellation() }

        val vm = viewModel(autoCaptureController = autoCaptureController)
        vm.startRegistration("Bruce", "Wayne")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)

        val state = vm.registrationState.value
        assertTrue(state is RegistrationState.Capturing)
        assertEquals(FaceGuidance.MOVE_CLOSER, (state as RegistrationState.Capturing).guidance)
    }

    @Test
    fun `manual capture wins over a still-running auto-capture loop and advances to the next angle`() = runTest {
        val engine = mockk<FaceEngine>()
        val faceQualityGate = mockk<FaceQualityGate>()
        val autoCaptureController = mockk<AutoCaptureController>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns null

        // FRONT's auto-loop never finds a good frame on its own — manual override must win it.
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true, any(), any()) } returns
            flow { awaitCancellation() }
        every { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 2f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_RIGHT, any(), true, any(), any()) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 3f }, fakeBitmap))

        val vm = viewModel(engine = engine, faceQualityGate = faceQualityGate, autoCaptureController = autoCaptureController)
        vm.startRegistration("Bruce", "Wayne")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)

        // Manual FRONT lets the loop run the two tilts, so all three land and the profile enrolls.
        assertTrue(vm.registrationState.value is RegistrationState.Enrolled)
    }

    @Test
    fun `enrolling passes non-null faceImagePath when FRONT bitmap was captured`() = runTest {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "face_test_${System.nanoTime()}").also { it.mkdirs() }
        val context = mockk<Context> { every { filesDir } returns tempDir }
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        val faceQualityGate = mockk<FaceQualityGate>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns null
        coEvery { repo.registerProfile(any(), any(), any(), any(), any(), any()) } returns 1L
        coEvery { repo.findExistingMatch(any()) } returns null

        val vm = viewModel(context = context, engine = engine, repo = repo, faceQualityGate = faceQualityGate)
        vm.startRegistration("Bruce", "Wayne")
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_LEFT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_RIGHT)

        coVerify {
            repo.registerProfile(
                firstName = "Bruce",
                lastName = "Wayne",
                email = any(),
                embeddingsByAngle = any(),
                now = any(),
                faceImagePath = match { it != null && it.endsWith(".jpg") }
            )
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `deleteProfile deletes the face image file from disk`() = runTest {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "face_test_${System.nanoTime()}").also { it.mkdirs() }
        val imageFile = java.io.File(tempDir, "face_test.jpg").also { it.createNewFile() }
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        val profile = FaceProfileEntity(
            id = 1L,
            firstName = "Bruce",
            lastName = "Wayne",
            email = null,
            createdAt = 0L,
            faceImagePath = imageFile.absolutePath
        )

        val vm = viewModel(repo = repo)
        vm.deleteProfile(profile)

        assertFalse(imageFile.exists())
        tempDir.deleteRecursively()
    }
}
