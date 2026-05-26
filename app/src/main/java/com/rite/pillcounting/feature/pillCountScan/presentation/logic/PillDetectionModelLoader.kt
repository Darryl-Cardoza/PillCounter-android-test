package com.rite.pillcounting.feature.pillCountScan.domain

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.rite.pillcounting.core.security.ModelDecryptor
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import com.rite.pillcounting.feature.pillCountScan.presentation.logic.Letterbox
import com.rite.pillcounting.feature.pillCountScan.presentation.logic.TrayMasksDetector
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
    val trayMasksDetector: TrayMasksDetector?,
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

    private var pillInterpreter: Interpreter? = null
    private var trayMasksDetector: TrayMasksDetector? = null
    private var gloveInterpreter: Interpreter? = null

    // One delegate per interpreter — TFLite does not support sharing a delegate
    // across multiple Interpreter instances (silent wrong outputs if you try).
    private var pillGpuDelegate: GpuDelegate? = null
    private var gloveGpuDelegate: GpuDelegate? = null

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    companion object {
        private const val PILL_MODEL_FILENAME = "pills_detector_fp32.tflite"
        // rtmdet_ins tray (capped: max 10 detections, score_threshold 0.25 in-graph)
        // → mask tensor peak ~16 MB, down from ~164 MB on the prior export.
        private const val TRAY_MODEL_FILENAME = "rtmdet_ins_tray_tiny_640.onnx"
        private const val GLOVE_MODEL_FILENAME = "gloves_detector_fp32.tflite"
        private const val TAG = "LoadModel"

        private const val TRAY_MODEL_ENABLED = true

        private const val MAX_CPU_THREADS = 4
        private const val TRAY_ORT_THREADS = 2
        private const val WARMUP_RUNS = 3
    }

    suspend fun getOrLoadInterpreters(includeGlove: Boolean = true): LoadedModels {
        Log.i(TAG, "getOrLoadInterpreters() called (includeGlove=$includeGlove)")

        return mutex.withLock {
            val existingPill  = pillInterpreter
            val existingTray  = trayMasksDetector
            val existingGlove = gloveInterpreter

            if (!includeGlove && existingPill != null) {
                Log.i(TAG, "Pill already loaded — returning cached (tray=${existingTray != null})")
                return@withLock LoadedModels(existingPill, existingTray, null)
            }
            if (includeGlove && existingPill != null && existingGlove != null) {
                Log.i(TAG, "Pill + glove already loaded — returning cached (tray=${existingTray != null})")
                return@withLock LoadedModels(existingPill, existingTray, existingGlove)
            }

            Log.i(TAG, "One or more interpreters missing — loading models")
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
                            Log.w(TAG, "Tray ONNX missing — continuing without tray: ${e.message}")
                            null
                        }
                    }

                    val gloveBytesDeferred = if (includeGlove) {
                        async { loadModelBytes(GLOVE_MODEL_FILENAME) }
                    } else null

                    val pillBytes   = pillBytesDeferred.await()
                    val pillBufferTime = System.currentTimeMillis() - pillLoadStart

                    val trayBytes   = trayBytesDeferred.await()
                    val trayBufferTime = System.currentTimeMillis() - trayLoadStart

                    val gloveBytes  = gloveBytesDeferred?.await()
                    val gloveBufferTime = System.currentTimeMillis() - pillLoadStart

                    Log.i(TAG, "Model bytes decrypted (tray=${trayBytes != null} glove=${gloveBytes != null})")

                    val pillBuffer = bytesToDirectBuffer(pillBytes)
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

                    val trayDetector: TrayMasksDetector? = if (trayBytes != null) {
                        val trayCreateStart = System.currentTimeMillis()
                        val detector = try {
                            createTrayMasksDetector(trayBytes)
                        } catch (e: Exception) {
                            Log.e(TAG, "Tray ONNX session init failed — continuing without tray", e)
                            null
                        }
                        val trayCreateTime = System.currentTimeMillis() - trayCreateStart
                        if (detector != null) {
                            Log.i(TAG, "Tray model — ✅ ONNX session ready (load=${trayBufferTime}ms init=${trayCreateTime}ms size=${trayBytes.size / 1024} KB)")
                        }
                        detector
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
                            modelName = "Glove Detection Model (PP-YOLOE+s)",
                            loadedOn = if (holder.usesGpu) "GPU" else "CPU+XNNPACK",
                            interpreter = holder.interpreter,
                            modelSizeBytes = gloveBuffer.capacity().toLong(),
                            loadTimeMs = gloveBufferTime + gloveInterpreterTime,
                            gpuDelegateEnabled = holder.usesGpu
                        )
                        logTensorInfo(holder.interpreter, "Glove model")
                        gloveInterpreter = holder.interpreter
                        holder
                    } else null

                    logTensorInfo(pillHolder.interpreter, "Pill model")

                    pillInterpreter      = pillHolder.interpreter
                    trayMasksDetector    = trayDetector

                    val tfliteHolders = listOfNotNull(pillHolder, gloveHolder)
                    val totalLoaded = tfliteHolders.size + (if (trayDetector != null) 1 else 0)
                    Log.i(TAG, "$totalLoaded model(s) initialized successfully")
                    logger.i("Models ready — pill=✓ tray=${trayDetector != null} glove=${gloveHolder != null}")

                    val gpuCount = tfliteHolders.count { it.gpuDelegate != null }
                    Log.i(TAG, "📊 TFLite delegates — GPU=$gpuCount / ${tfliteHolders.size}; ONNX tray=${if (trayDetector != null) "loaded" else "absent"}")

                    performanceLogger.logPerformanceSnapshot("POST_MODEL_LOAD")

                    // Warm-up: shader compile for GPU delegate and ORT kernel
                    // selection both happen on first inference. Burning them
                    // here keeps the first user-visible frame fast.
                    warmUp(
                        pill = pillHolder.interpreter,
                        glove = gloveHolder?.interpreter,
                        tray = trayDetector
                    )

                    LoadedModels(
                        pillInterpreter   = pillHolder.interpreter,
                        trayMasksDetector = trayDetector,
                        gloveInterpreter  = gloveHolder?.interpreter
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
        tray: TrayMasksDetector?
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

    private fun warmUpTrayDetector(detector: TrayMasksDetector) {
        val inputFloats = 3 * TrayMasksDetector.INPUT_SIZE * TrayMasksDetector.INPUT_SIZE
        val inputBuf = ByteBuffer.allocateDirect(inputFloats * 4)
            .order(ByteOrder.nativeOrder())
        val dummyScaleInfo = Letterbox.ScaleInfo(
            scale = 1f, padX = 0f, padY = 0f,
            inputSize = TrayMasksDetector.INPUT_SIZE
        )
        repeat(WARMUP_RUNS) { idx ->
            inputBuf.rewind()
            val t0 = System.currentTimeMillis()
            detector.detect(
                inputBuffer = inputBuf,
                scaleInfo = dummyScaleInfo,
                originalWidth = TrayMasksDetector.INPUT_SIZE,
                originalHeight = TrayMasksDetector.INPUT_SIZE
            )
            Log.i(TAG, "Tray warm-up run ${idx + 1}/$WARMUP_RUNS: ${System.currentTimeMillis() - t0}ms")
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
                Log.i(
                    TAG,
                    "$modelName input[$i] shape=${tensor.shape().contentToString()} type=${tensor.dataType()}"
                )
            }

            for (i in 0 until interpreter.outputTensorCount) {
                val tensor = interpreter.getOutputTensor(i)
                Log.i(
                    TAG,
                    "$modelName output[$i] shape=${tensor.shape().contentToString()} type=${tensor.dataType()}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "$modelName — failed to log tensor info", e)
        }
    }

    /**
     * Decrypts a model asset and returns the raw bytes. TFLite wraps these in a
     * direct ByteBuffer via [bytesToDirectBuffer]; ONNX Runtime takes the bytes directly.
     */
    private fun loadModelBytes(modelName: String): ByteArray {
        val encFile = File(context.filesDir, "$modelName.enc")
        if (!encFile.exists()) {
            context.assets.open("$modelName.enc").use { input ->
                encFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return ModelDecryptor.decryptToBytes(encFile)
    }

    /**
     * Creates an OrtSession for the tray masks model.
     *
     * NNAPI EP is intentionally NOT registered. ONNX Runtime 1.19 has a known
     * crash (SIGABRT inside libonnxruntime.so) during session creation on
     * certain Android devices when the NNAPI EP probes accelerators — the
     * crash happens in native code and cannot be caught from Java. CPU EP is
     * stable on all tested devices.
     */
    private fun createTrayMasksDetector(modelBytes: ByteArray): TrayMasksDetector {
        val opts = OrtSession.SessionOptions().apply {
            // 2 threads, not MAX_CPU_THREADS — tray runs in parallel with pill
            // (GPU) and glove (TFLite), and pill's GPU dispatch + the pre/post
            // processing loops are CPU-bound too. Saturating all 4 cores on the
            // tray model leaves nothing for the rest of the pipeline.
            setIntraOpNumThreads(TRAY_ORT_THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = ortEnv.createSession(modelBytes, opts)
        Log.i(TAG, "Tray ONNX — CPU EP only (NNAPI disabled to avoid native crash on some devices)")
        return TrayMasksDetector(ortEnv, session)
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
            Log.e(TAG, "Failed to close gloveInterpreter during unload", e)
        }
        safelyCloseDelegate(gloveGpuDelegate, "gloveGpuDelegate")
        gloveInterpreter = null
        gloveGpuDelegate = null
        logger.i("Glove model unloaded — pill + tray remain cached")
        Log.i(TAG, "Glove model resources released")
    }

    fun close() {
        try { pillInterpreter?.close() } catch (e: Exception) {
            Log.e(TAG, "Failed to close pillInterpreter", e)
        }
        try { trayMasksDetector?.close() } catch (e: Exception) {
            Log.e(TAG, "Failed to close trayMasksDetector", e)
        }
        try { gloveInterpreter?.close() } catch (e: Exception) {
            Log.e(TAG, "Failed to close gloveInterpreter", e)
        }

        safelyCloseDelegate(pillGpuDelegate, "pillGpuDelegate")
        safelyCloseDelegate(gloveGpuDelegate, "gloveGpuDelegate")

        pillInterpreter    = null
        trayMasksDetector  = null
        gloveInterpreter   = null
        pillGpuDelegate    = null
        gloveGpuDelegate   = null

        logger.i("All model resources released")
        Log.i(TAG, "All model resources released and cleared")
    }
}
