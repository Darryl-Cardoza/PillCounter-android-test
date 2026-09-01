package com.rite.pillcounting.core.scanning.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: Executor
) {
    private val logger = AppLogger.create<CameraHelper>()
    private val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
        ProcessCameraProvider.getInstance(context)

    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var boundCamera: Camera? = null
    private var previewView: PreviewView? = null
    private var boundCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Reused across rebinds (lens switch, rotation, stall watchdog) — a fresh
    // executor per bind leaked one thread each time. Shut down in [pauseCamera].
    private var analyzerExecutor: ExecutorService? = null

    private val isBound = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(true)

    // Last display rotation pushed via setTargetRotation. Used to detect an
    // actual rotation change so we can rebind the use cases (see setTargetRotation).
    private var lastAppliedRotation = ROTATION_UNSET

    /** Drives the periodic autofocus re-trigger; cancelled on pause/teardown. */
    private val focusScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var focusJob: Job? = null

    /**
     * How often to re-trigger center autofocus. A single startup
     * startFocusAndMetering() locks focus on whatever was centered at bind time
     * (usually an empty scene) for the metering duration, so a bottle presented
     * afterward stays soft until focus re-converges. Re-triggering on this
     * cadence keeps focus locked onto the currently-centered object — the bound
     * for "present a bottle → sharp" instead of waiting on lazy continuous AF.
     */
    private val autofocusIntervalMs = 1500L

    // onUndeliveredElement closes any frame the CONFLATED buffer overwrites before a slow
    // collector reads it — without it, ImageAnalysis's KEEP_ONLY_LATEST strategy stalls
    // forever once its buffer pool is exhausted by never-closed ImageProxy instances.
    private val _frameChannel = Channel<ImageProxy>(Channel.CONFLATED, onUndeliveredElement = { it.close() })
    val frameFlow = _frameChannel.receiveAsFlow()
    private var imageCapture: ImageCapture? = null

    // A capture's callback lands hundreds of ms after the request, so callers
    // cannot tell a capture is still running. Refuse a second takePicture()
    // until the in-flight one reports success or failure.
    private val isCapturing = AtomicBoolean(false)
    // ---------------------------------------------------------
    // CAMERA STATE + ZOOM FLOW
    // ---------------------------------------------------------

    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState = _cameraState.asStateFlow()

    // public observable zoomFlow
    private val _zoomFlow = MutableStateFlow(1f)
    val zoomFlow = _zoomFlow.asStateFlow()

    data class CameraState(
        val isTorchOn: Boolean = false,
        val zoomRatio: Float = 1.0f,
        val minZoomRatio: Float = 1.0f,
        val maxZoomRatio: Float = 1.0f
    )

    companion object {
        // Sentinel: no rotation has been pushed via setTargetRotation yet.
        private const val ROTATION_UNSET = -1
    }

    // ---------------------------------------------------------
    // START CAMERA
    // ---------------------------------------------------------

    fun startCamera(
        previewView: PreviewView,
        targetResolution: Size = Size(1280, 720),
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    ) {
        logger.i("Starting camera | Target=${targetResolution.width}×${targetResolution.height}")
        this.previewView = previewView
        this.boundCameraSelector = cameraSelector

        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                logger.e("Failed to get CameraProvider", e)
                return@addListener
            }

            if (isBound.get()) {
                logger.w("Camera already bound — skipping")
                return@addListener
            }

            try {
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetResolution,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                        )
                    )
                    .build()

                preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                // Seed targetRotation from the current display so the first frames
                // after bind are already oriented correctly. The activity has
                // configChanges=orientation set, so CameraX will not auto-update
                // targetRotation on rotation — setTargetRotation() must be called
                // when the orientation changes (see CameraHelper.setTargetRotation).
                val initialRotation = previewView.display?.rotation ?: 0

                imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setOutputImageRotationEnabled(true)
                    .setTargetRotation(initialRotation)
                    .build()
                    .also { analysis ->
                        val analyzerExec = analyzerExecutor?.takeUnless { it.isShutdown }
                            ?: Executors.newSingleThreadExecutor().also { analyzerExecutor = it }
                        analysis.setAnalyzer(analyzerExec) {
                            processImageProxy(it)
                        }
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    boundCameraSelector,
                    preview,
                    imageAnalysis,
                    imageCapture
                )

                observeCameraState()
                isBound.set(true)
                isStreaming.set(true)

                logger.i("Camera successfully bound")

                // initial zoom
                val zoomInit =
                    boundCamera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                _zoomFlow.value = zoomInit

                setCenterFocus(previewView)
                startPeriodicFocus(previewView)

            } catch (e: Exception) {
                logger.e("Failed binding camera", e)
                isBound.set(false)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Switches the active camera lens (e.g. front ↔ back), rebinding the
     * preview if one is already running. `startCamera()` early-returns while
     * [isBound] is true (see its "already bound — skipping" guard above), so
     * calling it a second time to change lenses is a silent no-op — this
     * unbinds first so the new selector actually takes effect.
     */
    fun switchCamera(previewView: PreviewView, cameraSelector: CameraSelector) {
        if (!isBound.get()) {
            startCamera(previewView, cameraSelector = cameraSelector)
            return
        }
        try {
            cameraProviderFuture.get().unbindAll()
        } catch (e: Exception) {
            logger.w("Unbind before camera switch failed: ${e.message}")
        }
        preview = null
        imageAnalysis = null
        boundCamera = null
        isBound.set(false)
        isStreaming.set(false)
        startCamera(previewView, cameraSelector = cameraSelector)
    }

    // ---------------------------------------------------------
    // FRAME PROCESSING
    // ---------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    private fun processImageProxy(image: ImageProxy) {
        try {
            if (!isStreaming.get()) {
                image.close()
                return
            }

            if (!_frameChannel.isClosedForSend) {
                if (!_frameChannel.trySend(image).isSuccess) {
                    image.close()
                }
            } else image.close()

        } catch (t: Throwable) {
            logger.e("Analyzer error", t)
            image.close()
        }
    }

    // ---------------------------------------------------------
    // OBSERVE CAMERA STATE (zoom + torch)
    // ---------------------------------------------------------

    private fun observeCameraState() {
        val cameraInfo = boundCamera?.cameraInfo ?: return

        cameraInfo.torchState.observe(lifecycleOwner) { torch ->
            _cameraState.update { it.copy(isTorchOn = torch == TorchState.ON) }
        }

        cameraInfo.zoomState.observe(lifecycleOwner) { state ->
            _cameraState.update {
                it.copy(
                    zoomRatio = state.zoomRatio,
                    minZoomRatio = state.minZoomRatio,
                    maxZoomRatio = state.maxZoomRatio
                )
            }

            // Emit zoom to UI
            _zoomFlow.value = state.zoomRatio
        }
    }

    // ---------------------------------------------------------
    // ZOOM CONTROL
    // ---------------------------------------------------------

    fun setZoom(zoomRatio: Float) {
        try {
            val cam = boundCamera ?: return
            val zoomState = cam.cameraInfo.zoomState.value ?: return

            val newZoom = zoomRatio.coerceIn(
                zoomState.minZoomRatio,
                zoomState.maxZoomRatio
            )

            cam.cameraControl.setZoomRatio(newZoom)
            _zoomFlow.value = newZoom

            logger.i("Zoom set → $newZoom")

        } catch (e: Exception) {
            logger.e("Zoom failed", e)
        }
    }

    fun getCurrentZoomRatio(): Float? =
        boundCamera?.cameraInfo?.zoomState?.value?.zoomRatio

    /**
     * Push the current display rotation down to ImageAnalysis. Required when the
     * activity handles orientation in configChanges (no recreate), otherwise the
     * analyzer keeps emitting frames in the orientation captured at bind time and
     * downstream pixel coords go out of sync with the rotated preview.
     *
     * Pass a Surface.ROTATION_* constant (typically previewView.display.rotation).
     */
    fun setTargetRotation(rotation: Int) {
        imageAnalysis?.targetRotation = rotation
        imageCapture?.targetRotation = rotation

        // Updating targetRotation alone does not reliably re-rotate an already-bound
        // ImageAnalysis output stream: the analyzer can keep emitting frames in the
        // orientation captured at bind time, so the tray box + pill centroids stay
        // mapped to the previous orientation after the device rotates. When the
        // rotation actually changes, rebind the use cases so a fresh ImageAnalysis is
        // created with the new rotation (its builder seeds the current display
        // rotation at bind time). Skip the very first push (lastAppliedRotation unset),
        // which only aligns the freshly-bound stream.
        if (isBound.get() &&
            lastAppliedRotation != ROTATION_UNSET &&
            rotation != lastAppliedRotation
        ) {
            previewView?.let { rebind(it) }
        }
        lastAppliedRotation = rotation
    }

    /**
     * Tear down and re-bind the camera use cases. Used on a display rotation change
     * so ImageAnalysis starts emitting frames in the new orientation immediately.
     */
    private fun rebind(previewView: PreviewView) {
        logger.i("Rebinding camera for rotation change")
        try {
            cameraProviderFuture.get().unbindAll()
        } catch (e: Exception) {
            logger.w("Unbind before rotation rebind failed: ${e.message}")
        }
        preview = null
        imageAnalysis = null
        boundCamera = null
        isBound.set(false)
        isStreaming.set(false)
        startCamera(previewView, cameraSelector = boundCameraSelector)
    }

    // ---------------------------------------------------------
    // AUTO FOCUS
    // ---------------------------------------------------------

    fun setCenterFocus(previewView: PreviewView) {
        try {
            val cam = boundCamera ?: return
            // Skip until the preview has been measured — a 0×0 metering point is
            // meaningless and throws on some devices.
            if (previewView.width == 0 || previewView.height == 0) return
            val factory = previewView.meteringPointFactory
            val center = factory.createPoint(
                previewView.width / 2f,
                previewView.height / 2f
            )

            // Auto-cancel after roughly one refocus cycle so a triggered AF lock
            // never outlives the next re-trigger (see [startPeriodicFocus]); a
            // long lock here is what kept a stale focus plane on screen.
            val action = FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build()

            cam.cameraControl.startFocusAndMetering(action)

        } catch (e: Exception) {
            logger.e("Autofocus failed", e)
        }
    }

    /**
     * Periodically re-triggers center autofocus while the camera is streaming.
     * This is what makes "present a bottle → it sharpens" fast: a one-shot AF at
     * bind time locks onto the empty startup scene, so without a re-trigger the
     * label stays soft until that lock expires. Cancelled in [pauseCamera].
     */
    private fun startPeriodicFocus(previewView: PreviewView) {
        focusJob?.cancel()
        focusJob = focusScope.launch {
            while (isActive) {
                delay(autofocusIntervalMs)
                if (isStreaming.get() && isBound.get()) {
                    setCenterFocus(previewView)
                }
            }
        }
    }

    // ---------------------------------------------------------
    // PAUSE / RESUME
    // ---------------------------------------------------------

    fun pauseCamera() {
        logger.i("Pausing camera")
        focusJob?.cancel()
        focusJob = null
        try {
            val provider = cameraProviderFuture.get()
            provider.unbindAll()
        } catch (_: Exception) {
            logger.i("Failed to pause camera")
        }

        preview = null
        imageAnalysis = null
        boundCamera = null
        isBound.set(false)
        isStreaming.set(false)
        isCapturing.set(false)
        analyzerExecutor?.shutdown()
        analyzerExecutor = null
    }

    fun resumeCamera(
        previewView: PreviewView,
        targetResolution: Size = Size(1280, 720)
    ) {
        startCamera(previewView, targetResolution)
    }

    // ---------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------

    fun getPreviewWidth(): Int = previewView?.width ?: 640
    fun getPreviewHeight(): Int = previewView?.height ?: 640

    fun imageProxyToBitmap(image: ImageProxy): Bitmap {

        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotationDegrees = image.imageInfo.rotationDegrees

        if (rotationDegrees == 0) return bitmap

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    /**
     * Request a still photo.
     *
     * @return true when a capture request was issued — exactly one of
     *   [onCaptured] / [onCaptureError] will then run. Returns false when the
     *   request was refused (no camera bound, or a capture already in flight),
     *   in which case neither callback runs.
     */
    fun captureImage(
        onCaptured: (Bitmap) -> Unit,
        onCaptureError: (Throwable) -> Unit = {}
    ): Boolean {

        val imageCapture = imageCapture ?: return false

        if (!isCapturing.compareAndSet(false, true)) {
            logger.w("Capture already in flight — ignoring duplicate request")
            return false
        }

        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {
                    isCapturing.set(false)

                    val bitmap = imageProxyToBitmap(image)
                    onCaptured(bitmap)

                    image.close()
                }

                // Without clearing the flag here a single failed capture would
                // leave the shutter refusing every later tap.
                override fun onError(exception: ImageCaptureException) {
                    isCapturing.set(false)
                    logger.e("Capture failed", exception)
                    onCaptureError(exception)
                }
            }
        )

        return true
    }
}