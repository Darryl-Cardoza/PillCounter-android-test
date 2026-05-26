package com.rite.pillcounting.feature.dispenseFlow.presentation.analyzer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rite.pillcounting.core.utils.common.HelperFunctions.saveBitmapToFile
import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight barcode analyzer that consumes frames from an existing camera
 * binding (e.g. [com.rite.pillcounting.feature.pillCountScan.presentation.logic.CameraHelper])
 * without owning the [ImageProxy] lifecycle.
 *
 * The caller forwards the same [ImageProxy] to the pill detection pipeline,
 * which closes it. To avoid a use-after-close on the underlying media buffer
 * (MLKit processes asynchronously), we snapshot a [Bitmap] synchronously
 * BEFORE returning so MLKit reads from the bitmap copy instead of the camera
 * buffer.
 *
 * Reliability under load (the reason this file exists):
 *  - Throttle. A barcode is stationary; 30 fps to MLKit just floods the
 *    pipeline. We accept at most one frame per [MIN_INTERVAL_MS]. This leaves
 *    headroom for the pill detection model running in parallel.
 *  - Watchdog. Under GPU pressure MLKit can silently fail to call its
 *    completion listener, leaving the `isProcessing` gate locked forever and
 *    barcode reads dead until the screen is rebuilt. A coroutine fires after
 *    [MLKIT_TIMEOUT_MS] and resets the gate if our frame's callback never came
 *    back. Token-matched so a fresh frame that legitimately took the gate
 *    isn't yanked out from under itself.
 *
 * No frame leak — we don't close the proxy here.
 */
class FrameBarcodeAnalyzer(
    private val appContext: Context,
) {
    private val logger = AppLogger("FrameBarcodeAnalyzer")
    private val scanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        BarcodeScanning.getClient(options)
    }

    private val isPaused = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    /** Token so a late watchdog wakeup can verify "is this MY stale frame?". */
    private val frameToken = AtomicLong(0L)
    private var lastAttemptAtMs: Long = 0L
    private var watchdogJob: Job? = null

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** How long MLKit gets before we declare its frame lost and free the gate. */
        private const val MLKIT_TIMEOUT_MS = 1500L
        /** Minimum gap between MLKit attempts. */
        private const val MIN_INTERVAL_MS = 250L
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
        // If we were stuck mid-process when paused (or MLKit silently lost a
        // callback), reset the gate so the next frame isn't blocked forever.
        isProcessing.set(false)
        watchdogJob?.cancel()
        watchdogJob = null
        lastAttemptAtMs = 0L
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun analyze(
        imageProxy: ImageProxy,
        onBarcodeDetected: (rawValue: String, imagePath: String?) -> Unit,
    ) {
        if (isPaused.get()) return

        // Throttle to MIN_INTERVAL_MS. The check is racy by design — we accept
        // the possibility that two threads slip past simultaneously; the
        // isProcessing CAS below is the authoritative gate.
        val now = System.currentTimeMillis()
        if (now - lastAttemptAtMs < MIN_INTERVAL_MS) return

        if (!isProcessing.compareAndSet(false, true)) return
        lastAttemptAtMs = now

        // Bitmap copy MUST be synchronous: the caller will hand this same
        // ImageProxy to the pill VM which eventually closes it. We can't
        // dispatch toBitmap() to a background coroutine and risk reading after
        // close. This is the expensive step (~30-80ms on weak ARM) but it's
        // also what guarantees correctness.
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmapCopy: Bitmap? = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            logger.w("Could not snapshot frame for barcode analysis: ${e.message}")
            null
        }

        if (bitmapCopy == null) {
            isProcessing.set(false)
            return
        }

        val token = frameToken.incrementAndGet()

        // Watchdog: if MLKit doesn't complete within the timeout, free the
        // gate so the next frame can proceed. Critical for the intermittent
        // "barcode scanner stops working" symptom under GPU pressure where
        // MLKit drops its callback silently.
        watchdogJob?.cancel()
        watchdogJob = ioScope.launch {
            delay(MLKIT_TIMEOUT_MS)
            if (frameToken.get() == token && isProcessing.get()) {
                logger.w("MLKit timeout for frame token=$token — releasing gate")
                isProcessing.set(false)
            }
        }

        val input = InputImage.fromBitmap(bitmapCopy, rotation)

        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull()
                if (barcode != null && !isPaused.get()) {
                    // Got a hit — self-pause; the caller resumes us when the
                    // resulting RX/NDC sheet is dismissed.
                    isPaused.set(true)
                    ioScope.launch {
                        try {
                            val filePath = try {
                                saveBitmapToFile(
                                    appContext,
                                    bitmapCopy,
                                    "barcode_${System.currentTimeMillis()}.jpg"
                                )
                            } catch (e: Exception) {
                                logger.w("Could not save barcode image: ${e.message}")
                                null
                            }
                            withContext(Dispatchers.Main) {
                                onBarcodeDetected(barcode.rawValue.orEmpty(), filePath)
                            }
                        } finally {
                            if (!bitmapCopy.isRecycled) bitmapCopy.recycle()
                        }
                    }
                } else {
                    bitmapCopy.recycle()
                }
            }
            .addOnFailureListener { ex ->
                logger.e("Barcode detection failed", ex)
                bitmapCopy.recycle()
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                watchdogJob?.cancel()
            }
    }
}
