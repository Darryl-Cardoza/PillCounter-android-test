package com.rite.pillcounting.feature.pillCountScan.domain

import android.content.Context
import android.util.Log
import com.rite.pillcounting.core.security.ModelDecryptor
import com.rite.pillcounting.core.utils.logger.AppLogger
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
    val trayInterpreter: Interpreter
)

private data class InterpreterHolder(
    val interpreter: Interpreter,
    val delegate: GpuDelegate?
)

@Singleton
class PillDetectionModelLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val logger = AppLogger("PillModelLoader")
    private val mutex = Mutex()

    private var pillInterpreter: Interpreter? = null
    private var trayInterpreter: Interpreter? = null

    private var pillGpuDelegate: GpuDelegate? = null
    private var trayGpuDelegate: GpuDelegate? = null

    companion object {
        private const val PILL_MODEL_FILENAME = "pillcountingmodel"
        private const val TRAY_MODEL_FILENAME = "traymodel"
        private const val TAG = "LoadModel"

        private const val MAX_CPU_THREADS = 4
    }

    suspend fun getOrLoadInterpreters(): LoadedModels {
        Log.i(TAG, "getOrLoadInterpreters() called")

        return mutex.withLock {
            val existingPill = pillInterpreter
            val existingTray = trayInterpreter

            if (existingPill != null && existingTray != null) {
                Log.i(TAG, "Both models already loaded — returning cached interpreters")
                return@withLock LoadedModels(existingPill, existingTray)
            }

            Log.i(TAG, "One or both interpreters missing — loading models")
            logger.i("Loading pill + tray interpreters")

            withContext(Dispatchers.IO) {
                coroutineScope {
                    val pillBufferDeferred = async { loadModelFile(PILL_MODEL_FILENAME) }
                    val trayBufferDeferred = async { loadModelFile(TRAY_MODEL_FILENAME) }

                    val pillBuffer = pillBufferDeferred.await()
                    val trayBuffer = trayBufferDeferred.await()

                    Log.i(TAG, "Model buffers ready")

                    val gpuSupported = withContext(Dispatchers.Main) {
                        CompatibilityList().isDelegateSupportedOnThisDevice
                    }

                    Log.i(TAG, "GPU supported on device: $gpuSupported")

                    val pillHolder = createInterpreterWithFallback(
                        modelBuffer = pillBuffer,
                        modelName = "Pill model",
                        tryGpu = gpuSupported
                    )

                    val trayHolder = createInterpreterWithFallback(
                        modelBuffer = trayBuffer,
                        modelName = "Tray model",
                        tryGpu = gpuSupported
                    )

                    logTensorInfo(pillHolder.interpreter, "Pill model")
                    logTensorInfo(trayHolder.interpreter, "Tray model")

                    pillInterpreter = pillHolder.interpreter
                    trayInterpreter = trayHolder.interpreter
                    pillGpuDelegate = pillHolder.delegate
                    trayGpuDelegate = trayHolder.delegate

                    Log.i(TAG, "Both interpreters initialized successfully")
                    logger.i("Pill + tray interpreters ready")

                    LoadedModels(
                        pillInterpreter = pillHolder.interpreter,
                        trayInterpreter = trayHolder.interpreter
                    )
                }
            }
        }
    }

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
                    Log.i(TAG, "$modelName — trying GPU interpreter")
                    val options = buildGpuOptions(delegate)
                    val interpreter = Interpreter(modelBuffer.duplicateAndRewind(), options)
                    Log.i(TAG, "$modelName — GPU interpreter initialized successfully")
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
            Log.i(TAG, "$modelName — device does not support GPU delegate, using CPU")
        }

        return try {
            val cpuOptions = buildCpuOptions()
            val interpreter = Interpreter(modelBuffer.duplicateAndRewind(), cpuOptions)
            Log.i(TAG, "$modelName — CPU interpreter initialized successfully")
            InterpreterHolder(interpreter, null)
        } catch (e: Exception) {
            Log.e(TAG, "$modelName — CPU interpreter initialization also failed", e)
            throw IllegalStateException("$modelName failed on both GPU and CPU initialization", e)
        }
    }

    private suspend fun createGpuDelegateSafely(modelName: String): GpuDelegate? {
        return withContext(Dispatchers.Main) {
            try {
                val compatList = CompatibilityList()
                // Start from device-optimal defaults, then allow FP16 for ~2× GPU throughput.
                // Minor precision loss is acceptable for object detection.
                val gpuOptions = compatList.bestOptionsForThisDevice.apply {
                    isPrecisionLossAllowed = true
                }
                val delegate = GpuDelegate(gpuOptions)
                Log.i(TAG, "$modelName — GPU delegate created (FP16 enabled)")
                delegate
            } catch (e: Exception) {
                Log.e(TAG, "$modelName — GPU delegate creation failed", e)
                logger.w("$modelName GPU delegate creation failed", e)
                null
            }
        }
    }

    private fun buildGpuOptions(delegate: GpuDelegate): Interpreter.Options {
        return Interpreter.Options().apply {
            addDelegate(delegate)
            // Disable XNNPACK: it conflicts with the GPU delegate and wastes CPU cycles.
            setUseXNNPACK(false)
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

    private fun safelyCloseDelegate(delegate: GpuDelegate?, label: String) {
        try {
            delegate?.close()
            Log.i(TAG, "$label closed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close $label", e)
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

        safelyCloseDelegate(pillGpuDelegate, "pillGpuDelegate")
        safelyCloseDelegate(trayGpuDelegate, "trayGpuDelegate")

        pillInterpreter = null
        trayInterpreter = null
        pillGpuDelegate = null
        trayGpuDelegate = null

        logger.i("All model resources released")
        Log.i(TAG, "All model resources released and cleared")
    }
}