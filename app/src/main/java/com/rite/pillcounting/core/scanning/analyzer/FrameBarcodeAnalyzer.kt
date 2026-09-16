package com.rite.pillcounting.core.scanning.analyzer

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
import com.rite.pillcounting.core.utils.common.SoundUtils
import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight barcode analyzer that consumes frames from an existing camera
 * binding (e.g. [com.rite.pillcounting.core.scanning.logic.CameraHelper])
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
    /**
     * When true, the analyzer does NOT self-pause on a hit. Instead it tracks
     * the last fired barcode value and when a label was last visible. A
     * subsequent fire of the same value only happens once the label has been
     * out of view for [FOCUS_CHANGE_WINDOW_MS] (with empty frames actually seen
     * in that span) and is then re-acquired. A *different* value fires
     * immediately. This is the inventory "scan a bottle, move it away, scan
     * the next" UX.
     *
     * When false (default), the analyzer self-pauses on every hit and waits for
     * an external resume() call — the dispense-flow behavior where a bottom
     * sheet appears and the caller resumes when it's dismissed.
     */
    private val enableFocusChangeDebounce: Boolean = false,
    /**
     * Minimum gap between MLKit decode attempts, in ms. Lower = more frames
     * reach MLKit per second = faster acquisition, at the cost of more CPU.
     * Inventory (barcode-only, no parallel pill detection) overrides this to a
     * lower value so a steadied label decodes faster; the dispense flow keeps
     * the conservative default to leave headroom for the pill model.
     */
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    private val logger = AppLogger("FrameBarcodeAnalyzer")
    private val scannerDelegate = lazy {
        // FORMAT_ALL_FORMATS = 0 is treated by ML Kit as an empty bitmask (no formats),
        // not "all formats". Listing formats explicitly is the only reliable way to
        // ensure QR codes and all 1D/2D symbologies are detected.
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            )
            .build()
        BarcodeScanning.getClient(options)
    }
    private val scanner: BarcodeScanner by scannerDelegate

    private val isPaused = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    /** Set once [close] runs; gates [analyze] so no frame touches a closed scanner. */
    private val released = AtomicBoolean(false)
    private val frameSeen = java.util.concurrent.atomic.AtomicLong(0L)
    private val frameDroppedPaused = java.util.concurrent.atomic.AtomicLong(0L)
    private val frameDroppedThrottle = java.util.concurrent.atomic.AtomicLong(0L)
    private val frameDroppedProcessing = java.util.concurrent.atomic.AtomicLong(0L)
    private val frameAccepted = java.util.concurrent.atomic.AtomicLong(0L)
    /** Token so a late watchdog wakeup can verify "is this MY stale frame?". */
    private val frameToken = AtomicLong(0L)
    private var lastAttemptAtMs: Long = 0L
    private var watchdogJob: Job? = null

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** How long MLKit gets before we declare its frame lost and free the gate. */
        private const val MLKIT_TIMEOUT_MS = 1500L
        /** Default minimum gap between MLKit attempts (overridable per instance). */
        private const val DEFAULT_MIN_INTERVAL_MS = 250L
        /**
         * In focus-change mode, how long the label must be out of view before
         * the analyzer will re-fire the same barcode value (a deliberate bottle
         * swap). Wall-clock, not a frame count: upstream frame delivery is not
         * continuous (CameraHelper drops frames while the AF lens is scanning),
         * so counting analyzed frames would stretch the window unpredictably.
         */
        private const val FOCUS_CHANGE_WINDOW_MS = 750L

        /**
         * Empty frames that must actually be seen inside the out-of-view window
         * before a same-value re-fire. Positive evidence that the label left the
         * scene — a wall-clock gap alone can also mean frames simply stopped
         * arriving (AF gate closed over a stationary bottle).
         */
        private const val FOCUS_CHANGE_MIN_EMPTY_FRAMES = 2
    }

    /** Last value we fired a callback for (focus-change mode). */
    private var lastFiredValue: String? = null

    /** Consecutive empty MLKit frames since the label was last visible (focus-change mode). */
    private var emptyStreak: Int = 0

    /** When an analyzed frame last showed a visible label; 0 = none yet (focus-change mode). */
    private var lastVisibleLabelAtMs: Long = 0L

    fun pause() {
        logger.d("INV_SCAN analyzer.pause() wasPaused=${isPaused.get()} processing=${isProcessing.get()}")
        isPaused.set(true)
    }

    fun resume() {
        logger.d("INV_SCAN analyzer.resume() wasPaused=${isPaused.get()} processing=${isProcessing.get()}")
        isPaused.set(false)
        // If we were stuck mid-process when paused (or MLKit silently lost a
        // callback), reset the gate so the next frame isn't blocked forever.
        isProcessing.set(false)
        watchdogJob?.cancel()
        watchdogJob = null
        lastAttemptAtMs = 0L
        // Reset focus-change state so the first scan after resume always fires,
        // even if it matches the last value from before.
        lastFiredValue = null
        emptyStreak = 0
        lastVisibleLabelAtMs = 0L
    }

    /**
     * Release native + coroutine resources. MUST be called when the owning
     * screen leaves composition (e.g. from a DisposableEffect.onDispose),
     * otherwise the MLKit [BarcodeScanner] (which holds native resources) and
     * the [ioScope] leak per screen visit. Idempotent.
     */
    fun close() {
        if (!released.compareAndSet(false, true)) return
        logger.d("INV_SCAN analyzer.close() — releasing scanner + ioScope")
        // Stop accepting frames so an in-flight listener doesn't touch a closed scanner.
        isPaused.set(true)
        watchdogJob?.cancel()
        watchdogJob = null
        ioScope.cancel()
        // Only close the scanner if it was actually created — `by lazy` means an
        // analyzer that never saw a frame never built one.
        if (scannerDelegate.isInitialized()) scanner.close()
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun analyze(
        imageProxy: ImageProxy,
        onBarcodeDetected: (rawValue: String, imagePath: String?) -> Unit,
    ) {
        if (released.get()) return
        val seen = frameSeen.incrementAndGet()
        if (isPaused.get()) {
            val dropped = frameDroppedPaused.incrementAndGet()
            if (dropped % 30 == 0L) logger.d("INV_SCAN analyze() dropped (paused) total=$dropped seen=$seen")
            return
        }

        // Throttle to minIntervalMs. The check is racy by design — we accept
        // the possibility that two threads slip past simultaneously; the
        // isProcessing CAS below is the authoritative gate.
        val now = System.currentTimeMillis()
        if (now - lastAttemptAtMs < minIntervalMs) {
            val dropped = frameDroppedThrottle.incrementAndGet()
            if (dropped % 60 == 0L) logger.d("INV_SCAN analyze() dropped (throttle) total=$dropped seen=$seen")
            return
        }

        if (!isProcessing.compareAndSet(false, true)) {
            val dropped = frameDroppedProcessing.incrementAndGet()
            if (dropped % 10 == 0L) logger.d("INV_SCAN analyze() dropped (processing-busy) total=$dropped seen=$seen")
            return
        }
        val accepted = frameAccepted.incrementAndGet()
        logger.d("INV_SCAN analyze() ACCEPTED frame seen=$seen accepted=$accepted")
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
                // Prefer the first GTIN-shaped barcode in the frame. Many drug
                // labels include a 2D DataMatrix lot/serial alongside the 1D
                // product barcode (e.g. Strattera's "YL006IDAM02"); MLKit
                // returns both and `firstOrNull()` flips between them frame to
                // frame, which defeats the focus-change debouncer downstream
                // (alternating A→B→A reads each look "new"). Filtering here
                // means we only ever surface the product barcode.
                // Priority order:
                //  1. 2D code (DataMatrix / QR / PDF417 / Aztec) — these carry the full
                //     GS1 payload: GTIN + lot + expiry + serial number. Drug labels always
                //     encode all fields in the 2D symbol; the 1D barcode alongside it
                //     carries only the bare GTIN.
                //  2. Any GTIN-shaped 1D barcode as fallback.
                //  3. Whatever MLKit found first.
                val is2D = { b: com.google.mlkit.vision.barcode.common.Barcode ->
                    b.format == Barcode.FORMAT_QR_CODE ||
                    b.format == Barcode.FORMAT_DATA_MATRIX ||
                    b.format == Barcode.FORMAT_PDF417 ||
                    b.format == Barcode.FORMAT_AZTEC
                }
                val barcode = barcodes.firstOrNull(is2D)
                    ?: barcodes.firstOrNull { it.rawValue?.isGtinLike() == true }
                    ?: barcodes.firstOrNull()
                val rawValue = barcode?.rawValue
                val isProductBarcode = rawValue?.isGtinLike() == true
                logger.d(
                    "INV_SCAN MLKit success token=$token barcodes=${barcodes.size} first='$rawValue' " +
                        "gtinLike=$isProductBarcode paused=${isPaused.get()}"
                )

                // Log every detected barcode so scan results are visible in logcat.
                if (barcodes.isNotEmpty()) {
                    barcodes.forEach { b ->
                        val formatName = barcodeFormatName(b.format)
                        logger.i("SCAN_DATA format=$formatName raw='${b.rawValue}'")
                    }
                }

                // Decide whether THIS frame should fire a callback. There are two
                // policies depending on the analyzer's mode:
                //
                //  - Default (dispense flow): self-pause on any hit; the caller
                //    resumes when its sheet is dismissed. No de-dup needed — the
                //    pause itself prevents repeats.
                //
                //  - Focus-change (inventory): never self-pause. Fire on any new
                //    value immediately. For a value identical to the last one
                //    fired, only re-fire after the label was out of view for
                //    [FOCUS_CHANGE_WINDOW_MS] with empty frames actually seen
                //    (label moved away) and then re-acquired. This is the "scan
                //    a bottle, move it aside, scan the next" UX.
                val shouldFire: Boolean = when {
                    barcode == null || isPaused.get() -> false
                    // In focus-change (inventory) mode, ignore non-product
                    // barcodes outright so stray 1D lot/serial codes never
                    // reach the VM and spam "invalid label" toasts.
                    // 2D codes (DataMatrix, QR, PDF417, Aztec) are always passed
                    // through — they carry the full GS1 payload and the ViewModel
                    // validates the content.
                    enableFocusChangeDebounce && !isProductBarcode && !is2D(barcode) -> {
                        logger.d("INV_SCAN focus-change: non-GTIN 1D '$rawValue' filtered")
                        false
                    }
                    !enableFocusChangeDebounce -> true
                    rawValue != null && rawValue != lastFiredValue -> {
                        // Different value than last fire — fire immediately. This
                        // covers the rapid swap-to-different-NDC case.
                        logger.d("INV_SCAN focus-change: new value '$rawValue' (last='$lastFiredValue') → FIRE")
                        true
                    }
                    emptyStreak >= FOCUS_CHANGE_MIN_EMPTY_FRAMES &&
                        lastVisibleLabelAtMs != 0L &&
                        now - lastVisibleLabelAtMs >= FOCUS_CHANGE_WINDOW_MS -> {
                        // Same value, but the label was out of view long enough
                        // (with empty frames seen) to call it a focus change. The
                        // user moved the label away and brought it (or another
                        // bottle of the same NDC) back.
                        logger.d(
                            "INV_SCAN focus-change: same value '$rawValue' after ${now - lastVisibleLabelAtMs}ms " +
                                "out of view (emptyStreak=$emptyStreak) → FIRE"
                        )
                        true
                    }
                    else -> {
                        // Same value, no focus change yet — suppress.
                        logger.d("INV_SCAN focus-change: same value '$rawValue' suppressed (emptyStreak=$emptyStreak)")
                        false
                    }
                }

                // Track label visibility for focus-change mode. A visible label
                // resets the empty streak and stamps the visibility clock; an
                // empty frame grows the streak.
                if (enableFocusChangeDebounce) {
                    // Frames where the only visible barcode is a non-product
                    // 1D code count as "empty" for the focus-change debouncer.
                    // Any 2D code (DataMatrix, QR, etc.) counts as visible — it
                    // carries the full GS1 payload regardless of GTIN shape.
                    val isVisibleLabel = barcode != null && (isProductBarcode || is2D(barcode))
                    if (!isVisibleLabel) {
                        emptyStreak++
                    } else {
                        emptyStreak = 0
                        lastVisibleLabelAtMs = now
                    }
                }

                if (shouldFire) {
                    // Audible confirmation that a barcode was decoded — mirrors a
                    // handheld scanner's beep. Played here so every camera-based
                    // scan (dispense + inventory) chirps from one place.
                    SoundUtils.playBarcodeSound(appContext)
                    if (enableFocusChangeDebounce) {
                        lastFiredValue = rawValue
                        emptyStreak = 0
                    } else {
                        // Default mode: self-pause; caller resumes us.
                        isPaused.set(true)
                        logger.d("INV_SCAN analyzer SELF-PAUSED on hit raw='$rawValue'")
                    }
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
                                onBarcodeDetected(rawValue.orEmpty(), filePath)
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
                logger.e("INV_SCAN MLKit failure token=$token", ex)
                bitmapCopy.recycle()
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                watchdogJob?.cancel()
            }
    }
}

