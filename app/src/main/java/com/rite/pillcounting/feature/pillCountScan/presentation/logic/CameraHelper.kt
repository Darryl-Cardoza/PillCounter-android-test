package com.rite.pillcounting.feature.pillCountScan.presentation.logic

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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor
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

    private val isBound = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(true)

    private val _frameChannel = Channel<ImageProxy>(Channel.CONFLATED)
    val frameFlow = _frameChannel.receiveAsFlow()
    private var imageCapture: ImageCapture? = null
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

    // ---------------------------------------------------------
    // START CAMERA
    // ---------------------------------------------------------

    fun startCamera(
        previewView: PreviewView,
        targetResolution: Size = Size(1280, 720)
    ) {
        logger.i("Starting camera | Target=${targetResolution.width}×${targetResolution.height}")
        this.previewView = previewView

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
                        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) {
                            processImageProxy(it)
                        }
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
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

            } catch (e: Exception) {
                logger.e("Failed binding camera", e)
                isBound.set(false)
            }

        }, ContextCompat.getMainExecutor(context))
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
    }

    // ---------------------------------------------------------
    // AUTO FOCUS
    // ---------------------------------------------------------

    fun setCenterFocus(previewView: PreviewView) {
        try {
            val cam = boundCamera ?: return
            val factory = previewView.meteringPointFactory
            val center = factory.createPoint(
                previewView.width / 2f,
                previewView.height / 2f
            )

            val action = FocusMeteringAction.Builder(center)
                .addPoint(center, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()

            cam.cameraControl.startFocusAndMetering(action)

        } catch (e: Exception) {
            logger.e("Autofocus failed", e)
        }
    }

    // ---------------------------------------------------------
    // PAUSE / RESUME
    // ---------------------------------------------------------

    fun pauseCamera() {
        logger.i("Pausing camera")
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

    fun captureImage(onCaptured: (Bitmap) -> Unit) {

        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {

                    val bitmap = imageProxyToBitmap(image)
                    onCaptured(bitmap)

                    image.close()
                }
            }
        )

    }
}
