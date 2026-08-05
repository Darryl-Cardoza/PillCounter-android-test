package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Detects and embeds faces via the loaded YuNet + SFace TFLite interpreters.
 *
 * Description:
 * Direct Kotlin equivalent of `standalone_face_tf.py`'s `FaceEngine` class —
 * same two responsibilities (detect, embed), same score/NMS thresholds, now
 * driven by [FaceModelLoader]'s interpreters instead of raw `tf.lite`.
 *
 * What it does:
 * - [detect] letterboxes the frame into the detector's fixed input, runs the
 *   detector, and decodes raw outputs via [YuNetDecoder].
 * - [detectPrimary] returns the largest-area face, matching the script's
 *   "one primary face per frame" behavior.
 * - [embed] aligns the given face via [FaceAligner] and runs the recognizer,
 *   returning its raw 128-float output (normalization is baked into the graph).
 */
@Singleton
class FaceEngine @Inject constructor(
    private val modelLoader: FaceModelLoader
) {
    companion object {
        const val DET_SCORE_THRESHOLD = 0.85f
        const val DET_NMS_THRESHOLD = 0.3f
    }

    // TEMPORARY diagnostics for the enroll/verify mismatch investigation — remove
    // once the root cause is found. Filter logcat on these tags to see exactly
    // what goes into and comes out of each model on every call.
    private val detectorLogger = AppLogger("FaceDetectorIO")
    private val recognizerLogger = AppLogger("FaceRecognizerIO")

    /**
     * Detects every face in [bitmap].
     *
     * @param bitmap A camera frame (or any image) in Android [Bitmap] form.
     * @return Every detected face, sorted by area descending.
     *
     * Example Usage:
     * val faces = faceEngine.detect(frame)
     */
    suspend fun detect(bitmap: Bitmap): List<FaceBox> {
        val interpreters = modelLoader.getOrLoadInterpreters()
        val size = interpreters.detectorInputSize
        val (inputBuffer, scale) = letterbox(bitmap, size, size)
        detectorLogger.i("IN  frame=${bitmap.width}x${bitmap.height} detectorInput=${size}x$size scale=$scale")

        val rawOutputs = runDetector(interpreters.detector, inputBuffer)
        val faces = YuNetDecoder.decode(rawOutputs, size, size, DET_SCORE_THRESHOLD, DET_NMS_THRESHOLD, scale)
            .sortedByDescending { it.rect.width() * it.rect.height() }

        if (faces.isEmpty()) {
            detectorLogger.i("OUT faces=0")
        } else {
            val top = faces.first()
            detectorLogger.i(
                "OUT faces=${faces.size} top(score=${top.score}, rect=${top.rect}, " +
                    "landmarks=${top.landmarks.joinToString(",", limit = 10)})"
            )
        }
        return faces
    }

    /**
     * Detects faces and returns only the largest one.
     *
     * @param bitmap A camera frame in Android [Bitmap] form.
     * @return The largest-area detected face, or null if none were found.
     */
    suspend fun detectPrimary(bitmap: Bitmap): FaceBox? = detect(bitmap).firstOrNull()

    /**
     * Produces the 128-float SFace embedding for [face] in [bitmap].
     *
     * @param bitmap The same frame [face] was detected in.
     * @param face A face returned by [detect] or [detectPrimary].
     * @return 128 raw floats — normalize with [FaceMatcher.normalize] before comparing.
     *
     * Example Usage:
     * val embedding = faceEngine.embed(frame, face)
     */
    suspend fun embed(bitmap: Bitmap, face: FaceBox): FloatArray {
        val interpreters = modelLoader.getOrLoadInterpreters()
        val aligned = FaceAligner.alignCrop(bitmap, face.landmarks, interpreters.recognizerInputSize)
        recognizerLogger.i("IN  alignedCrop=${aligned.width}x${aligned.height} fromLandmarks=${face.landmarks.joinToString(",")}")

        val embedding = runRecognizer(interpreters.recognizer, aligned)

        var sumSq = 0f
        for (v in embedding) sumSq += v * v
        val norm = sqrt(sumSq)
        recognizerLogger.i(
            "OUT dims=${embedding.size} l2norm=$norm first5=${embedding.take(5).joinToString(",")}"
        )
        return embedding
    }

    /** Letterboxes [bitmap] into a [w]x[h] canvas; returns the input buffer and the scale factor mapping detector-space back to original-frame-space. */
    private fun letterbox(bitmap: Bitmap, w: Int, h: Int): Pair<ByteBuffer, Float> {
        val scaleToFit = minOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val newW = (bitmap.width * scaleToFit).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scaleToFit).toInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val canvas = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(canvas).drawBitmap(resized, Matrix(), null)

        val buffer = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(w * h)
        canvas.getPixels(pixels, 0, w, 0, 0, w, h)
        for (p in pixels) {
            // BGR order, raw 0-255 — matches the script's yunet_preprocess (no normalization).
            buffer.putFloat((p and 0xFF).toFloat())          // B
            buffer.putFloat(((p shr 8) and 0xFF).toFloat())  // G
            buffer.putFloat(((p shr 16) and 0xFF).toFloat()) // R
        }
        buffer.rewind()
        resized.recycle(); canvas.recycle()
        return buffer to (1f / scaleToFit)
    }

    /** Runs the detector and reads back all 12 raw output tensors, shape-tagged for [YuNetDecoder]. */
    private fun runDetector(interpreter: Interpreter, inputBuffer: ByteBuffer): List<RawOutput> {
        val nOut = interpreter.outputTensorCount
        val outputArrays = Array(nOut) { idx ->
            val shape = interpreter.getOutputTensor(idx).shape() // [1, anchors, last]
            Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
        }
        val outMap = HashMap<Int, Any>(nOut)
        for (i in 0 until nOut) outMap[i] = outputArrays[i]
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outMap)

        return (0 until nOut).map { idx ->
            val shape = interpreter.getOutputTensor(idx).shape()
            val flat = FloatArray(shape[1] * shape[2])
            var k = 0
            for (a in 0 until shape[1]) for (l in 0 until shape[2]) flat[k++] = outputArrays[idx][0][a][l]
            RawOutput(shape, flat)
        }
    }

    /** Runs the recognizer on an already-aligned 112x112 bitmap and returns its raw 128-float output. */
    private fun runRecognizer(interpreter: Interpreter, aligned: Bitmap): FloatArray {
        val size = aligned.width
        val inputBuffer = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        aligned.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) {
            inputBuffer.putFloat((p and 0xFF).toFloat())          // B
            inputBuffer.putFloat(((p shr 8) and 0xFF).toFloat())  // G
            inputBuffer.putFloat(((p shr 16) and 0xFF).toFloat()) // R
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(128) }
        interpreter.run(inputBuffer, output)
        return output[0]
    }
}
