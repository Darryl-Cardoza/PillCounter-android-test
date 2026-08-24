package com.rite.pillcounting.core.scanning.analyzer

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rite.pillcounting.core.scanning.logic.IdCardName
import com.rite.pillcounting.core.scanning.logic.IdNameParser
import com.rite.pillcounting.core.scanning.logic.IdTextLine
import com.rite.pillcounting.core.utils.common.SoundUtils
import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Result of scanning a photo ID: the parser's best first/last name guess (null
 * when no confident pair was found) plus every name-like word seen on the
 * card, for the user to tap-to-fill when the guess is wrong or missing.
 */
data class IdScanResult(
    val name: IdCardName?,
    val suggestions: List<String>,
)

/**
 * Extracts a person's name from a photo ID held up to the camera.
 *
 * Two extraction paths per frame, in confidence order:
 *  1. PDF417 barcode (back of a US driver's license) — ML Kit parses the
 *     AAMVA payload into structured fields; first/last name are authoritative
 *     and fire immediately.
 *  2. OCR of the printed text (badge front, license front) via
 *     [IdNameParser] heuristics. An OCR read must repeat on two consecutive
 *     frames before firing, so a misread never surfaces from a single frame.
 *     Fires with a parsed name when one is found, or with suggestion words
 *     alone when the layout defeats the pairing heuristics.
 *
 * Privacy: the AAMVA payload also carries DOB, address and license number —
 * only the two name fields are read, everything else is discarded with the
 * frame. Frames are never written to disk.
 *
 * Frame lifecycle follows [FrameBarcodeAnalyzer]: the bitmap snapshot is taken
 * synchronously inside [analyze], so the caller may close the [ImageProxy] as
 * soon as the call returns. Self-pauses on a hit; call [resume] to rescan and
 * [close] when the owning screen leaves composition.
 */
class IdCardAnalyzer(private val appContext: Context) {
    private val logger = AppLogger("IdCardAnalyzer")

