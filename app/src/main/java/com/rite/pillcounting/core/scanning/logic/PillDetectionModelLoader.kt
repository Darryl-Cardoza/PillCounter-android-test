package com.rite.pillcounting.core.scanning.logic

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.rite.pillcounting.core.security.ModelDecryptor
import com.rite.pillcounting.core.security.ModelKeyUnit
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedModels(
    val pillInterpreter: Interpreter,
    val traySegDetector: TraySegmentationDetector?,
    val gloveInterpreter: Interpreter?
)

private data class InterpreterHolder(
    val interpreter: Interpreter,
    val gpuDelegate: GpuDelegate? = null
) {
    val usesGpu: Boolean get() = gpuDelegate != null
}

@Singleton
class PillDetectionModelLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val performanceLogger: PerformanceLogger
) {

    private val logger = AppLogger("PillModelLoader")
    private val mutex = Mutex()
    private val modelKeyUnit = ModelKeyUnit(context).also { it.activateIfNeeded() }

    private var pillInterpreter: Interpreter? = null
    private var traySegDetector: TraySegmentationDetector? = null
    private var gloveInterpreter: Interpreter? = null

    // One delegate per interpreter — TFLite does not support sharing a delegate
    // across multiple Interpreter instances (silent wrong outputs if you try).
    private var pillGpuDelegate: GpuDelegate? = null
    private var trayGpuDelegate: GpuDelegate? = null
    private var gloveGpuDelegate: GpuDelegate? = null

    companion object {
        // PP-YOLOE+s pill detector, HardSwish-activated, FP16 weights.
        // Retrained for Mali r38p1 compatibility (no SiLU; sigmoid+broadcast-mul
        // pattern rejected by the M14's OpenCL driver). 100% GPU-delegated:
        // 79xConv2D + 61xHardSwish + 15xLogistic, no SILU/SWISH/RESIZE_BILINEAR.
        // ~14 MB encrypted (down from ~28 MB FP32). Best val mAP@0.5:0.95 = 0.596.
        // Inference is decoded in Postprocessor — DFL softmax + anchor grid + NMS on CPU.
        private const val PILL_MODEL_FILENAME = "pills_detector_fp16.tflite"
        // MobileNetV2-UNet semantic seg, FP16 weights, 384x384 input, in-graph
        // ImageNet normalization. Replaces the prior RTMDet-Ins ONNX tray on
        // ORT-CPU. Tray now runs on TFLite GPU OpenCL — Mali-friendly op set
        // (Conv/ReLU6/BN/Add/Concat/Resize-nearest only).
        private const val TRAY_MODEL_FILENAME = "tray_detector_fp16.tflite"
        // YOLOX-Nano gloves / no_gloves detector (LeakyReLU, 6x6-Conv stem, 320 input).
        // Replaces the MobileNetV2 binary classifier — the classifier had no
        // training signal for empty scenes (every training image contained a
        // hand or glove) and false-positived on no-hand frames. The detector
        // restores implicit-negatives: empty scene -> zero boxes above threshold
        // -> "no gloves" at the app's compliance gate. ~3.5 MB encrypted FP32.
        // Designed for 100% GPU delegation on Mali r38p1 (validate with
        // validate_gpu_delegate.py). Inference is decoded in GloveDetector —
        // sigmoid + box decode + per-class NMS on CPU.
        // AES-GCM RITE encryption, same scheme as pill + tray.
        private const val GLOVE_MODEL_FILENAME = "gloves_fp16.tflite"
        private const val TAG = "LoadModel"

        private const val TRAY_MODEL_ENABLED = true

        private const val MAX_CPU_THREADS = 4
        private const val WARMUP_RUNS = 3
    }

    suspend fun getOrLoadInterpreters(includeGlove: Boolean = true): LoadedModels {
        logger.i("getOrLoadInterpreters() called (includeGlove=$includeGlove)")

        return mutex.withLock {
            val existingPill  = pillInterpreter
            val existingTray  = traySegDetector
            val existingGlove = gloveInterpreter

            if (!includeGlove && existingPill != null) {
                Log.i(TAG, "Pill already loaded — returning cached (tray=${existingTray != null})")
                return@withLock LoadedModels(existingPill, existingTray, null)
            }
            if (includeGlove && existingPill != null && existingGlove != null) {
                Log.i(TAG, "Pill + glove already loaded — returning cached (tray=${existingTray != null})")
                return@withLock LoadedModels(existingPill, existingTray, existingGlove)
            }

            logger.i("One or more interpreters missing — loading models")
            logger.i(if (includeGlove) "Loading pill + tray + glove interpreters" else "Loading pill + tray interpreters (glove deferred)")

            performanceLogger.logPerformanceSnapshot("PRE_MODEL_LOAD")

            withContext(Dispatchers.IO) {
                coroutineScope {
                    val pillLoadStart = System.currentTimeMillis()
                    val pillBytesDeferred = async { loadModelBytes(PILL_MODEL_FILENAME) }

                    val trayLoadStart = System.currentTimeMillis()
                    val trayBytesDeferred = async<ByteArray?> {
                        if (!TRAY_MODEL_ENABLED) {
                            Log.w(TAG, "Tray model TEMPORARILY DISABLED — pill-only inference for speed testing")
                            return@async null
                        }
                        try { loadModelBytes(TRAY_MODEL_FILENAME) } catch (e: Exception) {
                            Log.w(TAG, "Tray TFLite missing — continuing without tray: ${e.message}")
                            null
                        }
                    }

                    val gloveBytesDeferred = if (includeGlove) {
                        // YOLOX-Nano gloves detector — AES-GCM RITE encrypted, same as pill + tray.
                        logger.i("GLOVE_MODEL — loading asset '$GLOVE_MODEL_FILENAME.enc'")
                        async { loadModelBytes(GLOVE_MODEL_FILENAME) }
                    } else null

                    val pillBytes   = pillBytesDeferred.await()
                    val pillBufferTime = System.currentTimeMillis() - pillLoadStart

                    val trayBytes   = trayBytesDeferred.await()
                    val trayBufferTime = System.currentTimeMillis() - trayLoadStart

                    val gloveBytes  = gloveBytesDeferred?.await()
                    val gloveBufferTime = System.currentTimeMillis() - pillLoadStart

                    Log.i(TAG, "Model bytes decrypted (tray=${trayBytes != null} glove=${gloveBytes != null})")
                    if (includeGlove) {
                        logger.i("GLOVE_MODEL — decrypt ${if (gloveBytes != null) "OK" else "FAILED"} size=${gloveBytes?.size ?: 0} bytes")
                    }

                    val pillBuffer = bytesToDirectBuffer(pillBytes)
                    val trayBuffer = trayBytes?.let { bytesToDirectBuffer(it) }
                    val gloveBuffer = gloveBytes?.let { bytesToDirectBuffer(it) }

                    val pillInterpreterStart = System.currentTimeMillis()
                    val pillHolder = createInterpreterWithGpuFallback(pillBuffer, "Pill model")
                    pillGpuDelegate = pillHolder.gpuDelegate
                    val pillInterpreterTime = System.currentTimeMillis() - pillInterpreterStart

                    performanceLogger.logModelLoad(
                        modelName = "Pill Detection Model",
                        loadedOn = if (pillHolder.usesGpu) "GPU" else "CPU+XNNPACK",
                        interpreter = pillHolder.interpreter,
                        modelSizeBytes = pillBuffer.capacity().toLong(),
                        loadTimeMs = pillBufferTime + pillInterpreterTime,
                        gpuDelegateEnabled = pillHolder.usesGpu
                    )

                    // Tray model: prefer GPU (TFLite OpenCL on Mali) but fall back
                    // to CPU+XNNPACK if the device doesn't support the GPU delegate.
                    // The model's op set is universally supported on both backends —
                    // it'll just be slower on CPU. If init fails entirely we continue
                    // without tray (pill counting still works, just no spatial filter).
                    val trayDetector: TraySegmentationDetector? = if (trayBuffer != null) {
                        val trayCreateStart = System.currentTimeMillis()
                        val holder = try {
                            createInterpreterWithGpuFallback(trayBuffer, "Tray model")
                        } catch (t: Throwable) {
                            Log.e(TAG, "Tray init failed (both GPU and CPU) — continuing without tray", t)
                            null
                        }
                        val trayCreateTime = System.currentTimeMillis() - trayCreateStart
                        if (holder != null) {
                            trayGpuDelegate = holder.gpuDelegate
                            performanceLogger.logModelLoad(
                                modelName = "Tray Segmentation Model (MobileNetV2-UNet)",
                                loadedOn = if (holder.usesGpu) "GPU" else "CPU+XNNPACK",
                                interpreter = holder.interpreter,
                                modelSizeBytes = trayBuffer.capacity().toLong(),
                                loadTimeMs = trayBufferTime + trayCreateTime,
                                gpuDelegateEnabled = holder.usesGpu
                            )
                            logTensorInfo(holder.interpreter, "Tray model")
                            Log.i(TAG, "Tray model — ✅ TFLite ${if (holder.usesGpu) "GPU" else "CPU+XNNPACK"} ready (load=${trayBufferTime}ms init=${trayCreateTime}ms size=${trayBuffer.capacity() / 1024} KB)")
                            TraySegmentationDetector(holder.interpreter)
                        } else {
                            Log.i(TAG, "Tray skipped (init failed) — pill counting will not be tray-filtered")
                            null
                        }
                    } else {
                        Log.i(TAG, "Tray skipped (no bytes) — pill counting will not be tray-filtered")
                        null
                    }

                    val gloveHolder = if (gloveBuffer != null) {
                        val gloveInterpreterStart = System.currentTimeMillis()
                        val holder = createInterpreterWithGpuFallback(gloveBuffer, "Glove model")
                        gloveGpuDelegate = holder.gpuDelegate
                        val gloveInterpreterTime = System.currentTimeMillis() - gloveInterpreterStart
                        performanceLogger.logModelLoad(
                            modelName = "Glove Detection Model (YOLOX-Nano)",
                            loadedOn = if (holder.usesGpu) "GPU" else "CPU+XNNPACK",
                            interpreter = holder.interpreter,
                            modelSizeBytes = gloveBuffer.capacity().toLong(),
                            loadTimeMs = gloveBufferTime + gloveInterpreterTime,
                            gpuDelegateEnabled = holder.usesGpu
                        )
                        logTensorInfo(holder.interpreter, "GLOVE_MODEL")
                        gloveInterpreter = holder.interpreter
                        holder
                    } else null

                    logTensorInfo(pillHolder.interpreter, "Pill model")

                    pillInterpreter   = pillHolder.interpreter
                    traySegDetector   = trayDetector

                    val tfliteHolders = listOfNotNull(pillHolder, gloveHolder)
                    val totalLoaded = tfliteHolders.size + (if (trayDetector != null) 1 else 0)
                    Log.i(TAG, "$totalLoaded model(s) initialized successfully")
                    logger.i("Models ready — pill=✓ tray=${trayDetector != null} glove=${gloveHolder != null}")

                    val gpuCount = tfliteHolders.count { it.gpuDelegate != null } + (if (trayDetector != null) 1 else 0)
                    val tfliteTotal = tfliteHolders.size + (if (trayDetector != null) 1 else 0)
                    Log.i(TAG, "📊 TFLite delegates — GPU=$gpuCount / $tfliteTotal (tray=${if (trayDetector != null) "GPU" else "absent"})")

                    performanceLogger.logPerformanceSnapshot("POST_MODEL_LOAD")

                    // Warm-up: GPU shader compile happens on first inference for
                    // each TFLite interpreter. Burning the cost here (with
                    // serialization-to-disk via `setSerializationParams`) keeps
                    // the first user-visible frame fast.
                    warmUp(
                        pill = pillHolder.interpreter,
                        glove = gloveHolder?.interpreter,
                        tray = trayDetector
                    )

                    LoadedModels(
                        pillInterpreter  = pillHolder.interpreter,
                        traySegDetector  = trayDetector,
                        gloveInterpreter = gloveHolder?.interpreter
                    )
                }
            }
        }
    }

    /**
     * Builds an Interpreter using the GPU delegate when supported on this device
     * (backend auto-selected by CompatibilityList — OpenCL preferred, OpenGL
     * fallback); falls back to CPU+XNNPACK on any GPU init failure.
     *
     * Must stay on Dispatchers.IO. Do NOT wrap in withContext(Dispatchers.Main) —
     * GPU shader compile takes several seconds and would trip Android's
     * 10-second input dispatcher ANR (observed on Galaxy A14 5G, 2025-05-26).
     */
    private suspend fun createInterpreterWithGpuFallback(
        modelBuffer: ByteBuffer,
        modelName: String
    ): InterpreterHolder = withContext(Dispatchers.IO) {
        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            var gpu: GpuDelegate? = null
            try {
                val gpuOpts = compatList.bestOptionsForThisDevice.apply {
                    isPrecisionLossAllowed = true
                    inferencePreference = GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
                    setQuantizedModelsAllowed(true)
                    try {
                        setSerializationParams(
                            context.cacheDir.absolutePath,
                            "${modelName.lowercase().replace(' ', '_')}_v1"
                        )
                    } catch (_: Throwable) { /* older tflite-gpu without serialization */ }
                }
                gpu = GpuDelegate(gpuOpts)
                val opts = Interpreter.Options().apply {
                    addDelegate(gpu)
                    setUseXNNPACK(false)
                    setAllowFp16PrecisionForFp32(true)
                    setNumThreads(1)
                }
                val interpreter = Interpreter(modelBuffer.duplicateAndRewind(), opts)
                Log.i(TAG, "$modelName — ✅ initialized on GPU")
                return@withContext InterpreterHolder(interpreter, gpuDelegate = gpu)
            } catch (t: Throwable) {
                Log.e(TAG, "$modelName — GPU init failed, falling back to CPU+XNNPACK", t)
                try { gpu?.close() } catch (_: Throwable) {}
            }
        } else {
            Log.i(TAG, "$modelName — GPU not supported on this device, using CPU+XNNPACK")
        }

        val cpuOpts = Interpreter.Options().apply {
            setUseXNNPACK(true)
            numThreads = Runtime.getRuntime().availableProcessors()
                .coerceAtMost(MAX_CPU_THREADS)
        }
        val interpreter = Interpreter(modelBuffer.duplicateAndRewind(), cpuOpts)
        Log.i(TAG, "$modelName — ✅ initialized on CPU+XNNPACK")
        InterpreterHolder(interpreter)
    }


    /**
     * Runs [WARMUP_RUNS] dummy inferences against each loaded model.
     *
     * - TFLite GPU delegate: compiles OpenCL shaders for every op in the graph
     *   on first call. On A14 5G this takes ~1-2 seconds the first time; with
     *   `setSerializationParams` (set in [createInterpreterWithGpuFallback])
     *   the binaries are cached to disk and subsequent app launches cost
     *   ~200-500 ms. Either way, doing it here keeps the first user-visible
     *   frame fast.
     * - ONNX Runtime: kernel selection + workspace allocation happen on the
     *   first session.run; warming up amortizes this too.
     *
     * Failures here are swallowed — if the warm-up itself errors, the real
     * inference will hit the same error and produce a more contextual log.
     */
    private fun warmUp(
        pill: Interpreter,
        glove: Interpreter?,
        tray: TraySegmentationDetector?
    ) {
        val tStart = System.currentTimeMillis()
        try {
            warmUpTfliteInterpreter(pill, "Pill")
        } catch (t: Throwable) {
            Log.w(TAG, "Pill warm-up failed (continuing): ${t.message}")
        }
        if (glove != null) {
            try {
                warmUpTfliteInterpreter(glove, "Glove")
            } catch (t: Throwable) {
                Log.w(TAG, "Glove warm-up failed (continuing): ${t.message}")
            }
        }
        if (tray != null) {
            try {
                warmUpTrayDetector(tray)
            } catch (t: Throwable) {
                Log.w(TAG, "Tray warm-up failed (continuing): ${t.message}")
            }
        }
        Log.i(TAG, "Warm-up complete in ${System.currentTimeMillis() - tStart}ms")
    }

    private fun warmUpTfliteInterpreter(interpreter: Interpreter, label: String) {
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val inputFloats = inputShape.fold(1) { acc, d -> acc * d }
        val inputBuf = ByteBuffer.allocateDirect(inputFloats * 4)
            .order(ByteOrder.nativeOrder())

        val outputs = HashMap<Int, Any>(interpreter.outputTensorCount)
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            outputs[i] = allocOutputArray(shape)
        }

        repeat(WARMUP_RUNS) { idx ->
            inputBuf.rewind()
            val t0 = System.currentTimeMillis()
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)
            Log.i(TAG, "$label warm-up run ${idx + 1}/$WARMUP_RUNS: ${System.currentTimeMillis() - t0}ms")
        }
    }

    private fun warmUpTrayDetector(detector: TraySegmentationDetector) {
        // Allocate a single 640x640 dummy bitmap for warmup (the detector
        // expects the same 640 letterbox the per-frame pipeline produces).
        // Recycle it after warmup to free 1.6 MB immediately.
        val dummyBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val dummyScaleInfo = Letterbox.ScaleInfo(
            scale = 1f, padX = 0f, padY = 0f, inputSize = 640
        )
        try {
            repeat(WARMUP_RUNS) { idx ->
                val t0 = System.currentTimeMillis()
                detector.detect(
                    letterboxedBitmap = dummyBitmap,
                    scaleInfo640 = dummyScaleInfo,
                    originalWidth = 640,
                    originalHeight = 640
                )
                Log.i(TAG, "Tray warm-up run ${idx + 1}/$WARMUP_RUNS: ${System.currentTimeMillis() - t0}ms")
            }
        } finally {
            try { dummyBitmap.recycle() } catch (_: Throwable) {}
        }
    }

    private fun allocOutputArray(shape: IntArray): Any = when (shape.size) {
        1 -> FloatArray(shape[0])
        2 -> Array(shape[0]) { FloatArray(shape[1]) }
        3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
        4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
        else -> throw IllegalArgumentException("Unsupported output rank: ${shape.size}")
    }

    private fun bytesToDirectBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }

    private fun logTensorInfo(interpreter: Interpreter, modelName: String) {
        try {
            for (i in 0 until interpreter.inputTensorCount) {
                val tensor = interpreter.getInputTensor(i)
                logger.i("$modelName input[$i] shape=${tensor.shape().contentToString()} type=${tensor.dataType()}")
            }

            for (i in 0 until interpreter.outputTensorCount) {
                val tensor = interpreter.getOutputTensor(i)
                logger.i("$modelName output[$i] shape=${tensor.shape().contentToString()} type=${tensor.dataType()}")
            }
        } catch (e: Exception) {
            logger.e("$modelName — failed to log tensor info", e)
        }
    }

    /**
     * Decrypts a model asset and returns the raw bytes. TFLite wraps these in a
     * direct ByteBuffer via [bytesToDirectBuffer]; ONNX Runtime takes the bytes directly.
     */
    /**
     * Loads model bytes from assets.
     *
     * Two paths:
     *  - `encrypted = true` (default): asset is `<modelName>.enc`, copied to
     *    filesDir on first use and decrypted via [ModelDecryptor] (AES-GCM
     *    RITE format). Used for the pill and tray models.
     *  - `encrypted = false`: asset is `<modelName>` itself, read directly
     *    into memory. Used for the YOLOX-Nano gloves detector, which ships
     *    as a raw .tflite.
     */
    private fun loadModelBytes(modelName: String, encrypted: Boolean = true): ByteArray {
        if (!encrypted) {
            return context.assets.open(modelName).use { it.readBytes() }
        }
        val encFile = File(context.filesDir, "$modelName.enc")
        if (!encFile.exists()) {
            context.assets.open("$modelName.enc").use { input ->
                encFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return ModelDecryptor.decryptToBytes(encFile, modelKeyUnit)
    }

    private fun ByteBuffer.duplicateAndRewind(): ByteBuffer {
        return duplicate().apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }
    }

    private fun safelyCloseDelegate(delegate: GpuDelegate?, label: String) {
        try {
            delegate?.close()
            if (delegate != null) Log.i(TAG, "$label closed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close $label", e)
        }
    }

    /**
     * Releases only the glove model resources. Pill + tray interpreters remain cached.
     */
    fun unloadGloveModel() {
        try {
            gloveInterpreter?.close()
        } catch (e: Exception) {
            logger.e("Failed to close gloveInterpreter during unload", e)
        }
        safelyCloseDelegate(gloveGpuDelegate, "gloveGpuDelegate")
        gloveInterpreter = null
        gloveGpuDelegate = null
        logger.i("Glove model unloaded — pill + tray remain cached")
        logger.i("Glove model resources released")
    }

    fun close() {
        try { pillInterpreter?.close() } catch (e: Exception) {
            Log.e(TAG, "Failed to close pillInterpreter", e)
        }
        try { traySegDetector?.close() } catch (e: Exception) {
            Log.e(TAG, "Failed to close traySegDetector", e)
        }
        try { gloveInterpreter?.close() } catch (e: Exception) {
            Log.e(TAG, "Failed to close gloveInterpreter", e)
        }

        safelyCloseDelegate(pillGpuDelegate, "pillGpuDelegate")
        safelyCloseDelegate(trayGpuDelegate, "trayGpuDelegate")
        safelyCloseDelegate(gloveGpuDelegate, "gloveGpuDelegate")

        pillInterpreter   = null
        traySegDetector   = null
        gloveInterpreter  = null
        pillGpuDelegate   = null
        trayGpuDelegate   = null
        gloveGpuDelegate  = null

        logger.i("All model resources released")
        logger.i("All model resources released and cleared")
    }
}
