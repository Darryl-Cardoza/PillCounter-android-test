package com.rite.pillcounting.core.faceAuth.logic

import android.content.Context
import com.rite.pillcounting.core.security.ModelDecryptor
import com.rite.pillcounting.core.security.ModelKeyUnit
import com.rite.pillcounting.core.utils.logger.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** The two loaded face-model interpreters, plus the input sizes callers need to preprocess for. */
data class FaceInterpreters(
    val detector: Interpreter,
    val recognizer: Interpreter,
    val detectorInputSize: Int,
    val recognizerInputSize: Int
)

/**
 * Loads and decrypts the YuNet detector + SFace recognizer TFLite models.
 *
 * Description:
 * Same encrypted-asset pattern as [com.rite.pillcounting.core.scanning.logic.PillDetectionModelLoader]
 * (`ModelDecryptor`/`ModelKeyUnit`, AES-GCM RITE-format `.tflite.enc` files), but
 * simpler — CPU+XNNPACK only, no GPU-delegate fallback, since these two models
 * are small and this is the base integration phase.
 *
 * What it does:
 * - Caches the loaded interpreters after the first successful load (mutex-guarded).
 * - Copies each encrypted asset into `filesDir` once, then decrypts from there —
 *   matches `PillDetectionModelLoader`'s existing `loadModelBytes()` pattern.
 */
@Singleton
class FaceModelLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private val modelKeyUnit = ModelKeyUnit(context).also { it.activateIfNeeded() }
    private var cached: FaceInterpreters? = null

    // TEMPORARY diagnostics for the enroll/verify mismatch investigation — remove
    // once the root cause is found. Filter logcat on these tags to see exactly
    // what shape/dtype each loaded model actually declares.
    private val detectorLogger = AppLogger("FaceDetectorIO")
    private val recognizerLogger = AppLogger("FaceRecognizerIO")

    companion object {
        private const val DETECTOR_FILENAME = "yunet_640x640_float16.tflite"
        private const val RECOGNIZER_FILENAME = "sface_112x112_float16.tflite"
        private const val DETECTOR_INPUT_SIZE = 640
        private const val RECOGNIZER_INPUT_SIZE = 112
        private const val NUM_THREADS = 2
    }

    /**
     * Returns the loaded detector + recognizer interpreters, loading them on first call.
     *
     * @return Cached or freshly loaded [FaceInterpreters].
     * @throws IllegalStateException if either encrypted model asset is missing, via [ModelDecryptor].
     *
     * Example Usage:
     * val interpreters = faceModelLoader.getOrLoadInterpreters()
     */
    suspend fun getOrLoadInterpreters(): FaceInterpreters = mutex.withLock {
        cached?.let { return it }

        return withContext(Dispatchers.IO) {
            val detectorBytes = loadModelBytes(DETECTOR_FILENAME)
            val recognizerBytes = loadModelBytes(RECOGNIZER_FILENAME)

            val options = Interpreter.Options().apply { setNumThreads(NUM_THREADS) }
            val detector = Interpreter(bytesToDirectBuffer(detectorBytes), options)
            val recognizer = Interpreter(bytesToDirectBuffer(recognizerBytes), options)
            detector.allocateTensors()
            recognizer.allocateTensors()

            logTensorSpecs(detectorLogger, "detector", detector)
            logTensorSpecs(recognizerLogger, "recognizer", recognizer)

            FaceInterpreters(detector, recognizer, DETECTOR_INPUT_SIZE, RECOGNIZER_INPUT_SIZE).also { cached = it }
        }
    }

    private fun loadModelBytes(modelName: String): ByteArray {
        val encFile = File(context.filesDir, "$modelName.enc")
        if (!encFile.exists()) {
            context.assets.open("$modelName.enc").use { input ->
                encFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return ModelDecryptor.decryptToBytes(encFile, modelKeyUnit)
    }

    private fun bytesToDirectBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }

    /** TEMPORARY: dumps every input/output tensor's declared shape + dtype so we can confirm our preprocessing assumptions match the actual model file. */
    private fun logTensorSpecs(logger: AppLogger, label: String, interpreter: Interpreter) {
        for (i in 0 until interpreter.inputTensorCount) {
            val t = interpreter.getInputTensor(i)
            logger.i("[$label] INPUT[$i] shape=${t.shape().contentToString()} dtype=${t.dataType()}")
        }
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            logger.i("[$label] OUTPUT[$i] shape=${t.shape().contentToString()} dtype=${t.dataType()}")
        }
    }
}
