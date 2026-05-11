package com.rite.pillcounting.feature.pillCountScan.domain

import android.content.Context
import android.util.Log
import com.rite.pillcounting.core.security.ModelDecryptor
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    val trayInterpreter: Interpreter,
    val gloveInterpreter: Interpreter
)

private data class InterpreterHolder(
    val interpreter: Interpreter,
    val usesGpu: Boolean
)

@Singleton
class PillDetectionModelLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val performanceLogger: PerformanceLogger
) {

    private val logger = AppLogger("PillModelLoader")
    private val mutex = Mutex()

    private var pillInterpreter: Interpreter? = null
    private var trayInterpreter: Interpreter? = null
    private var gloveInterpreter: Interpreter? = null

    // ✅ SINGLE SHARED GPU DELEGATE for all 3 models
    private var sharedGpuDelegate: GpuDelegate? = null

    companion object {
        private const val PILL_MODEL_FILENAME = "pillcountingmodel"
        private const val TRAY_MODEL_FILENAME = "traymodel"
        private const val GLOVE_MODEL_FILENAME = "gloves-detection"
        private const val TAG = "LoadModel"

        private const val MAX_CPU_THREADS = 4
        private const val GPU_DELEGATE_RETRY_COUNT = 3  // ✅ Retry GPU creation
        private const val GPU_DELEGATE_RETRY_DELAY_MS = 150L
    }

    suspend fun getOrLoadInterpreters(): LoadedModels {
        Log.i(TAG, "getOrLoadInterpreters() called")

        return mutex.withLock {
            val existingPill  = pillInterpreter
            val existingTray  = trayInterpreter
            val existingGlove = gloveInterpreter

            if (existingPill != null && existingTray != null && existingGlove != null) {
                Log.i(TAG, "All three models already loaded — returning cached interpreters")
                return@withLock LoadedModels(existingPill, existingTray, existingGlove)
            }

            Log.i(TAG, "One or more interpreters missing — loading models")
            logger.i("Loading pill + tray + glove interpreters")

            // Log initial system state before model loading
            performanceLogger.logPerformanceSnapshot("PRE_MODEL_LOAD")

            withContext(Dispatchers.IO) {
                coroutineScope {
                    val pillLoadStart = System.currentTimeMillis()
                    val pillBufferDeferred  = async { loadModelFile(PILL_MODEL_FILENAME) }

                    val trayLoadStart = System.currentTimeMillis()
                    val trayBufferDeferred  = async { loadModelFile(TRAY_MODEL_FILENAME) }

                    val gloveLoadStart = System.currentTimeMillis()
                    val gloveBufferDeferred = async { loadModelFile(GLOVE_MODEL_FILENAME) }

                    val pillBuffer  = pillBufferDeferred.await()
                    val pillBufferTime = System.currentTimeMillis() - pillLoadStart

                    val trayBuffer  = trayBufferDeferred.await()
                    val trayBufferTime = System.currentTimeMillis() - trayLoadStart

                    val gloveBuffer = gloveBufferDeferred.await()
                    val gloveBufferTime = System.currentTimeMillis() - gloveLoadStart

                    Log.i(TAG, "Model buffers ready")

                    // ✅ CREATE SHARED GPU DELEGATE ONCE with retry logic
                    val gpuSupported = withContext(Dispatchers.Main) {
                        CompatibilityList().isDelegateSupportedOnThisDevice
                    }

                    Log.i(TAG, "GPU supported on device: $gpuSupported")

                    if (gpuSupported) {
                        sharedGpuDelegate = createSharedGpuDelegate()
                        if (sharedGpuDelegate != null) {
                            Log.i(TAG, "✅ SHARED GPU DELEGATE created successfully - will be used by all 3 models")
                        } else {
                            Log.w(TAG, "⚠️ GPU delegate creation failed - all models will fall back to CPU")
                        }
                    } else {
                        Log.i(TAG, "GPU not supported on device - all models will use CPU")
                    }

                    // ✅ LOAD ALL 3 MODELS using shared GPU delegate
                    val pillInterpreterStart = System.currentTimeMillis()
                    val pillHolder = createInterpreterWithSharedGpu(
                        modelBuffer = pillBuffer,
                        modelName = "Pill model"
                    )
                    val pillInterpreterTime = System.currentTimeMillis() - pillInterpreterStart

                    // Log pill model load
                    performanceLogger.logModelLoad(
                        modelName = "Pill Detection Model",
                        loadedOn = if (pillHolder.usesGpu) "GPU" else "CPU",
                        interpreter = pillHolder.interpreter,
                        modelSizeBytes = pillBuffer.capacity().toLong(),
                        loadTimeMs = pillBufferTime + pillInterpreterTime,
                        gpuDelegateEnabled = pillHolder.usesGpu
                    )

                    val trayInterpreterStart = System.currentTimeMillis()
                    val trayHolder = createInterpreterWithSharedGpu(
                        modelBuffer = trayBuffer,
                        modelName = "Tray model"
                    )
                    val trayInterpreterTime = System.currentTimeMillis() - trayInterpreterStart

                    // Log tray model load
                    performanceLogger.logModelLoad(
                        modelName = "Tray Segmentation Model",
                        loadedOn = if (trayHolder.usesGpu) "GPU" else "CPU",
                        interpreter = trayHolder.interpreter,
                        modelSizeBytes = trayBuffer.capacity().toLong(),
                        loadTimeMs = trayBufferTime + trayInterpreterTime,
                        gpuDelegateEnabled = trayHolder.usesGpu
                    )

                    val gloveInterpreterStart = System.currentTimeMillis()
                    val gloveHolder = createInterpreterWithSharedGpu(
                        modelBuffer = gloveBuffer,
                        modelName = "Glove model"
                    )
                    val gloveInterpreterTime = System.currentTimeMillis() - gloveInterpreterStart

                    // Log glove model load
                    performanceLogger.logModelLoad(
                        modelName = "Glove Detection Model (YOLOv11)",
                        loadedOn = if (gloveHolder.usesGpu) "GPU" else "CPU",
                        interpreter = gloveHolder.interpreter,
                        modelSizeBytes = gloveBuffer.capacity().toLong(),
                        loadTimeMs = gloveBufferTime + gloveInterpreterTime,
                        gpuDelegateEnabled = gloveHolder.usesGpu
                    )

                    logTensorInfo(pillHolder.interpreter, "Pill model")
                    logTensorInfo(trayHolder.interpreter, "Tray model")
                    logTensorInfo(gloveHolder.interpreter, "Glove model")

                    pillInterpreter  = pillHolder.interpreter
                    trayInterpreter  = trayHolder.interpreter
                    gloveInterpreter = gloveHolder.interpreter

                    Log.i(TAG, "All three interpreters initialized successfully")
                    logger.i("Pill + tray + glove interpreters ready")

                    // ✅ Log GPU delegate memory info
                    if (sharedGpuDelegate != null) {
                        Log.i(TAG, "📊 GPU DELEGATE MEMORY:")
                        Log.i(TAG, "  - Shared by: Pill, Tray, Glove models")
                        Log.i(TAG, "  - FP16 Precision: ENABLED")
                        Log.i(TAG, "  - Total models on GPU: 3")
                    }

                    // Log post-load system state
                    performanceLogger.logPerformanceSnapshot("POST_MODEL_LOAD")

                    LoadedModels(
                        pillInterpreter  = pillHolder.interpreter,
                        trayInterpreter  = trayHolder.interpreter,
                        gloveInterpreter = gloveHolder.interpreter
                    )
                }
            }
        }
    }

    /**
     * ✅ Creates a SINGLE shared GPU delegate with retry logic.
     * This delegate will be reused by all 3 models for efficient GPU memory usage.
     */
    private suspend fun createSharedGpuDelegate(): GpuDelegate? {
        return withContext(Dispatchers.Main) {
            var lastException: Exception? = null

            repeat(GPU_DELEGATE_RETRY_COUNT) { attempt ->
                try {
                    if (attempt > 0) {
                        delay(GPU_DELEGATE_RETRY_DELAY_MS)
                        Log.i(TAG, "GPU delegate creation retry attempt ${attempt + 1}/$GPU_DELEGATE_RETRY_COUNT")
                    }

                    val compatList = CompatibilityList()
                    val gpuOptions = compatList.bestOptionsForThisDevice.apply {
                        // ✅ Enable FP16 for 2× performance boost
                        isPrecisionLossAllowed = true

                        // ✅ Set inference preference (optional - tune for your needs)
                        inferencePreference = GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER

                        // ✅ Enable quantized model support (if needed)
                        setQuantizedModelsAllowed(true)
                    }

                    val delegate = GpuDelegate(gpuOptions)
                    Log.i(TAG, "✅ Shared GPU delegate created successfully (FP16 enabled, attempt ${attempt + 1})")
                    return@withContext delegate

                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "GPU delegate creation attempt ${attempt + 1} failed: ${e.message}")
                }
            }

            Log.e(TAG, "❌ GPU delegate creation failed after $GPU_DELEGATE_RETRY_COUNT attempts", lastException)
            logger.w("GPU delegate creation failed after retries", lastException)
            null
        }
    }

    /**
     * ✅ Creates interpreter using the SHARED GPU delegate.
     * Falls back to CPU only if GPU delegate doesn't exist or interpreter init fails.
     */
    private suspend fun createInterpreterWithSharedGpu(
        modelBuffer: ByteBuffer,
        modelName: String
    ): InterpreterHolder {
        val delegate = sharedGpuDelegate

        // Try GPU first if delegate exists
        if (delegate != null) {
            try {
                Log.i(TAG, "$modelName — creating interpreter with SHARED GPU delegate")
                val options = buildGpuOptions(delegate)
                val interpreter = Interpreter(modelBuffer.duplicateAndRewind(), options)
                Log.i(TAG, "$modelName — ✅ GPU interpreter initialized successfully")
                return InterpreterHolder(interpreter, usesGpu = true)
            } catch (e: Exception) {
                Log.e(TAG, "$modelName — GPU interpreter init failed, falling back to CPU", e)
                logger.w("$modelName GPU interpreter failed", e)
            }
        } else {
            Log.i(TAG, "$modelName — no GPU delegate available, using CPU")
        }

        // CPU fallback
        return try {
            val cpuOptions = buildCpuOptions()
            val interpreter = Interpreter(modelBuffer.duplicateAndRewind(), cpuOptions)
            Log.i(TAG, "$modelName — CPU interpreter initialized successfully")
            InterpreterHolder(interpreter, usesGpu = false)
        } catch (e: Exception) {
            Log.e(TAG, "$modelName — CPU interpreter initialization failed", e)
            throw IllegalStateException("$modelName failed on both GPU and CPU initialization", e)
        }
    }

    private fun buildGpuOptions(delegate: GpuDelegate): Interpreter.Options {
        return Interpreter.Options().apply {
            addDelegate(delegate)
            // Disable XNNPACK when using GPU (conflicts)
            setUseXNNPACK(false)
            // Allow FP16 precision for faster inference
            setAllowFp16PrecisionForFp32(true)
            // Set number of threads (GPU manages its own threads, but 1 is recommended)
            setNumThreads(1)
        }
    }

    private fun buildCpuOptions(): Interpreter.Options {
        return Interpreter.Options().apply {
            setUseXNNPACK(true)
            numThreads = Runtime.getRuntime()
                .availableProcessors()
                .coerceAtMost(MAX_CPU_THREADS)
        }
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

    private fun loadModelFile(modelName: String): ByteBuffer {
        val encFile = File(context.filesDir, "$modelName.enc")

        if (!encFile.exists()) {
            context.assets.open("$modelName.enc").use { input ->
                encFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        val decryptedBytes = ModelDecryptor.decryptToBytes(encFile)

        return ByteBuffer.allocateDirect(decryptedBytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(decryptedBytes)
            rewind()
        }
    }

    private fun ByteBuffer.duplicateAndRewind(): ByteBuffer {
        return duplicate().apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }
    }

    fun close() {
        try {
            pillInterpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close pillInterpreter", e)
        }

        try {
            trayInterpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close trayInterpreter", e)
        }

        try {
            gloveInterpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close gloveInterpreter", e)
        }

        // ✅ Close SHARED GPU delegate ONCE (used by all 3 models)
        try {
            sharedGpuDelegate?.close()
            if (sharedGpuDelegate != null) {
                Log.i(TAG, "Shared GPU delegate closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close shared GPU delegate", e)
        }

        pillInterpreter  = null
        trayInterpreter  = null
        gloveInterpreter = null
        sharedGpuDelegate = null

        logger.i("All model resources released")
        Log.i(TAG, "All model resources released and cleared")
    }
}