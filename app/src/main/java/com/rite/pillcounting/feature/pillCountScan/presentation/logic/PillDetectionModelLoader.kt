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
    val gloveInterpreter: Interpreter?
)

private data class InterpreterHolder(
    val interpreter: Interpreter,
    val delegate: GpuDelegate?
) {
    val usesGpu: Boolean get() = delegate != null
}

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

    // One GpuDelegate per interpreter — TFLite does not support sharing a delegate
    // across multiple Interpreter instances (silent wrong outputs if you try).
    private var pillGpuDelegate: GpuDelegate? = null
    private var trayGpuDelegate: GpuDelegate? = null
    private var gloveGpuDelegate: GpuDelegate? = null

    companion object {
        private const val PILL_MODEL_FILENAME = "pillcountingmodel"
        private const val TRAY_MODEL_FILENAME = "traymodel"
        private const val GLOVE_MODEL_FILENAME = "gloves-detection"
        private const val TAG = "LoadModel"

        private const val MAX_CPU_THREADS = 4
        private const val GPU_DELEGATE_RETRY_COUNT = 3  // Retry GPU creation
        private const val GPU_DELEGATE_RETRY_DELAY_MS = 150L
    }

    suspend fun getOrLoadInterpreters(includeGlove: Boolean = true): LoadedModels {
        Log.i(TAG, "getOrLoadInterpreters() called (includeGlove=$includeGlove)")

        return mutex.withLock {
            val existingPill  = pillInterpreter
            val existingTray  = trayInterpreter
            val existingGlove = gloveInterpreter

            if (!includeGlove && existingPill != null && existingTray != null) {
                Log.i(TAG, "Pill + tray already loaded, glove skipped — returning cached")
                return@withLock LoadedModels(existingPill, existingTray, null)
            }
            if (includeGlove && existingPill != null && existingTray != null && existingGlove != null) {
                Log.i(TAG, "All three models already loaded — returning cached interpreters")
                return@withLock LoadedModels(existingPill, existingTray, existingGlove)
            }

            Log.i(TAG, "One or more interpreters missing — loading models")
            logger.i(if (includeGlove) "Loading pill + tray + glove interpreters" else "Loading pill + tray interpreters (glove deferred)")

            // Log initial system state before model loading
            performanceLogger.logPerformanceSnapshot("PRE_MODEL_LOAD")

            withContext(Dispatchers.IO) {
                coroutineScope {
                    val pillLoadStart = System.currentTimeMillis()
                    val pillBufferDeferred  = async { loadModelFile(PILL_MODEL_FILENAME) }

                    val trayLoadStart = System.currentTimeMillis()
                    val trayBufferDeferred  = async { loadModelFile(TRAY_MODEL_FILENAME) }

                    val gloveBufferDeferred = if (includeGlove) {
                        async { loadModelFile(GLOVE_MODEL_FILENAME) }
                    } else null

                    val pillBuffer  = pillBufferDeferred.await()
                    val pillBufferTime = System.currentTimeMillis() - pillLoadStart

                    val trayBuffer  = trayBufferDeferred.await()
                    val trayBufferTime = System.currentTimeMillis() - trayLoadStart

                    val gloveBuffer = gloveBufferDeferred?.await()
                    val gloveBufferTime = System.currentTimeMillis() - pillLoadStart

                    Log.i(TAG, "Model buffers ready (glove=${gloveBuffer != null})")

                    val gpuSupported = withContext(Dispatchers.Main) {
                        CompatibilityList().isDelegateSupportedOnThisDevice
                    }

                    Log.i(TAG, "GPU supported on device: $gpuSupported")

                    // Each interpreter gets its own GpuDelegate — sharing one across
                    // multiple interpreters silently corrupts outputs (TFLite limitation).
                    val pillInterpreterStart = System.currentTimeMillis()
                    val pillHolder = createInterpreterWithFallback(
                        modelBuffer = pillBuffer,
                        modelName = "Pill model",
                        tryGpu = gpuSupported
                    )
                    pillGpuDelegate = pillHolder.delegate
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
                    val trayHolder = createInterpreterWithFallback(
                        modelBuffer = trayBuffer,
                        modelName = "Tray model",
                        tryGpu = gpuSupported
                    )
                    trayGpuDelegate = trayHolder.delegate
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

                    val gloveHolder = if (gloveBuffer != null) {
                        val gloveInterpreterStart = System.currentTimeMillis()
                        val holder = createInterpreterWithFallback(
                            modelBuffer = gloveBuffer,
                            modelName = "Glove model",
                            tryGpu = gpuSupported
                        )
                        gloveGpuDelegate = holder.delegate
                        val gloveInterpreterTime = System.currentTimeMillis() - gloveInterpreterStart
                        performanceLogger.logModelLoad(
                            modelName = "Glove Detection Model (YOLOv11)",
                            loadedOn = if (holder.usesGpu) "GPU" else "CPU",
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
                    logTensorInfo(trayHolder.interpreter, "Tray model")

                    pillInterpreter  = pillHolder.interpreter
                    trayInterpreter  = trayHolder.interpreter

                    val loadedCount = if (gloveHolder != null) "3" else "2"
                    Log.i(TAG, "$loadedCount interpreters initialized successfully")
                    logger.i(if (gloveHolder != null) "Pill + tray + glove interpreters ready" else "Pill + tray interpreters ready (glove deferred)")

                    val gpuCount = listOfNotNull(pillHolder, trayHolder, gloveHolder).count { it.delegate != null }
                    Log.i(TAG, "📊 GPU delegates active: $gpuCount / ${if (gloveHolder != null) 3 else 2} (one per interpreter)")

                    // Log post-load system state
                    performanceLogger.logPerformanceSnapshot("POST_MODEL_LOAD")

                    LoadedModels(
                        pillInterpreter  = pillHolder.interpreter,
                        trayInterpreter  = trayHolder.interpreter,
                        gloveInterpreter = gloveHolder?.interpreter
                    )
                }
            }
        }
    }

    /**
     * Creates a fresh GpuDelegate (with retry) for a single interpreter.
     * Each Interpreter needs its own delegate — they cannot be shared.
     */
    private suspend fun createGpuDelegateSafely(modelName: String): GpuDelegate? {
        return withContext(Dispatchers.Main) {
            var lastException: Exception? = null

            repeat(GPU_DELEGATE_RETRY_COUNT) { attempt ->
                try {
                    if (attempt > 0) {
                        delay(GPU_DELEGATE_RETRY_DELAY_MS)
                        Log.i(TAG, "$modelName — GPU delegate retry ${attempt + 1}/$GPU_DELEGATE_RETRY_COUNT")
                    }

                    val compatList = CompatibilityList()
                    val gpuOptions = compatList.bestOptionsForThisDevice.apply {
                        isPrecisionLossAllowed = true
                        inferencePreference = GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER
                        setQuantizedModelsAllowed(true)
                    }

                    val delegate = GpuDelegate(gpuOptions)
                    Log.i(TAG, "$modelName — GPU delegate created (FP16 enabled, attempt ${attempt + 1})")
                    return@withContext delegate

                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "$modelName — GPU delegate attempt ${attempt + 1} failed: ${e.message}")
                }
            }

            Log.e(TAG, "$modelName — GPU delegate creation failed after $GPU_DELEGATE_RETRY_COUNT attempts", lastException)
            logger.w("$modelName GPU delegate creation failed after retries", lastException)
            null
        }
    }

    /**
     * Creates an interpreter for the given model. Tries to give it a fresh GpuDelegate
     * first; falls back to CPU if either delegate creation or GPU interpreter init fails.
     */
    private suspend fun createInterpreterWithFallback(
        modelBuffer: ByteBuffer,
        modelName: String,
        tryGpu: Boolean
    ): InterpreterHolder {
        var delegate: GpuDelegate? = null

        if (tryGpu) {
            delegate = createGpuDelegateSafely(modelName)

            if (delegate != null) {
                try {
                    Log.i(TAG, "$modelName — creating GPU interpreter")
                    val options = buildGpuOptions(delegate)
                    val interpreter = Interpreter(modelBuffer.duplicateAndRewind(), options)
                    Log.i(TAG, "$modelName — ✅ GPU interpreter initialized successfully")
                    return InterpreterHolder(interpreter, delegate)
                } catch (e: Exception) {
                    Log.e(TAG, "$modelName — GPU interpreter init failed, falling back to CPU", e)
                    safelyCloseDelegate(delegate, "$modelName GPU delegate after init failure")
                    delegate = null
                }
            } else {
                Log.i(TAG, "$modelName — GPU delegate unavailable, using CPU")
            }
        } else {
            Log.i(TAG, "$modelName — GPU not supported on device, using CPU")
        }

        // CPU fallback
        return try {
            val cpuOptions = buildCpuOptions()
            val interpreter = Interpreter(modelBuffer.duplicateAndRewind(), cpuOptions)
            Log.i(TAG, "$modelName — CPU interpreter initialized successfully")
            InterpreterHolder(interpreter, delegate = null)
        } catch (e: Exception) {
            Log.e(TAG, "$modelName — CPU interpreter initialization failed", e)
            throw IllegalStateException("$modelName failed on both GPU and CPU initialization", e)
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

    /**
     * Releases only the glove model resources. Pill + tray interpreters remain cached.
     * Safe to call from any coroutine; re-entrant (no-op if already unloaded).
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

        // Close each model's own GPU delegate
        safelyCloseDelegate(pillGpuDelegate, "pillGpuDelegate")
        safelyCloseDelegate(trayGpuDelegate, "trayGpuDelegate")
        safelyCloseDelegate(gloveGpuDelegate, "gloveGpuDelegate")

        pillInterpreter   = null
        trayInterpreter   = null
        gloveInterpreter  = null
        pillGpuDelegate   = null
        trayGpuDelegate   = null
        gloveGpuDelegate  = null

        logger.i("All model resources released")
        Log.i(TAG, "All model resources released and cleared")
    }
}