/**
 * Heuristic: does this barcode value plausibly carry a GTIN/NDC? Drug product
 * barcodes are all-digit and 12–14 chars (UPC-A is 12, EAN-13 is 13, GTIN-14
 * is 14). GS1 DataMatrix payloads start with the "01" AI followed by a 14-digit
 * GTIN, so they pass too. Lot/serial codes like "YL006IDAM02" mix letters and
 * digits and never qualify.
 */
private fun String.isGtinLike(): Boolean {
    if (startsWith("01") && length >= 16 && substring(2, 16).all { it.isDigit() }) return true
    return length in 12..14 && all { it.isDigit() }
}

private fun barcodeFormatName(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE      -> "QR_CODE"
    Barcode.FORMAT_DATA_MATRIX  -> "DATA_MATRIX"
    Barcode.FORMAT_PDF417       -> "PDF417"
    Barcode.FORMAT_AZTEC        -> "AZTEC"
    Barcode.FORMAT_CODE_128     -> "CODE_128"
    Barcode.FORMAT_CODE_39      -> "CODE_39"
    Barcode.FORMAT_CODE_93      -> "CODE_93"
    Barcode.FORMAT_EAN_13       -> "EAN_13"
    Barcode.FORMAT_EAN_8        -> "EAN_8"
    Barcode.FORMAT_UPC_A        -> "UPC_A"
    Barcode.FORMAT_UPC_E        -> "UPC_E"
    Barcode.FORMAT_ITF          -> "ITF"
    Barcode.FORMAT_CODABAR      -> "CODABAR"
    else                        -> "UNKNOWN($format)"
}
