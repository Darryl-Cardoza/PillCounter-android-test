package com.dispensesure.retail.core.scanning.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.dispensesure.retail.R
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.logic.CameraHelper
import com.dispensesure.retail.core.scanning.logic.Detection
import com.dispensesure.retail.core.scanning.logic.GloveDetection
import com.dispensesure.retail.core.scanning.logic.LoadedModels
import com.dispensesure.retail.core.scanning.logic.PillAnalyzer
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.scanning.logic.TrayDetection
import com.dispensesure.retail.core.utils.common.HelperFunctions.saveBitmapToFile
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.common.OverlayUtils
import com.dispensesure.retail.core.utils.common.PhotoInfo
import com.dispensesure.retail.core.utils.common.SoundUtils
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.logger.PerformanceLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Shared pill counting: camera frames → ML → pills, tray, gloves, idle pause, Add photo.
 * Knows nothing about dispense or stock. Each flow view model creates one and calls [close].
 */
class PillCountingEngine @AssistedInject constructor(
    @Assisted parentScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val modelLoader: PillDetectionModelLoader,
    private val performanceLogger: PerformanceLogger,
    private val locationProvider: LocationProvider,
    private val userDao: UserDao,
    private val preferenceHelper: PreferenceHelper,
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): PillCountingEngine
    }

    // Child of the flow VM's scope, so close() can stop every engine job without touching the VM.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val logger = AppLogger("PillCountingEngine")
    private var currentFrameBitmap: Bitmap? = null
    // Frames are swapped on Dispatchers.Default and taken on Main; guards both.
    private val frameLock = Any()
    // Last looked-up location for the photo strip; Add never waits for a fresh one.
    @Volatile private var cachedLocation: String? = null
    private var locationJob: Job? = null
    @Volatile private var isAnalyzingFrame = false
    // Set on Main, read on the analyzer thread.
    @Volatile private var isPaused = false
    private var idleJob: Job? = null
    // Bumped on counting activity. Atomic because the analyzer thread and Main
    // both bump it.
    private val activityTicks = AtomicLong(0)

    // Count-confirmation state. See onCountObserved().
    private var pendingCount = -1
    private var confirmedCount = -1
    private var consecutiveSameCount = 0

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // One reader, the flow VM. Unlimited, so a gloves or tray save is never dropped.
    private val _events = Channel<EngineEvent>(Channel.UNLIMITED)
    val events: Flow<EngineEvent> = _events.receiveAsFlow()

    // The +N pop for the screen. Losing one is harmless, and it never holds up a save.
    private val _addPops = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val addPops: SharedFlow<Int> = _addPops.asSharedFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Idle)

    private val _lastTenDetections = MutableStateFlow(ArrayDeque<Int>())

    /** Public read-only flow for observing recent detection counts. */
    val lastTenDetections: StateFlow<ArrayDeque<Int>> = _lastTenDetections

    private var cameraHelper: CameraHelper? = null

    // Last frame that showed a complete tray. Drives the AE/AWB lock release
    // delay so a flickering tray gate can't cause lock/unlock churn.
    private var lastCompleteTrayMs: Long = 0L

    // --- Duplicate prevention ---
    private var lastAddedScanSignature: String? = null
    private var addCooldownJob: Job? = null

    // ── Glove detection control: stop running glove model once gloves are detected ──
    private var shouldRunGloveDetection = true

    // Consecutive-frame counter guarding against single-frame flicker between
    // "gloves" and "no_gloves" classes — require several consecutive strong
    // detections before locking the session state, so one flickery frame
    // doesn't permanently commit "gloves detected".
    private var consecutiveGloveFrames = 0

    // Tray color detection: always enabled during COUNTING stage.
    // @Volatile ensures main-thread write is visible to Dispatchers.Default immediately.
    @Volatile private var isTrayColorDetectionEnabled = false
    // true when drug is hazardous AND global "Hazardous Drug" setting is ON → show popup.
    private var hazardousTrayPopupEnabled = false
    // Prevents saving hazardousTrayDetected to DB more than once per transaction.
    private var hazardousTrayResultSaved = false
    // Remembered so resumeFromIdle() can restore detection without re-calling configureHazardous.
    private var lastHazardousByDrug = false
    // Tracks colors already prompted this session to avoid repeated popups per color.
    private val promptedTrayColors = mutableSetOf<TrayColor>()
    // In-memory cache of the single saved hazardous tray color (null if none saved yet).
    private var cachedHazardousColor: String? = null
    // Whether the current transaction is for a hazardous drug.
    private var isHazardousTxn = false
    // Prevents the "using hazardous tray" toast from firing on every frame for non-hazardous txns.
    private var hazardousTrayToastShown = false

    // Performance monitoring
    private var performanceMonitorJob: Job? = null
    private val performanceSnapshotInterval = 10_000L // 10 seconds

    companion object {
        private const val ZERO_DETECTIONS_THRESHOLD = 25

        // How long the tray must be gone before AE/AWB unlock. Rides on top of
        // PillAnalyzer's TRAY_HOLD_FRAMES so a brief gate drop doesn't unlock.
        private const val AE_LOCK_RELEASE_DELAY_MS = 1000L

        // Log prefix for the idle watchdog. Filter: logcat -s PillCountingEngine.
        private const val IDLE_TAG = "IDLE_WATCHDOG"

        // Quiet time before the camera pauses.
        @VisibleForTesting
        internal const val IDLE_TIMEOUT_MS = 60_000L

        // Watchdog poll step. Sets how close to the timeout the pause lands.
        @VisibleForTesting
        internal const val IDLE_STEP_MS = 5_000L

        /**
         * How long Add stays disabled after a tap. The Add handler captures the
         * current camera frame, draws the detection overlay, and writes a JPEG to
         * disk on a background thread. This debounce guards that window so a second
         * Add can't fire before the frame + detections have refreshed and the save
         * has run — otherwise we'd persist a stale/duplicate image for the next
         * transaction detail. Kept short so the UI stays responsive.
         */
        @VisibleForTesting
        internal const val ADD_COOLDOWN_MS = 1000L

        /**
         * Consecutive frames of a strong "gloves" detection required before the
         * gate locks in. At ~4 fps glove inference, 3 frames is a brief ~750ms
         * hold — enough to reject a single flickery misclassification without
         * feeling slow to the user.
         */
        private const val GLOVE_CONFIRM_FRAMES = 3

        // Frames a count must hold before it counts as activity. Rejects
        // single-frame flicker on a still tray.
        @VisibleForTesting
        internal const val COUNT_CONFIRM_FRAMES = 3
    }

    /** Model initialization states */
    private sealed class ModelState {
        object Idle : ModelState()
        data class Ready(val analyzer: PillAnalyzer) : ModelState()
    }

    init {
        armIdleTimer()
        startPerformanceMonitoring()
    }

    /**
     * Start periodic performance monitoring
     */
    private fun startPerformanceMonitoring() {
        performanceMonitorJob?.cancel()
        performanceMonitorJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(performanceSnapshotInterval)
                try {
                    performanceLogger.logPerformanceSnapshot("PERIODIC_MONITORING")
                } catch (error: Exception) {
                    logger.eThrottled("Performance monitoring failed", error, event = LogEvent.FUNCTIONALITY_ERROR)
                }
            }
        }
    }

    /** Attach a CameraHelper instance for lifecycle control. */
    fun attachCamera(helper: CameraHelper) {
        cameraHelper = helper
        refreshLocation()
    }

    /**
     * Initialize TensorFlow Lite interpreters using the Singleton Loader.
     *
     * Now calls [PillDetectionModelLoader.getOrLoadInterpreters] which returns
     * both the pill and tray interpreters loaded in parallel.
     */
    fun start() {
        if (_modelState.value is ModelState.Ready) {
            logger.w("Interpreter already initialized, skipping reinitialization.")
            return
        }

        scope.launch {
            try {
                // Load pill + tray only; glove is deferred until a hazardous drug is confirmed.
                val loadedModels = modelLoader.getOrLoadInterpreters(includeGlove = false)

                _modelState.value = ModelState.Ready(buildAnalyzer(loadedModels))
                logger.i("Pill + tray interpreters initialized (glove deferred).")

            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.e("Interpreter init failed", error, event = LogEvent.MODEL_LOAD_FAILED)
            }
        }
    }

    /**
     * Loads the glove detection model and rebuilds [PillAnalyzer] to include it.
     * Called only when the confirmed drug is hazardous and the COUNTING stage begins.
     * No-op if glove is already loaded.
     */
    fun loadGloveModel() {
        if ((_modelState.value as? ModelState.Ready)?.analyzer?.hasGloveInterpreter == true) {
            logger.i("Glove model already loaded — skipping rebuild")
            return
        }

        scope.launch {
            try {
                val loadedModels = modelLoader.getOrLoadInterpreters(includeGlove = true)

                _modelState.value = ModelState.Ready(buildAnalyzer(loadedModels))
                logger.i("Glove model loaded — analyzer rebuilt for hazardous drug.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.e("Glove model load failed", error, event = LogEvent.MODEL_LOAD_FAILED)
            }
        }
    }

    /**
     * Closes the glove TFLite interpreter and rebuilds [PillAnalyzer] without it.
     * Pill + tray interpreters remain in the loader cache so detection continues uninterrupted.
     */
    private suspend fun unloadGloveAndRebuildAnalyzer() {
        modelLoader.unloadGloveModel()
        // The loader returns no glove interpreter for includeGlove = false.
        val loadedModels = modelLoader.getOrLoadInterpreters(includeGlove = false)
        _modelState.value = ModelState.Ready(buildAnalyzer(loadedModels))
        logger.i("Analyzer rebuilt without glove model")
    }

    /** The only place a [PillAnalyzer] is built, so every rebuild gets the same callbacks. */
    private fun buildAnalyzer(loadedModels: LoadedModels): PillAnalyzer = PillAnalyzer(
        pillInterpreter  = loadedModels.pillInterpreter,
        traySegDetector = loadedModels.traySegDetector,
        gloveInterpreter = loadedModels.gloveInterpreter,
        performanceLogger = performanceLogger,
        shouldRunGloveDetection = { shouldRunGloveDetection },
        shouldDetectTrayColor = { isTrayColorDetectionEnabled },
        onResult = { count, detections, trayDetections, gloveDetections, bitmap, imageWidth, imageHeight ->
            processDetections(
                count           = count,
                detections      = detections,
                trayDetections  = trayDetections,
                gloveDetections = gloveDetections,
                bitmap          = bitmap,
                imageWidth      = imageWidth,
                imageHeight     = imageHeight
            )
        }
    )

    // ------------------------------------------------------------------------
    // Frame Processing and Detection Logic
    // ------------------------------------------------------------------------

    /**
     * Handle each analyzed frame and maintain rolling detection state.
     *
     * [trayDetections] is forwarded to [EngineState.trayDetections] so [CameraPreviewSection]
     * can draw the tray bounding box overlay.
     */
    @VisibleForTesting
    internal fun processDetections(
        count: Int,
        detections: List<Detection>,
        trayDetections: List<TrayDetection>,
        gloveDetections: List<GloveDetection>,
        bitmap: Bitmap,
        imageWidth: Int,
        imageHeight: Int
    ) {
        if (isPaused) {
            bitmap.recycle()
            return
        }

        synchronized(frameLock) {
            currentFrameBitmap?.recycle()
            currentFrameBitmap = bitmap
        }

        logger.d("Frame analyzed | count=$count")

        // ── Publish tray & glove detections for the UI overlay ───────────────
        _state.update { it.copy(trayDetections = trayDetections, gloveDetections = gloveDetections) }

        // ── AE/AWB lock ──────────────────────────────────────────────────────
        // trayDetections is only populated when PillAnalyzer saw a COMPLETE tray, so a
        // non-empty list is the "tray in view" signal. Lock exposure and white
        // balance while it is, so the detector sees a consistent image instead of
        // one the camera keeps re-metering. Release is delayed so a flickering
        // tray gate can't cause lock/unlock churn.
        if (trayDetections.isNotEmpty()) {
            lastCompleteTrayMs = System.currentTimeMillis()
            cameraHelper?.setAeAwbLock(true)
        } else if (lastCompleteTrayMs != 0L &&
            System.currentTimeMillis() - lastCompleteTrayMs >= AE_LOCK_RELEASE_DELAY_MS
        ) {
            cameraHelper?.setAeAwbLock(false)
            lastCompleteTrayMs = 0L
        }

        // ── Tray color classification + hazardousTrayDetected DB save ────────
        // Runs only for hazardous drug transactions. Saves exactly once per txn.
        //
        // Scenario 1 (setting OFF): immediate save — true if in hazardous list, false otherwise.
        // Scenario 2 (setting ON):  true if in hazardous list (immediate), otherwise show popup;
        //                           user response (yes/no) determines the saved value.
        logger.d(
            "Frame: detectionEnabled=$isTrayColorDetectionEnabled popupEnabled=$hazardousTrayPopupEnabled " +
            "resultSaved=$hazardousTrayResultSaved trays=${trayDetections.size} " +
            "pending=${_state.value.pendingTrayColor?.label}"
        )
        if (isTrayColorDetectionEnabled) {
            val trayColor = trayDetections.firstOrNull { tray -> tray.trayColor != TrayColor.UNKNOWN }?.trayColor
            logger.d("  First known tray color: ${trayColor?.label ?: "none"} hazardousColor=$cachedHazardousColor isHazardousTxn=$isHazardousTxn")

            if (trayColor != null) {
                if (isHazardousTxn && !hazardousTrayResultSaved) {
                    when {
                        cachedHazardousColor == null -> {
                            // No hazardous color saved yet — first hazardous transaction.
                            // Show prompt so the user can designate this tray (setting must be ON).
                            if (hazardousTrayPopupEnabled &&
                                _state.value.pendingTrayColor == null &&
                                trayColor !in promptedTrayColors) {
                                promptedTrayColors.add(trayColor)
                                _state.update { it.copy(pendingTrayColor = trayColor) }
                                logger.i("[HAZARDOUS] First hazardous txn — showing tray classification popup for ${trayColor.label}")
                            } else if (!hazardousTrayPopupEnabled) {
                                // Global setting OFF — nothing to classify, save false.
                                logger.i("[HAZARDOUS] No saved color, setting OFF — saving false for ${trayColor.label}")
                                reportHazardousTrayResult(false)
                            }
                        }
                        trayColor.name == cachedHazardousColor -> {
                            // Correct hazardous tray detected — save true.
                            logger.i("[HAZARDOUS] Tray ${trayColor.label} matches hazardous color — saving true")
                            reportHazardousTrayResult(true)
                        }
                        else -> {
                            // A hazardous color is saved but this tray does not match it.
                            if (!hazardousTrayPopupEnabled) {
                                // Global setting OFF — save false silently.
                                logger.i("[HAZARDOUS] Setting OFF, tray ${trayColor.label} != hazardous color — saving false")
                                reportHazardousTrayResult(false)
                            } else if (!hazardousTrayToastShown) {
                                // Setting ON — warn user to swap to the correct hazardous tray.
                                hazardousTrayToastShown = true
                                logger.i("[HAZARDOUS] Tray ${trayColor.label} is NOT the hazardous tray — showing warning toast")
                                scope.launch(Dispatchers.Main) {
                                    showToast(context.getString(R.string.non_hazardous_tray_warning))
                                }
                            }
                        }
                    }
                } else if (!isHazardousTxn && !hazardousTrayToastShown && trayColor.name == cachedHazardousColor) {
                    // Non-hazardous transaction using the hazardous tray — warn the user.
                    hazardousTrayToastShown = true
                    logger.i("[HAZARDOUS] Hazardous tray ${trayColor.label} detected in non-hazardous transaction — showing toast")
                    scope.launch(Dispatchers.Main) {
                        showToast(context.getString(R.string.hazardous_tray_warning))
                    }
                }
            }
        }

        // ── Check if gloves detected - if yes, stop running glove detection ──
        // Require several CONSECUTIVE strong detections before locking the session
        // state — a single flickery frame (e.g. a bare hand momentarily misclassified
        // as "gloves") must not permanently commit "gloves detected".
        if (!_state.value.glovesDetected) {
            val strongGloveThisFrame = gloveDetections.any { glove -> glove.classId == 0 && glove.confidence >= 0.75f }
            consecutiveGloveFrames = if (strongGloveThisFrame) consecutiveGloveFrames + 1 else 0

            if (consecutiveGloveFrames >= GLOVE_CONFIRM_FRAMES) {
                _state.update { it.copy(glovesDetected = true) }
                shouldRunGloveDetection = false
                val strongestGlove = gloveDetections.filter { glove -> glove.classId == 0 }.maxByOrNull { glove -> glove.confidence }
                logger.i("GLOVE_ICON — turning GREEN (bestConfidence=${strongestGlove?.confidence}, consecutiveFrames=$consecutiveGloveFrames) - unloading glove model and saving DB flag")
                scope.launch {
                    try {
                        unloadGloveAndRebuildAnalyzer()
                        _events.trySend(EngineEvent.GlovesConfirmed)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logger.e("Failed to unload glove model", error, event = LogEvent.GLOVE_DETECT_FAILED)
                    }
                }
            }
        }

        // Rolling count buffer
        val recentCounts = ArrayDeque(_lastTenDetections.value)
        if (recentCounts.size >= ZERO_DETECTIONS_THRESHOLD) recentCounts.removeFirst()
        recentCounts.addLast(count)
        _lastTenDetections.value = recentCounts

        onCountObserved(count)

        // Map pill centres to normalised [0..1] coordinates
        updateDetectedPills(
            pills = detections.map { detection ->
                DetectedPill(
                    x = (detection.rect.centerX() / imageWidth.toFloat()).coerceIn(0f, 1f),
                    y = (detection.rect.centerY() / imageHeight.toFloat()).coerceIn(0f, 1f),
                    confidence = detection.confidence
                )
            }, frameWidth = imageWidth, frameHeight = imageHeight
        )
    }

    /** Update the list of detected pills in UI state AND the frame dimensions. */
    private fun updateDetectedPills(
        pills: List<DetectedPill>, frameWidth: Int, frameHeight: Int
    ) {
        _state.update {
            it.copy(
                detectedPills = pills, frameWidth = frameWidth, frameHeight = frameHeight,
            )
        }
    }

    // Fired by the watchdog after a window with no count change.
    private fun pauseAndClearBuffers() {
        // A new deque, so the flow emits and the analyzer's copy never sees it change.
        _lastTenDetections.value = ArrayDeque()
        resetCountConfirmation()
        lastCompleteTrayMs = 0L
        cameraHelper?.setAeAwbLock(false)
        _state.update {
            it.copy(
                showIdleOverlay = true,
                gloveDetections = emptyList(),
                pendingTrayColor = null,
                trayDetections = emptyList(),
            )
        }
        isTrayColorDetectionEnabled = false
        hazardousTrayPopupEnabled = false
        promptedTrayColors.clear()
        isHazardousTxn = false
        hazardousTrayToastShown = false
        isPaused = true
        _state.update { it.copy(isCameraPaused = true) }
        logger.w("$IDLE_TAG camera paused due to idle timeout. Buffers cleared.")
    }

    fun armIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            val quietStepsNeeded = (IDLE_TIMEOUT_MS / IDLE_STEP_MS).toInt()
            var ticksAtLastCheck = activityTicks.get()
            var quietSteps = 0
            logger.d("$IDLE_TAG armed | timeout=${IDLE_TIMEOUT_MS}ms step=${IDLE_STEP_MS}ms ticks=$ticksAtLastCheck")
            while (true) {
                delay(IDLE_STEP_MS)
                val ticksNow = activityTicks.get()
                if (ticksNow != ticksAtLastCheck) {
                    logger.d("$IDLE_TAG activity=${ticksNow - ticksAtLastCheck} -> deadline pushed")
                    ticksAtLastCheck = ticksNow
                    quietSteps = 0
                    continue
                }
                quietSteps++
                if (quietSteps >= quietStepsNeeded) {
                    logger.d("$IDLE_TAG quiet ${quietSteps * IDLE_STEP_MS}ms -> pausing")
                    pauseAndClearBuffers()
                    return@launch
                }
            }
        }
    }

    /** Mark counting activity so the watchdog holds off. */
    fun noteActivity() {
        activityTicks.incrementAndGet()
    }

    // A count must repeat for COUNT_CONFIRM_FRAMES frames to count as activity,
    // so detector flicker can't hold the watchdog open forever.
    @VisibleForTesting
    internal fun onCountObserved(count: Int) {
        if (count == pendingCount) {
            consecutiveSameCount++
        } else {
            pendingCount = count
            consecutiveSameCount = 1
        }
        if (consecutiveSameCount == COUNT_CONFIRM_FRAMES && count != confirmedCount) {
            confirmedCount = count
            noteActivity()
        }
    }

    private fun resetCountConfirmation() {
        pendingCount = -1
        confirmedCount = -1
        consecutiveSameCount = 0
    }

    fun stopIdleTimer() {
        idleJob?.cancel()
        idleJob = null
        logger.d("$IDLE_TAG cancelled")
    }

    /** Process an incoming frame from CameraX. */
    fun onFrame(image: ImageProxy) {
        if (isPaused) {
            image.close()
            return
        }
        val modelStateNow = _modelState.value
        if (modelStateNow !is ModelState.Ready) {
            image.close()
            return
        }
        if (isAnalyzingFrame) {
            image.close()
            return
        }

        isAnalyzingFrame = true
        scope.launch(Dispatchers.Default) {
            try {
                modelStateNow.analyzer.analyze(image)
            } catch (error: Exception) {
                logger.eThrottled("Frame analysis failed.", error, event = LogEvent.PILL_COUNT_FAILED)
                image.close()
            } finally {
                isAnalyzingFrame = false
            }
        }
    }

    /** Reset idle overlay and resume camera analysis. */
    fun resumeFromIdle() {
        _state.update { it.copy(showIdleOverlay = false, pendingTrayColor = null) }

        _lastTenDetections.value = ArrayDeque()
        resetCountConfirmation()
        lastCompleteTrayMs = 0L
        cameraHelper?.setAeAwbLock(false)
        lastAddedScanSignature = null
        _state.update { it.copy(trayDetections = emptyList(), gloveDetections = emptyList()) }

        // Restore detection flags using the drug flag remembered from configureHazardous().
        // Do NOT reset hazardousTrayResultSaved — the transaction is the same, result already saved.
        val globalSettingOn = preferenceHelper.isHazardousDrugEnabled()
        isTrayColorDetectionEnabled = true
        isHazardousTxn = lastHazardousByDrug
        hazardousTrayPopupEnabled = lastHazardousByDrug && globalSettingOn
        hazardousTrayToastShown = false
        cachedHazardousColor = preferenceHelper.getHazardousTrayColor()
        if (lastHazardousByDrug) {
            promptedTrayColors.clear()
        }

        // Reset glove detection state
        resetGloves()

        isPaused = false
        _state.update { it.copy(isCameraPaused = false) }

        // The watchdog job ended when it fired, so re-arm it.
        armIdleTimer()

        logger.d("$IDLE_TAG overlay reset -> Analysis resumed.")
    }

    fun onFilteredPills(filtered: List<DetectedPill>) {
        // The Canvas overlay calls this from inside its draw block on every recompose.
        // Without a dedupe, every frame triggers a new state emission, which
        // triggers another recompose, which redraws the Canvas — a tight loop that
        // burns frame budget on weak devices. Compare references first (cheap),
        // and only escalate to a structural compare when the reference differs.
        val shownPills = _state.value.filteredPills
        if (shownPills === filtered) return
        if (shownPills.size == filtered.size &&
            shownPills.indices.all { index -> shownPills[index] === filtered[index] }) {
            return
        }
        _state.update { it.copy(filteredPills = filtered) }
    }

    /**
     * Called when COUNTING stage begins.
     *
     * Scenario 1 — setting OFF + drug hazardous:
     *   Detection runs, no popup. Result saved immediately based on hazardous-list lookup.
     * Scenario 2 — setting ON + drug hazardous:
     *   Detection runs, popup shown if color not in hazardous list. Result saved on user response.
     * If drug is NOT hazardous: nothing runs, no DB write.
     */
    fun configureHazardous(isHazardousDrug: Boolean) {
        val globalSettingOn = preferenceHelper.isHazardousDrugEnabled()
        lastHazardousByDrug = isHazardousDrug
        isHazardousTxn = isHazardousDrug
        isTrayColorDetectionEnabled = true
        hazardousTrayPopupEnabled = isHazardousDrug && globalSettingOn
        hazardousTrayResultSaved = false
        hazardousTrayToastShown = false
        cachedHazardousColor = preferenceHelper.getHazardousTrayColor()

        logger.d("=== configureHazardous ===")
        logger.d("  drugFlag=$isHazardousDrug  globalSettingOn=$globalSettingOn")
        logger.d("  detectionEnabled=$isTrayColorDetectionEnabled  popupEnabled=$hazardousTrayPopupEnabled  hazardousColor=$cachedHazardousColor")

        if (isHazardousDrug) {
            promptedTrayColors.clear()
            logger.i("Hazardous drug transaction. popupEnabled=$hazardousTrayPopupEnabled hazardousColor=$cachedHazardousColor")
        } else {
            logger.i("Non-hazardous drug — tray detection active for hazardous tray warning. hazardousColor=$cachedHazardousColor")
        }
    }

    /**
     * Saves the user's classification choice for the pending tray color.
     * Adds the color to the appropriate preference list and dismisses the popup.
     */
    fun classifyTray(color: TrayColor, isHazardous: Boolean) {
        if (isHazardous) {
            preferenceHelper.setHazardousTrayColor(color.name)
            cachedHazardousColor = color.name
            logger.i("Tray color ${color.label} saved as hazardous color → saving true to DB")
        } else {
            logger.i("Tray color ${color.label} not classified as hazardous → saving false to DB")
        }
        reportHazardousTrayResult(isHazardous)
        _state.update { it.copy(pendingTrayColor = null) }
    }

    // Once per transaction, like the DB save it feeds.
    private fun reportHazardousTrayResult(detected: Boolean) {
        if (hazardousTrayResultSaved) return
        hazardousTrayResultSaved = true
        _events.trySend(EngineEvent.HazardousTrayResult(detected))
    }

    /**
     * Reset glove detection state.
     * Called when the pill scanning screen is loaded or when resuming from idle.
     */
    fun resetGloves() {
        _state.update { it.copy(glovesDetected = false) }
        shouldRunGloveDetection = true
        consecutiveGloveFrames = 0
        // Also re-enable the analyzer's fast initial cadence so the first detection
        // after a reset arrives in one frame, not after the rate-limit window.
        (_modelState.value as? ModelState.Ready)?.analyzer?.resetGloveCadence()
        logger.i("🔄 Glove detection reset - Model will run on next frame")
    }

    // ------------------------------------------------------------------------
    // Add: guards at the tap, photo afterwards
    // ------------------------------------------------------------------------

    /**
     * Runs the Add guards and fixes the frame, pills and trays at the tap.
     * The flow view model checks its own limits first, then saves with [saveCountPhoto].
     */
    fun captureCount(count: Int): CaptureResult {
        if (_state.value.isAddCooldown) {
            logger.w("Add action ignored: cooldown active.")
            return CaptureResult.Rejected(CaptureResult.Reason.Cooldown)
        }
        if (count == 0) {
            logger.w("Add blocked: detected count is 0.")
            return CaptureResult.Rejected(CaptureResult.Reason.ZeroCount)
        }
        // Fix the frame, pills and tray at the tap; the save must not pick up a later frame.
        val frame = takeCurrentFrame()
        if (frame == null) {
            logger.w("Add blocked: no camera frame at tap.")
            return CaptureResult.Rejected(CaptureResult.Reason.NoFrame)
        }
        val filteredPills = _state.value.filteredPills
        val trays = _state.value.trayDetections

        val pillLayoutSignature = _state.value.detectedPills.joinToString(separator = "|") { pill ->
            "${"%.3f".format(pill.x)}-${"%.3f".format(pill.y)}"
        } + "|count=$count"

        if (pillLayoutSignature == lastAddedScanSignature) {
            frame.recycle()
            logger.w("Duplicate add prevented: no change in detection pattern.")
            return CaptureResult.Rejected(CaptureResult.Reason.Duplicate)
        }

        startAddCooldown()

        lastAddedScanSignature = pillLayoutSignature
        noteActivity()
        _addPops.tryEmit(count)
        return CaptureResult.Captured(count, frame, filteredPills, trays)
    }

    /**
     * Draws the pills and the info strip on a [captureCount] frame and saves it as a JPEG,
     * plus the unmarked crop when [keepRawCrop]. Recycles the frame.
     */
    suspend fun saveCountPhoto(
        captured: CaptureResult.Captured,
        label: PhotoLabel,
        keepRawCrop: Boolean,
    ): SavedPhoto = withContext(Dispatchers.IO) {
        try {
            val userId = preferenceHelper.getUserId().orEmpty()
            val loggedInUser = userDao.getByUserId(userId)
            // No lookup finished yet: show the fallback rather than wait.
            val location = cachedLocation ?: context.getString(R.string.location_unavailable)

            val countedFrame = captured.frame

            val drugImage = label.drugImagePath?.takeIf { drugImagePath -> drugImagePath.isNotBlank() }
                ?.let { drugImagePath -> runCatching { BitmapFactory.decodeFile(drugImagePath) }.getOrNull() }
                ?: drugImagePlaceholder()

            // Crop to tray + chute when a complete tray is in view; else keep the full frame.
            val frameWidth = countedFrame.width
            val frameHeight = countedFrame.height
            val trayCropRegion = OverlayUtils.savedPhotoCropRegion(captured.trays, frameWidth, frameHeight)
            val croppedPhoto = if (trayCropRegion != null) {
                Bitmap.createBitmap(
                    countedFrame, trayCropRegion.left, trayCropRegion.top,
                    trayCropRegion.width(), trayCropRegion.height(),
                )
            } else countedFrame
            val pillsInPhoto = if (trayCropRegion != null) {
                OverlayUtils.pillsInCrop(captured.filteredPills, trayCropRegion, frameWidth, frameHeight)
            } else captured.filteredPills

            val photoInfo = PhotoInfo(
                drugName = label.drugName,
                drugImage = drugImage,
                isStockCount = label.isStockCount,
                requestedNdc = label.requestedNdc,
                dispensedNdc = label.dispensedNdc,
                lotNumber = label.lotNumber,
                serialNumber = label.serialNumber,
                expirationDate = label.expirationDate,
                count = captured.count,
                rxRefill = label.rxRefill,
                stepLabel = label.stepLabel,
                userName = listOfNotNull(loggedInUser?.fName, loggedInUser?.lName).joinToString(" "),
                location = location,
                timestamp = System.currentTimeMillis(),
            )
            val photoWithStrip = try {
                OverlayUtils.drawDetectionsOnBitmap(
                    bitmap = croppedPhoto,
                    detectedPills = pillsInPhoto,
                    info = photoInfo,
                    res = context.resources,
                    scaleRefSide = min(frameWidth, frameHeight),
                    // Same breakpoint as UserInterfaceUtils.isTablet(), read outside Compose.
                    isPhone = context.resources.configuration.smallestScreenWidthDp <
                        UserInterfaceUtils.TABLET_BREAKPOINT_DP,
                )
            } catch (error: Exception) {
                logger.e("Overlay drawing failed, using bitmap without overlay", error, event = LogEvent.PILL_COUNT_FAILED)
                croppedPhoto
            }
            drugImage?.recycle()

            val fileTimestamp = System.currentTimeMillis()
            val photoPath = try {
                if (!photoWithStrip.isRecycled) {
                    saveBitmapToFile(
                        context,
                        photoWithStrip,
                        "txn_detail_$fileTimestamp.jpg",
                        "transaction_details",
                        grayscale = isGrayscale(),
                    )
                } else null
            } catch (error: Exception) {
                logger.e("Failed saving bitmap", error, event = LogEvent.FILE_WRITE_ERROR)
                null
            }
            // The crop with nothing drawn on it, when the caller needs it.
            val rawPhotoPath = if (keepRawCrop) {
                try {
                    if (!croppedPhoto.isRecycled) {
                        saveBitmapToFile(
                            context,
                            croppedPhoto,
                            "txn_detail_${fileTimestamp}_raw.jpg",
                            "transaction_details",
                        )
                    } else null
                } catch (error: Exception) {
                    logger.e("Failed saving raw bitmap", error, event = LogEvent.FILE_WRITE_ERROR)
                    null
                }
            } else null
            // A new bitmap, unless drawing failed and fell back to the crop (recycled below).
            if (photoWithStrip !== croppedPhoto && !photoWithStrip.isRecycled) photoWithStrip.recycle()
            // createBitmap can hand back the source itself when the crop is the whole frame.
            if (croppedPhoto !== countedFrame && !croppedPhoto.isRecycled) croppedPhoto.recycle()
            if (!countedFrame.isRecycled) countedFrame.recycle()

            SavedPhoto(imagePath = photoPath, rawImagePath = rawPhotoPath)
        } finally {
            // Runs on a failed save too, so the next Add still gets a fresh location.
            refreshLocation()
        }
    }

    /** Disables Add for [ADD_COOLDOWN_MS] while the captured frame is saved. */
    private fun startAddCooldown() {
        addCooldownJob?.cancel()
        _state.update { it.copy(isAddCooldown = true) }
        addCooldownJob = scope.launch {
            delay(ADD_COOLDOWN_MS)
            _state.update { it.copy(isAddCooldown = false) }
        }
    }

    /** Scanning-screen drug-image fallback, as a bitmap for the saved photo (keeps its aspect). */
    private fun drugImagePlaceholder(): Bitmap? =
        ContextCompat.getDrawable(context, R.drawable.prescription_icon)?.let { placeholderDrawable ->
            val placeholderHeight = if (placeholderDrawable.intrinsicWidth > 0) {
                300 * placeholderDrawable.intrinsicHeight / placeholderDrawable.intrinsicWidth
            } else 225
            placeholderDrawable.toBitmap(300, placeholderHeight.coerceAtLeast(1))
        }

    /** Hands over the latest analyzed frame; the caller now owns and recycles it. */
    private fun takeCurrentFrame(): Bitmap? = synchronized(frameLock) {
        val frame = currentFrameBitmap?.takeIf { latestFrame -> !latestFrame.isRecycled }
        currentFrameBitmap = null
        frame
    }

    /** Refreshes [cachedLocation] in the background; skipped while a lookup is running. */
    private fun refreshLocation() {
        if (locationJob?.isActive == true) return
        locationJob = scope.launch(Dispatchers.IO) {
            cachedLocation = locationProvider.getCurrentLocationAsString()
        }
    }

    /** Drops the pills on screen and forgets the last Add, so the same tray can be added again. */
    fun rescan() {
        clearDetectedPills()
        lastAddedScanSignature = null
        logger.i("Rescan triggered.")
    }

    /** Forgets the last Add's pill layout, so the same pills can be added again after a failed save. */
    fun forgetLastAdd() {
        lastAddedScanSignature = null
    }

    fun clearDetectedPills() {
        _state.update { it.copy(detectedPills = emptyList()) }
    }

    // ------------------------------------------------------------------------
    // Still photo, sound, pause controls, close
    // ------------------------------------------------------------------------

    /** Full-resolution still from the attached camera. False when there is no camera or it refused. */
    fun captureStill(onCaptured: (Bitmap) -> Unit, onError: () -> Unit): Boolean {
        val camera = cameraHelper ?: return false
        return camera.captureImage(onCaptured = onCaptured, onCaptureError = { onError() })
    }

    /** True when the backend's colour-image setting is off, so saved photos go grayscale. */
    fun isGrayscale(): Boolean = !preferenceHelper.getIsColorImageEnabled()

    fun playCountSoundIfEnabled() {
        if (preferenceHelper.isSoundEnabled()) {
            SoundUtils.playCountSound(context)
        }
        if (preferenceHelper.isHapticEnabled()) {
            triggerHaptic(context)
        }
    }

    private fun triggerHaptic(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, 255))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(120)
        }
    }

    fun pauseDetection() {
        isPaused = true
        _state.update { it.copy(detectedPills = emptyList(), trayDetections = emptyList()) }
        // processDetections early-returns while paused, so the release branch
        // there can never run. Drop the lock here instead.
        lastCompleteTrayMs = 0L
        cameraHelper?.setAeAwbLock(false)
    }

    /** Resume pill detection after a [pauseDetection] call. */
    fun resumeDetection() {
        isPaused = false
    }

    fun setCameraPaused(paused: Boolean) {
        _state.update { it.copy(isCameraPaused = paused) }
        if (paused) {
            isPaused = true
            _state.update { it.copy(detectedPills = emptyList(), trayDetections = emptyList()) }
        } else {
            isPaused = false
        }
    }

    /** Back to live counting after a still photo: unpause, hide the idle overlay, re-arm the watchdog. */
    fun resumeLiveCounting() {
        isPaused = false
        _state.update { it.copy(isCameraPaused = false, showIdleOverlay = false) }
        armIdleTimer()
    }

    /** Writes the performance summary and frees the held frame. Models stay loaded in the singleton. */
    fun close() {
        // Stops every job the engine started; the parent scope keeps running.
        scope.cancel()

        // Generate final summary report
        try {
            performanceLogger.generateSummaryReport()
            logger.i("Performance summary generated")
        } catch (error: Exception) {
            logger.e("Failed to generate performance summary", error, event = LogEvent.FUNCTIONALITY_ERROR)
        }

        synchronized(frameLock) {
            try {
                currentFrameBitmap?.recycle()
            } catch (error: Exception) {
                logger.w("Error recycling bitmap: ${error.message}")
            } finally {
                currentFrameBitmap = null
            }
        }
        _modelState.value = ModelState.Idle
        logger.i("Engine closed. Model remains loaded in Singleton.")
    }
}
