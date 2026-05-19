package com.rite.pillcounting.feature.dispenseScan.presentation.analyzer

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight barcode analyzer that consumes frames from an existing camera
 * binding (e.g. [com.rite.pillcounting.feature.pillCountScan.presentation.logic.CameraHelper])
 * without owning the [ImageProxy] lifecycle.
 *
 * The caller forwards the same [ImageProxy] to the pill detection pipeline which
 * closes it. To avoid a use-after-close on the underlying media buffer (MLKit
 * processes asynchronously and may still be reading from it when the proxy is
 * closed), this analyzer takes a synchronous [Bitmap] snapshot before MLKit
 * starts — by the time MLKit reads the input, it's reading from the bitmap copy
 * rather than the camera buffer.
 *
 * Throttling: only one frame is in flight at a time; subsequent frames are
 * dropped while MLKit is still processing. No frame leak — we don't close the
 * proxy here.
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
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
        // Important: if we were stuck mid-process when paused, reset the gate so
        // we don't deadlock the analyzer (otherwise no future frame would be
        // processed and the user would be permanently stuck on "scan" state).
        isProcessing.set(false)
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun analyze(
        imageProxy: ImageProxy,
        onBarcodeDetected: (rawValue: String, imagePath: String?) -> Unit,
    ) {
        if (isPaused.get()) return
        if (!isProcessing.compareAndSet(false, true)) return

        // Snapshot the frame synchronously into a Bitmap so MLKit can safely read
        // it even after the caller closes the ImageProxy. This is the same kind
        // of copy ImagePreprocessor already does for pill detection; we don't
        // hold onto the result unless a barcode is detected.
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

        val input = InputImage.fromBitmap(bitmapCopy, rotation)

        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull()
                if (barcode != null && !isPaused.get()) {
                    // Pause ourselves: we've got a hit. The caller will reset us
                    // when the user dismisses the resulting RX/NDC sheet.
                    isPaused.set(true)
                    ioScope.launch {
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
                    }
                } else {
                    // No barcode this frame — release the bitmap so we don't
                    // accumulate one per dropped frame.
                    bitmapCopy.recycle()
                }
            }
            .addOnFailureListener { ex ->
                logger.e("Barcode detection failed", ex)
                bitmapCopy.recycle()
            }
            .addOnCompleteListener {
                isProcessing.set(false)
            }
    }
}
