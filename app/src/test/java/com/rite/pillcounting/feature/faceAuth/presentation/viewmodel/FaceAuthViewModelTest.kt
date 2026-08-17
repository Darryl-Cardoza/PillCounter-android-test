package com.rite.pillcounting.feature.faceAuth.presentation.viewmodel

import android.content.Context
import android.graphics.Bitmap
import com.rite.pillcounting.core.faceAuth.data.FaceProfileRepository
import com.rite.pillcounting.core.faceAuth.logic.AutoCaptureController
import com.rite.pillcounting.core.faceAuth.logic.FaceEngine
import com.rite.pillcounting.core.faceAuth.logic.FaceQualityGate
import com.rite.pillcounting.core.faceAuth.logic.GalleryEntry
import com.rite.pillcounting.core.faceAuth.logic.SessionLockController
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.faceAuth.domain.model.RegistrationState
import com.rite.pillcounting.feature.faceAuth.domain.model.VerifyState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
        repo: FaceProfileRepository = mockk(relaxed = true),
        sessionEmailProvider: SessionEmailProvider = mockk(relaxed = true),
        faceQualityGate: FaceQualityGate = mockk(),
        autoCaptureController: AutoCaptureController = mockk(relaxed = true),
        sessionLockController: SessionLockController = mockk(relaxed = true),
        preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    ) = FaceAuthViewModel(context, engine, repo, sessionEmailProvider, faceQualityGate, autoCaptureController, sessionLockController, preferenceHelper)

    @Test
    fun `capturing all three angles then finishing enrolls the profile`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        val faceQualityGate = mockk<FaceQualityGate>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        coEvery { repo.registerProfile(any(), any(), any(), any(), any(), any()) } returns 1L
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns null

        val vm = viewModel(engine = engine, repo = repo, faceQualityGate = faceQualityGate)
        vm.startRegistration("Bruce", "Wayne")
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_LEFT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_RIGHT)
        vm.finishRegistration()

        assertTrue(vm.registrationState.value is RegistrationState.Enrolled)
    }

    @Test
    fun `captureFrame rejects a frame the quality gate flags`() = runTest {
        val engine = mockk<FaceEngine>()
        val faceQualityGate = mockk<FaceQualityGate>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns "hold still / more light"

        val vm = viewModel(engine = engine, faceQualityGate = faceQualityGate)
        vm.startRegistration("Bruce", "Wayne")
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)

        val state = vm.registrationState.value
        assertTrue(state is RegistrationState.Rejected)
        assertEquals("hold still / more light", (state as RegistrationState.Rejected).reason)
    }

    @Test
    fun `verifyFrame with a matching gallery entry reports Matched`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        coEvery { repo.loadGallery() } returns listOf(GalleryEntry(faceProfileId = 1L, vec = FloatArray(128) { 1f }))
        coEvery { repo.observeProfiles() } returns flowOf(emptyList())
        coEvery { repo.getProfile(1L) } returns
            FaceProfileEntity(id = 1L, firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L)
        coEvery { repo.markUsed(any(), any()) } returns Unit

        val vm = viewModel(engine = engine, repo = repo)
        vm.startVerify()
        vm.verifyFrame(fakeBitmap)

        val state = vm.verifyState.value
        assertTrue(state is VerifyState.Matched)
        assertEquals("Bruce", (state as VerifyState.Matched).firstName)
    }

    @Test
    fun `verifyFrame with no matching gallery entry reports NotRecognized`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        coEvery { repo.loadGallery() } returns emptyList()
        coEvery { repo.observeProfiles() } returns flowOf(emptyList())

        val vm = viewModel(engine = engine, repo = repo)
        vm.startVerify()
        vm.verifyFrame(fakeBitmap)

        assertTrue(vm.verifyState.value is VerifyState.NotRecognized)
    }

    @Test
    fun `startAutoCapture commits all three angles in sequence`() = runTest {
        val autoCaptureController = mockk<AutoCaptureController>()
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 1f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 2f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_RIGHT, any(), true) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 3f }, fakeBitmap))

        val vm = viewModel(autoCaptureController = autoCaptureController)
        vm.startRegistration("Bruce", "Wayne")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)

        val state = vm.registrationState.value
        assertTrue(state is RegistrationState.Capturing)
        assertEquals(FaceCaptureAngle.entries.size, (state as RegistrationState.Capturing).capturedCount)
    }

    @Test
    fun `startAutoCapture surfaces live guidance text while no candidate has cleared the gates yet`() = runTest {
        val autoCaptureController = mockk<AutoCaptureController>()
        // Non-completing flow: emits the guidance event then suspends so the collect never
        // returns and the loop never advances to TILT_LEFT (which would be unstubbed).
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true) } returns
            flow { emit(AutoCaptureController.CaptureEvent.Guidance("move closer")); awaitCancellation() }

        val vm = viewModel(autoCaptureController = autoCaptureController)
        vm.startRegistration("Bruce", "Wayne")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)

        val state = vm.registrationState.value
        assertTrue(state is RegistrationState.Capturing)
        assertEquals("move closer", (state as RegistrationState.Capturing).guidance)
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
        every { autoCaptureController.run(FaceCaptureAngle.FRONT, any(), true) } returns
            flow { awaitCancellation() }
        every { autoCaptureController.run(FaceCaptureAngle.TILT_LEFT, any(), true) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 2f }, fakeBitmap))
        every { autoCaptureController.run(FaceCaptureAngle.TILT_RIGHT, any(), true) } returns
            flowOf(AutoCaptureController.CaptureEvent.Committed(FloatArray(128) { 3f }, fakeBitmap))

        val vm = viewModel(engine = engine, faceQualityGate = faceQualityGate, autoCaptureController = autoCaptureController)
        vm.startRegistration("Bruce", "Wayne")
        vm.startAutoCapture(flowOf(fakeBitmap), isFrontCamera = true)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)

        val state = vm.registrationState.value
        assertTrue(state is RegistrationState.Capturing)
        assertEquals(FaceCaptureAngle.entries.size, (state as RegistrationState.Capturing).capturedCount)
    }

    @Test
    fun `finishRegistration passes non-null faceImagePath when FRONT bitmap was captured`() = runTest {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "face_test_${System.nanoTime()}").also { it.mkdirs() }
        val context = mockk<Context> { every { filesDir } returns tempDir }
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        val faceQualityGate = mockk<FaceQualityGate>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        every { faceQualityGate.evaluate(fakeBitmap, fakeFace) } returns null
        coEvery { repo.registerProfile(any(), any(), any(), any(), any(), any()) } returns 1L

        val vm = viewModel(context = context, engine = engine, repo = repo, faceQualityGate = faceQualityGate)
        vm.startRegistration("Bruce", "Wayne")
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_LEFT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_RIGHT)
        vm.finishRegistration()

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