    private val barcodeDelegate = lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_PDF417)
                .build()
        )
    }
    private val barcodeScanner by barcodeDelegate

    private val textDelegate = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val textRecognizer by textDelegate

    private val isPaused = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    /** Token so the watchdog and completion paths only touch their own frame's gate. */
    private val frameToken = AtomicLong(0L)
    private var lastAttemptAtMs = 0L
    private var watchdogJob: Job? = null
    /** Last OCR-parsed candidate; must repeat on the next frame to fire. */
    private var lastOcrCandidate: IdCardName? = null

    /** Last OCR suggestion words; must repeat to fire when no pair was parsed. */
    private var lastSuggestions: List<String> = emptyList()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** OCR is heavier than barcode decode — throttle harder than [FrameBarcodeAnalyzer]. */
        private const val MIN_INTERVAL_MS = 400L
        /** Covers the barcode + OCR chain before the gate is force-released. */
        private const val MLKIT_TIMEOUT_MS = 2500L

        /** Suggestion-only fires need at least a plausible first+last pair to pick from. */
        private const val MIN_SUGGESTIONS_TO_FIRE = 2
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
        isProcessing.set(false)
        watchdogJob?.cancel()
        watchdogJob = null
        lastAttemptAtMs = 0L
        lastOcrCandidate = null
        lastSuggestions = emptyList()
    }

    /** Release ML Kit clients + coroutines. Call from DisposableEffect.onDispose. Idempotent. */
    fun close() {
        if (!released.compareAndSet(false, true)) return
        isPaused.set(true)
        watchdogJob?.cancel()
        watchdogJob = null
        ioScope.cancel()
        if (barcodeDelegate.isInitialized()) barcodeScanner.close()
        if (textDelegate.isInitialized()) textRecognizer.close()
    }

    fun analyze(
        imageProxy: ImageProxy,
        onDetected: (IdScanResult) -> Unit,
    ) {
        if (released.get() || isPaused.get()) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptAtMs < MIN_INTERVAL_MS) return
        if (!isProcessing.compareAndSet(false, true)) return
        lastAttemptAtMs = now

        // Synchronous snapshot — the caller closes the proxy right after we return.
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmap: Bitmap? = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            logger.w("Could not snapshot frame for ID analysis: ${e.message}")
            null
        }
        if (bitmap == null) {
            isProcessing.set(false)
            return
        }

        val token = frameToken.incrementAndGet()
        watchdogJob?.cancel()
        watchdogJob = ioScope.launch {
            delay(MLKIT_TIMEOUT_MS)
            if (frameToken.get() == token && isProcessing.get()) {
                logger.w("MLKit timeout for frame token=$token — releasing gate")
                isProcessing.set(false)
            }
        }

        // Any synchronous throw from ML Kit (e.g. client already released under
        // race) must free the gate + bitmap, or scanning dies until the step
        // is rebuilt.
        try {
            processFrame(input = InputImage.fromBitmap(bitmap, rotation), bitmap, token, onDetected)
        } catch (t: Throwable) {
            logger.e("ID_SCAN dispatch failed token=$token", t)
            finishFrame(bitmap, token)
        }
    }

    private fun processFrame(
        input: InputImage,
        bitmap: Bitmap,
        token: Long,
        onDetected: (IdScanResult) -> Unit,
    ) {
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val license = barcodes
                    .firstOrNull { it.valueType == Barcode.TYPE_DRIVER_LICENSE }
                    ?.driverLicense
                val first = license?.firstName?.trim().orEmpty()
                val last = license?.lastName?.trim().orEmpty()
                if (first.isNotEmpty() && last.isNotEmpty()) {
                    logger.i("ID_SCAN driver license PDF417 hit")
                    val name = IdCardName(IdNameParser.displayCase(first), IdNameParser.displayCase(last))
                    fire(IdScanResult(name, suggestions = emptyList()), onDetected)
                    finishFrame(bitmap, token)
                } else {
                    runOcr(input, bitmap, token, onDetected)
                }
            }
            .addOnFailureListener { ex ->
                logger.e("ID_SCAN barcode failure token=$token", ex)
                runOcr(input, bitmap, token, onDetected)
            }
    }

    private fun runOcr(
        input: InputImage,
        bitmap: Bitmap,
        token: Long,
        onDetected: (IdScanResult) -> Unit,
    ) {
        if (released.get()) {
            finishFrame(bitmap, token)
            return
        }
        // Same rationale as the try in analyze(): a synchronous throw here
        // (barcode listener context) must not leave the gate locked.
        val task = try {
            textRecognizer.process(input)
        } catch (t: Throwable) {
            logger.e("ID_SCAN OCR dispatch failed token=$token", t)
            finishFrame(bitmap, token)
            return
        }
        task
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { block ->
                    block.lines.map { IdTextLine(it.text, it.boundingBox?.height() ?: 0) }
                }
                val parsed = IdNameParser.parse(lines)
                val suggestions = IdNameParser.candidateWords(lines)
                when {
                    parsed != null && parsed == lastOcrCandidate && !isPaused.get() -> {
                        logger.i("ID_SCAN OCR name confirmed on consecutive frame")
                        fire(IdScanResult(parsed, suggestions), onDetected)
                    }
                    // No confident pair, but the same words keep showing up —
                    // surface them and let the user pick.
                    parsed == null && suggestions.size >= MIN_SUGGESTIONS_TO_FIRE &&
                        suggestions == lastSuggestions && !isPaused.get() -> {
                        logger.i("ID_SCAN OCR suggestions confirmed on consecutive frame")
                        fire(IdScanResult(name = null, suggestions = suggestions), onDetected)
                    }
                    // First sighting — hold as candidate; next frame must agree.
                    else -> {
                        lastOcrCandidate = parsed
                        lastSuggestions = suggestions
                    }
                }
            }
            .addOnFailureListener { ex ->
                logger.e("ID_SCAN OCR failure token=$token", ex)
            }
            .addOnCompleteListener {
                finishFrame(bitmap, token)
            }
    }

    private fun fire(result: IdScanResult, onDetected: (IdScanResult) -> Unit) {
        SoundUtils.playBarcodeSound(appContext)
        isPaused.set(true)
        ioScope.launch {
            withContext(Dispatchers.Main) { onDetected(result) }
        }
    }

    private fun finishFrame(bitmap: Bitmap, token: Long) {
        if (!bitmap.isRecycled) bitmap.recycle()
        // Everything below is only ours to touch if no newer frame has taken
        // over: a stale frame completing late must neither free the gate a new
        // frame holds nor cancel that new frame's watchdog (which would leave
        // the gate unrecoverable if the new frame's ML Kit callback is dropped).
        if (frameToken.get() == token) {
            watchdogJob?.cancel()
            isProcessing.set(false)
        }
    }
}
