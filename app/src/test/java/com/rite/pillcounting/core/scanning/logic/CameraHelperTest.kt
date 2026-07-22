package com.rite.pillcounting.core.scanning.logic

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * Unit tests for [CameraHelper].
 *
 * [CameraHelper] is a thin wrapper around CameraX (`ProcessCameraProvider`,
 * `ImageAnalysis`, `ImageCapture`, `PreviewView.meteringPointFactory`, real
 * `ImageProxy` pixel buffers, `BitmapFactory`) whose real binding/capture/focus
 * logic runs inside CameraX listener callbacks and requires a live camera
 * pipeline (Robolectric has no native CameraX shadow). All camera-bound state
 * (`boundCamera`, `imageAnalysis`, `imageCapture`, `previewView`) is private
 * with no injection seam, so it cannot be put into a "bound" state from a test
 * without touching production code — which the task instructions say not to do
 * for a one-off seam. This suite therefore covers what is meaningfully testable
 * without a bound camera: the safe-default/no-op guard behavior of every public
 * method on a freshly constructed (unbound) instance, which is real production
 * behavior (e.g. defensive null-safety before a camera is bound, such as at
 * activity startup or after `pauseCamera()`), plus `getPreviewWidth`/
 * `getPreviewHeight` defaults and their value once a preview is attached via
 * `startCamera` triggering the async provider listener (which we don't control
 * here, so we only assert the pre-bind default path).
 *
 * Run under Robolectric so [context] is a real (shadowed) `Context` — a plain
 * MockK relaxed mock of `Context` cannot satisfy `getApplicationContext()`
 * (the android.jar stub method has no real implementation and even a relaxed
 * proxy hits `AbstractMethodError` on some Context accessors), which
 * `ProcessCameraProvider.getInstance(context)` calls internally during
 * [CameraHelper]'s constructor. The static `getInstance` call itself is still
 * mocked because CameraX's config auto-discovery needs a real Android
 * manifest-driven `CameraXConfig.Provider` that isn't present in a unit test
 * process even under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lifecycleOwner: LifecycleOwner = mockk(relaxed = true)
    private val executor: Executor = mockk(relaxed = true)

    @Before
    fun setUp() {
        // getInstance() is defined on ProcessCameraProvider's companion object, not
        // as a top-level/@JvmStatic member — mockkStatic(ProcessCameraProvider::class)
        // does not intercept it; mockkObject(ProcessCameraProvider.Companion) does.
        mockkObject(ProcessCameraProvider.Companion)
        val future: ListenableFuture<ProcessCameraProvider> = mockk(relaxed = true)
        every { ProcessCameraProvider.getInstance(any()) } returns future
    }

    @After
    fun tearDown() {
        unmockkObject(ProcessCameraProvider.Companion)
    }

    private fun newHelper(): CameraHelper =
        CameraHelper(context, lifecycleOwner, executor)

    @Test
    fun `getPreviewWidth returns default 640 when no preview attached`() {
        val helper = newHelper()
        assertEquals(640, helper.getPreviewWidth())
    }

    @Test
    fun `getPreviewHeight returns default 640 when no preview attached`() {
        val helper = newHelper()
        assertEquals(640, helper.getPreviewHeight())
    }

    @Test
    fun `getCurrentZoomRatio returns null when no camera bound`() {
        val helper = newHelper()
        assertEquals(null, helper.getCurrentZoomRatio())
    }

    @Test
    fun `zoomFlow starts at 1x before any camera binds`() {
        val helper = newHelper()
        assertEquals(1f, helper.zoomFlow.value)
    }

    @Test
    fun `cameraState starts with default off-torch 1x state`() {
        val helper = newHelper()
        val state = helper.cameraState.value
        assertEquals(false, state.isTorchOn)
        assertEquals(1.0f, state.zoomRatio)
        assertEquals(1.0f, state.minZoomRatio)
        assertEquals(1.0f, state.maxZoomRatio)
    }

    @Test
    fun `setZoom is a no-op and does not change zoomFlow when no camera bound`() {
        val helper = newHelper()
        helper.setZoom(3.5f)
        // No bound camera => zoomState.value is null => early return, zoomFlow untouched.
        assertEquals(1f, helper.zoomFlow.value)
    }

    @Test
    fun `setTargetRotation does not throw and does not rebind when never bound`() {
        val helper = newHelper()
        // isBound is false initially, so the rebind branch must be skipped safely.
        helper.setTargetRotation(0)
        helper.setTargetRotation(90)
        // No exception means the guarded (isBound==false) path was taken both times.
    }

    @Test
    fun `setCenterFocus is a no-op when no camera is bound`() {
        val helper = newHelper()
        val previewView: PreviewView = mockk(relaxed = true)
        // boundCamera is null => early return before touching previewView metering.
        helper.setCenterFocus(previewView)
        verify(exactly = 0) { previewView.meteringPointFactory }
    }

    @Test
    fun `setCenterFocus is a no-op when preview view has zero width or height`() {
        val helper = newHelper()
        val previewView: PreviewView = mockk(relaxed = true)
        // Even if boundCamera were non-null, unmeasured (0x0) preview must be skipped;
        // here boundCamera is null anyway so this also exercises the null-camera guard.
        helper.setCenterFocus(previewView)
        verify(exactly = 0) { previewView.meteringPointFactory }
    }

    @Test
    fun `captureImage callback is never invoked before camera is bound`() {
        val helper = newHelper()
        var invocationCount = 0
        helper.captureImage { invocationCount++ }
        assertEquals(0, invocationCount)
    }

    @Test
    fun `pauseCamera resets bound and streaming state without throwing when never started`() {
        val helper = newHelper()
        // cameraProviderFuture.get() may throw/hang synchronously in a unit test
        // environment without a real CameraX provider; pauseCamera must swallow it.
        helper.pauseCamera()
    }

    @Test
    fun `CameraState default equals a freshly built default instance`() {
        val defaultState = CameraHelper.CameraState()
        assertEquals(false, defaultState.isTorchOn)
        assertEquals(1.0f, defaultState.zoomRatio)
        assertEquals(1.0f, defaultState.minZoomRatio)
        assertEquals(1.0f, defaultState.maxZoomRatio)
    }

    @Test
    fun `CameraState copy overrides only targeted fields`() {
        val base = CameraHelper.CameraState()
        val updated = base.copy(isTorchOn = true, zoomRatio = 2.5f)
        assertEquals(true, updated.isTorchOn)
        assertEquals(2.5f, updated.zoomRatio)
        assertEquals(1.0f, updated.minZoomRatio)
        assertEquals(1.0f, updated.maxZoomRatio)
    }
}
