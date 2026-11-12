package com.dispensesure.retail.core.scanning.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.dispensesure.retail.R
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.engine.GloveGate.Companion.GLOVE_CONFIRM_FRAMES
import com.dispensesure.retail.core.scanning.engine.IdleWatchdog.Companion.IDLE_TAG
import com.dispensesure.retail.core.scanning.logic.CameraHelper
import com.dispensesure.retail.core.scanning.logic.Detection
import com.dispensesure.retail.core.scanning.logic.GloveDetection
import com.dispensesure.retail.core.scanning.logic.LoadedModels
import com.dispensesure.retail.core.scanning.logic.PillAnalyzer
import com.dispensesure.retail.core.scanning.logic.PillDetectionModelLoader
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.scanning.logic.TrayDetection
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.common.SoundUtils
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
import java.util.ArrayDeque

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
    // Crop, strip and JPEG files for each ADD; keeps the last known location too.
    private val addPhotoWriter = AddPhotoWriter(scope, context, locationProvider, userDao, preferenceHelper)
    @Volatile private var isAnalyzingFrame = false
    // Set on Main, read on the analyzer thread.
    @Volatile private var isPaused = false
    // Pauses the camera after a quiet minute; see pauseAndClearBuffers.
    private val idleWatchdog = IdleWatchdog(scope, onIdle = ::pauseAndClearBuffers)

    // Turns a held count change into activity for the idle watchdog.
    private val countConfirmer = CountConfirmer()

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

    // Decides when gloves are on; the engine then stops the glove model.
    private val gloveGate = GloveGate()

    // Hazardous tray rules; the engine acts on what it decides.
    private val hazardousTrayMonitor = HazardousTrayMonitor()

    // Performance monitoring
    private var performanceMonitorJob: Job? = null
    private val performanceSnapshotInterval = 10_000L // 10 seconds

    companion object {
        private const val ZERO_DETECTIONS_THRESHOLD = 25

        // How long the tray must be gone before AE/AWB unlock. Rides on top of
        // PillAnalyzer's TRAY_HOLD_FRAMES so a brief gate drop doesn't unlock.
        private const val AE_LOCK_RELEASE_DELAY_MS = 1000L

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
        addPhotoWriter.refreshLocation()
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
        shouldDetectTrayColor = { hazardousTrayMonitor.isDetectionEnabled },
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
     * [trayDetections] is forwarded to [EngineState.trayDetections] so [com.dispensesure.retail.core.scanning.presentation.compose.CountOverlay]
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

        // ── Hazardous tray: the monitor decides, the engine acts ─────────────
        val trayColor = trayDetections.firstOrNull { tray -> tray.trayColor != TrayColor.UNKNOWN }?.trayColor
        applyTrayDecision(hazardousTrayMonitor.observe(trayColor))

        // ── Check if gloves detected - if yes, stop running glove detection ──
        if (gloveGate.observe(gloveDetections)) {
            _state.update { it.copy(glovesDetected = true) }
            shouldRunGloveDetection = false
            val strongestGlove = gloveDetections.filter { glove -> glove.classId == 0 }.maxByOrNull { glove -> glove.confidence }
            logger.i("GLOVE_ICON — turning GREEN (bestConfidence=${strongestGlove?.confidence}, consecutiveFrames=$GLOVE_CONFIRM_FRAMES) - unloading glove model and saving DB flag")
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

        // Rolling count buffer
        val recentCounts = ArrayDeque(_lastTenDetections.value)
        if (recentCounts.size >= ZERO_DETECTIONS_THRESHOLD) recentCounts.removeFirst()
        recentCounts.addLast(count)
        _lastTenDetections.value = recentCounts

        if (countConfirmer.observe(count)) noteActivity()

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
        countConfirmer.reset()
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
        hazardousTrayMonitor.pauseForIdle()
        isPaused = true
        _state.update { it.copy(isCameraPaused = true) }
        logger.w("$IDLE_TAG camera paused due to idle timeout. Buffers cleared.")
    }

    fun armIdleTimer() = idleWatchdog.arm()

    /** Mark counting activity so the watchdog holds off. */
    fun noteActivity() = idleWatchdog.noteActivity()

    fun stopIdleTimer() = idleWatchdog.stop()

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
        countConfirmer.reset()
        lastCompleteTrayMs = 0L
        cameraHelper?.setAeAwbLock(false)
        lastAddedScanSignature = null
        _state.update { it.copy(trayDetections = emptyList(), gloveDetections = emptyList()) }

        hazardousTrayMonitor.resumeAfterIdle(
            hazardousSettingOn = preferenceHelper.isHazardousDrugEnabled(),
            savedHazardousColor = preferenceHelper.getHazardousTrayColor(),
        )

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
     * Starts tray checks for the COUNTING stage.
     * Hazardous drug: saves whether the saved hazardous tray was used. Other drugs: warns if it is used.
     */
    fun configureHazardous(isHazardousDrug: Boolean) {
        hazardousTrayMonitor.configure(
            isHazardousDrug = isHazardousDrug,
            hazardousSettingOn = preferenceHelper.isHazardousDrugEnabled(),
            savedHazardousColor = preferenceHelper.getHazardousTrayColor(),
        )
    }

    /** Saves the user's popup answer; a yes makes [color] the saved hazardous tray colour. */
    fun classifyTray(color: TrayColor, isHazardous: Boolean) {
        if (isHazardous) preferenceHelper.setHazardousTrayColor(color.name)
        applyTrayDecision(hazardousTrayMonitor.classify(color, isHazardous))
        _state.update { it.copy(pendingTrayColor = null) }
    }

    // Save results and warnings go out as events; a popup colour goes into state.
    private fun applyTrayDecision(trayDecision: TrayDecision) {
        when (trayDecision) {
            TrayDecision.None -> Unit
            is TrayDecision.Save -> _events.trySend(EngineEvent.HazardousTrayResult(trayDecision.detected))
            is TrayDecision.AskUser -> _state.update { it.copy(pendingTrayColor = trayDecision.color) }
            TrayDecision.WarnNotHazardousTray -> _events.trySend(EngineEvent.Warning(R.string.non_hazardous_tray_warning))
            TrayDecision.WarnHazardousTrayInUse -> _events.trySend(EngineEvent.Warning(R.string.hazardous_tray_warning))
        }
    }

    /**
     * Reset glove detection state.
     * Called when the pill scanning screen is loaded or when resuming from idle.
     */
    fun resetGloves() {
        _state.update { it.copy(glovesDetected = false) }
        shouldRunGloveDetection = true
        gloveGate.reset()
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
    ): SavedPhoto = addPhotoWriter.write(captured, label, keepRawCrop, grayscale = isGrayscale())

    /** Disables Add for [ADD_COOLDOWN_MS] while the captured frame is saved. */
    private fun startAddCooldown() {
        addCooldownJob?.cancel()
        _state.update { it.copy(isAddCooldown = true) }
        addCooldownJob = scope.launch {
            delay(ADD_COOLDOWN_MS)
            _state.update { it.copy(isAddCooldown = false) }
        }
    }

    /** Hands over the latest analyzed frame; the caller now owns and recycles it. */
    private fun takeCurrentFrame(): Bitmap? = synchronized(frameLock) {
        val frame = currentFrameBitmap?.takeIf { latestFrame -> !latestFrame.isRecycled }
        currentFrameBitmap = null
        frame
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
    fun isGrayscale(): Boolean = preferenceHelper.isGrayscaleImage()

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
