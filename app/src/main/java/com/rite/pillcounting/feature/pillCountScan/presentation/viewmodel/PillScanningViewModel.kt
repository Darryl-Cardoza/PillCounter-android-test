package com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.dtos.TxnWithDetails
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.settings.domain.model.enums.ScheduleCode
import com.rite.pillcounting.core.security.ImageCrypto
import com.rite.pillcounting.core.utils.common.HelperFunctions.saveBitmapToFile
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.common.OverlayUtils
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.pillCountScan.domain.PillDetectionModelLoader
import com.rite.pillcounting.feature.pillCountScan.domain.data.NavigationEvent
import com.rite.pillcounting.feature.pillCountScan.domain.data.PillScanningEvent
import com.rite.pillcounting.feature.pillCountScan.domain.model.DetectedPill
import com.rite.pillcounting.feature.pillCountScan.domain.model.PillScanningUiState
import com.rite.pillcounting.feature.pillCountScan.domain.model.TxnDetail
import com.rite.pillcounting.feature.pillCountScan.presentation.logic.CameraHelper
import com.rite.pillcounting.feature.pillCountScan.presentation.logic.Detection
import com.rite.pillcounting.feature.pillCountScan.presentation.logic.GloveDetection
import com.rite.pillcounting.feature.pillCountScan.presentation.logic.PillAnalyzer
import com.rite.pillcounting.feature.pillCountScan.presentation.logic.TrayDetection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import javax.inject.Inject

/**
 * ViewModel responsible for:
 * - Managing camera frame analysis (via Singleton ModelLoader).
 * - Updating UI state for pill counting workflow.
 * - Managing database transactions.
 * - Preventing duplicate "Add" operations without a new scan.
 */
@HiltViewModel
class PillScanningViewModel @Inject constructor(
    app: Application,
    private val preferenceHelper: PreferenceHelper,
    private val pillCountTxnDao: PillCountTxnDao,
    private val userDao: UserDao,
    private val pillCountTxnDetailsDao: PillCountTxnDetailsDao,
    private val locationProvider: LocationProvider,
    private val drugMasterDao: DrugMasterDao,
    private val modelLoader: PillDetectionModelLoader,
    private val performanceLogger: PerformanceLogger
) : AndroidViewModel(app) {

    private val logger = AppLogger("PillScanningVM")
    val context: Context = getApplication<Application>().applicationContext
    private var currentFrameBitmap: Bitmap? = null
    private var lastTransformationMatrix: Matrix? = null
    private var isAnalyzingFrame = false
    private var isPaused = false
    private var idleJob: Job? = null
    private val idleTimeout = 60_000L

    private val _uiState = MutableStateFlow(PillScanningUiState())
    val uiState: StateFlow<PillScanningUiState> = _uiState.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Idle)
    private val _navigationEvent = Channel<NavigationEvent>()
    val navigationEvent = _navigationEvent.receiveAsFlow()

    private val _lastTenDetections = MutableStateFlow(ArrayDeque<Int>())
    private val _cameraPaused = MutableStateFlow(false)
    val cameraPaused = _cameraPaused.asStateFlow()

    private var cameraHelper: CameraHelper? = null

    // --- Duplicate prevention ---
    private var currentScanId: Long = 0L

    // --- Detection snapshot ---
    private var lastDetectedSnapshot: List<Int> = emptyList()
    private var lastChangeTimestamp: Long = System.currentTimeMillis()

    /** Public read-only flow for observing recent detection counts. */
    val lastTenDetections: StateFlow<ArrayDeque<Int>> = _lastTenDetections

    // --- Duplicate prevention ---
    private var lastAddedScanSignature: String? = null
    private var lastAddClickTime: Long = 0L
    private val _addPopEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val addPopEvents = _addPopEvents.asSharedFlow()

    private val _currentStep = MutableStateFlow(StepState.TARGET_VERIFICATION)
    val currentStep = _currentStep.asStateFlow()

    private val _steps = MutableStateFlow<List<StepState>>(emptyList())
    val steps: StateFlow<List<StepState>> = _steps

    private val shutterSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
    }

    private val _capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedBitmap: StateFlow<Bitmap?> = _capturedBitmap

    private val _showFlash = MutableStateFlow(false)
    val showFlash: StateFlow<Boolean> = _showFlash

    private val _isTxnFromHl7 = MutableStateFlow(false)
    val isTxnFromHl7: StateFlow<Boolean> = _isTxnFromHl7

    private val _txnInfo = MutableStateFlow<TxnWithDetails?>(null)
    val txnInfo: StateFlow<TxnWithDetails?> = _txnInfo

    // ── Glove detection control: stop running glove model once gloves are detected ──
    private val _glovesDetected = MutableStateFlow(false)
    val glovesDetected: StateFlow<Boolean> = _glovesDetected.asStateFlow()

    var shouldRunGloveDetection = true
        private set

    // ── NEW: expose tray detections so the UI can draw the tray boundary ──────
    private val _trayDetections = MutableStateFlow<List<TrayDetection>>(emptyList())
    val trayDetections: StateFlow<List<TrayDetection>> = _trayDetections.asStateFlow()

    private var observeTxnDetailsJob: Job? = null
    private var stockCountBaseTotal: Int = -1

    private val _isSoundOverride = MutableStateFlow(preferenceHelper.isSoundOverride())
    val isSoundEnabled: StateFlow<Boolean> = _isSoundOverride.asStateFlow()
    
    // Performance monitoring
    private var performanceMonitorJob: Job? = null
    private val performanceSnapshotInterval = 10_000L // 10 seconds

    companion object {
        private const val ZERO_DETECTIONS_THRESHOLD = 25
    }

    /** Model initialization states */
    sealed class ModelState {
        object Idle : ModelState()
        object Loading : ModelState()
        data class Ready(val analyzer: PillAnalyzer) : ModelState()
        data class Error(val message: String, val cause: Throwable? = null) : ModelState()
    }

    init {
//        resetIdleTimer()
        startPerformanceMonitoring()
    }
    
    /**
     * Start periodic performance monitoring
     */
    private fun startPerformanceMonitoring() {
        performanceMonitorJob?.cancel()
        performanceMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(performanceSnapshotInterval)
                try {
                    performanceLogger.logPerformanceSnapshot("PERIODIC_MONITORING")
                } catch (e: Exception) {
                    logger.e("Performance monitoring failed", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Initialization and Observation
    // ------------------------------------------------------------------------

    /** Attach a CameraHelper instance for lifecycle control. */
    fun attachCameraHelper(helper: CameraHelper) {
        cameraHelper = helper
    }

    /** Observe all transaction details for the current transaction. */
    fun observeTxnDetailsForTxn(step: StepState) {
        observeTxnDetailsJob?.cancel()

        observeTxnDetailsJob = viewModelScope.launch {
            pillCountTxnDetailsDao.observeAllForTxn(preferenceHelper.getTxnId(), step)
                .collectLatest { entities ->
                    val history = entities.map {
                        TxnDetail(
                            txnDetailId = it.txnDetailsId,
                            count = it.pillCount ?: 0,
                            image = it.imagePath,
                            createdAt = it.createdAt,
                            type = step
                        )
                    }

                    val currentSum = history.sumOf { it.count }
                    val isStockCount = _txnInfo.value?.countType == CountType.REGULAR
                    if (isStockCount && stockCountBaseTotal == -1) {
                        stockCountBaseTotal = currentSum
                    }
                    val sessionTotal = if (isStockCount) {
                        maxOf(0, currentSum - stockCountBaseTotal)
                    } else {
                        currentSum
                    }

                    _uiState.update { state ->
                        state.copy(txnDetailHistory = history, stockCountSessionTotal = sessionTotal)
                    }

                    if (step == StepState.CONTAINER_PENDING) {
                        val countedPills = pillCountTxnDetailsDao.observeAllForTxn(
                            preferenceHelper.getTxnId(), StepState.CONTAINER_INITIATE
                        ).first().sumOf { it.pillCount ?: 0 }

                        val remainingPills = countedPills - (_txnInfo.value?.targetCount ?: 0)

                        _uiState.update {
                            it.copy(targetCount = remainingPills.coerceAtLeast(0))
                        }
                    }
                }
        }
    }

    /**
     * Initialize TensorFlow Lite interpreters using the Singleton Loader.
     *
     * Now calls [PillDetectionModelLoader.getOrLoadInterpreters] which returns
     * both the pill and tray interpreters loaded in parallel.
     */
    fun initializeInterpreter(
        retryCount: Int = 1, viewWidth: Int, viewHeight: Int
    ) {
        if (_modelState.value is ModelState.Ready) {
            logger.w("Interpreter already initialized, skipping reinitialization.")
            return
        }

        viewModelScope.launch {
            _modelState.value = ModelState.Loading

            try {
                // ── Load both models (returns immediately if already cached) ──
                val models = modelLoader.getOrLoadInterpreters()

                val analyzer = PillAnalyzer(
                    pillInterpreter  = models.pillInterpreter,
                    trayInterpreter  = models.trayInterpreter,
                    gloveInterpreter = models.gloveInterpreter,
                    performanceLogger = performanceLogger,
                    shouldRunGloveDetection = { shouldRunGloveDetection },
                    onResult = { count, detections, trayDetections, gloveDetections, bitmap, matrix, imageWidth, imageHeight ->
                        processDetections(
                            count         = count,
                            detections    = detections,
                            trayDets      = trayDetections,
                            gloveDets     = gloveDetections,
                            bitmap        = bitmap,
                            matrix        = matrix,
                            previewWidth  = viewWidth,
                            previewHeight = viewHeight,
                            imageWidth    = imageWidth,
                            imageHeight   = imageHeight
                        )
                    }
                )

                _modelState.value = ModelState.Ready(analyzer)
                logger.i("Both interpreters initialized successfully (via Singleton).")

            } catch (e: Exception) {
                logger.e("Interpreter init failed", e)
                _modelState.value = ModelState.Error("Interpreter initialization failed", e)
            }
        }
    }

    // ------------------------------------------------------------------------
    // Frame Processing and Detection Logic
    // ------------------------------------------------------------------------

    /**
     * Handle each analyzed frame and maintain rolling detection state.
     *
     * [trayDets] is forwarded to [_trayDetections] so [CameraPreviewSection]
     * can draw the tray bounding box overlay.
     */
    private fun processDetections(
        count: Int,
        detections: List<Detection>,
        trayDets: List<TrayDetection>,
        gloveDets: List<GloveDetection>,
        bitmap: Bitmap,
        matrix: Matrix,
        previewWidth: Int,
        previewHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ) {
        if (isPaused) {
            bitmap.recycle()
            return
        }

        currentScanId++
        currentFrameBitmap?.recycle()
        currentFrameBitmap = bitmap
        lastTransformationMatrix = Matrix(matrix)

        logger.d("Frame analyzed | count=$count | scanId=$currentScanId")

        // ── Publish tray & glove detections for the UI overlay ───────────────
        _trayDetections.value = trayDets
        _uiState.update { it.copy(gloveDetections = gloveDets) }

        // ── Check if gloves detected - if yes, stop running glove detection ──
        // Require a strong detection before locking the session state, otherwise a single
        // weak false positive on the warm-up frame disables glove detection permanently.
        if (gloveDets.any { it.classId == 0 && it.confidence >= 0.75f }) {
            _glovesDetected.value = true
            shouldRunGloveDetection = false
            logger.i("✅ GLOVES DETECTED - Stopping glove detection model")
        }

        // Rolling count buffer
        val buffer = ArrayDeque(_lastTenDetections.value)
        if (buffer.size >= ZERO_DETECTIONS_THRESHOLD) buffer.removeFirst()
        buffer.addLast(count)
        _lastTenDetections.value = buffer

        // Map pill centres to normalised [0..1] coordinates
        updateDetectedPills(
            pills = detections.map { det ->
                DetectedPill(
                    x = (det.rect.centerX() / imageWidth.toFloat()).coerceIn(0f, 1f),
                    y = (det.rect.centerY() / imageHeight.toFloat()).coerceIn(0f, 1f),
                    confidence = det.confidence
                )
            }, frameWidth = imageWidth, frameHeight = imageHeight
        )
    }

    /** Update the list of detected pills in UI state AND the frame dimensions. */
    private fun updateDetectedPills(
        pills: List<DetectedPill>, frameWidth: Int, frameHeight: Int
    ) {
        _uiState.update {
            it.copy(
                detectedPills = pills, imageFrameWidth = frameWidth, imageFrameHeight = frameHeight,
            )
        }
    }

    // DISABLED FOR PERFORMANCE MONITORING: Idle timeout functionality disabled
    // to ensure continuous scanning without interruptions
    private fun pauseAndClearBuffers() {
        _lastTenDetections.value.clear()
        lastDetectedSnapshot = emptyList()
        _uiState.update { it.copy(showIdleOverlay = true, gloveDetections = emptyList()) }
        _trayDetections.value = emptyList()
        isPaused = true
        _cameraPaused.value = true
        logger.w("Camera paused due to idle timeout. Buffers cleared.")
    }

    fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(idleTimeout)
            pauseAndClearBuffers()
        }
    }

    fun pauseIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    /** Process an incoming frame from CameraX. */
    fun onFrameCaptured(image: ImageProxy) {
        if (isPaused) {
            image.close()
            return
        }
        val currentState = _modelState.value
        if (currentState !is ModelState.Ready) {
            image.close()
            return
        }
        if (isAnalyzingFrame) {
            image.close()
            return
        }

        isAnalyzingFrame = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                currentState.analyzer.analyze(image)
            } catch (e: Exception) {
                logger.e("Frame analysis failed.", e)
                image.close()
            } finally {
                isAnalyzingFrame = false
            }
        }
    }

    /** Reset idle overlay and resume camera analysis. */
    fun resetIdleOverlay() {
        _uiState.update { it.copy(showIdleOverlay = false) }

        _lastTenDetections.value.clear()
        lastDetectedSnapshot = emptyList()
        lastChangeTimestamp = System.currentTimeMillis()
        lastAddedScanSignature = null
        _trayDetections.value = emptyList()
        _uiState.update { it.copy(gloveDetections = emptyList()) }

        // Reset glove detection state
        resetGloveDetection()

        isPaused = false
        _cameraPaused.value = false

        logger.i("Idle overlay reset -> Analysis resumed.")
    }

    fun updateFilteredPills(filtered: List<DetectedPill>) {
        // The Canvas overlay calls this from inside its draw block on every recompose.
        // Without a dedupe, every frame triggers a new _uiState emission, which
        // triggers another recompose, which redraws the Canvas — a tight loop that
        // burns frame budget on weak devices. Compare references first (cheap),
        // and only escalate to a structural compare when the reference differs.
        val current = _uiState.value.filteredPills
        if (current === filtered) return
        if (current.size == filtered.size &&
            current.indices.all { i -> current[i] === filtered[i] }) {
            return
        }
        _uiState.update { it.copy(filteredPills = filtered) }
    }

    /**
     * Reset glove detection state.
     * Called when the pill scanning screen is loaded or when resuming from idle.
     */
    fun resetGloveDetection() {
        _glovesDetected.value = false
        shouldRunGloveDetection = true
        // Also re-enable the analyzer's fast initial cadence so the first detection
        // after a reset arrives in one frame, not after the rate-limit window.
        (_modelState.value as? ModelState.Ready)?.analyzer?.resetGloveCadence()
        logger.i("🔄 Glove detection reset - Model will run on next frame")
    }

    override fun onCleared() {
        super.onCleared()

        // Stop performance monitoring
        performanceMonitorJob?.cancel()

        // Generate final summary report
        try {
            performanceLogger.generateSummaryReport()
            logger.i("Performance summary generated: ${performanceLogger.getLogFile().absolutePath}")
        } catch (e: Exception) {
            logger.e("Failed to generate performance summary", e)
        }

        try {
            currentFrameBitmap?.recycle()
        } catch (e: Exception) {
            logger.w("Error recycling bitmap: ${e.message}")
        } finally {
            currentFrameBitmap = null
        }
        _modelState.value = ModelState.Idle
        logger.i("ViewModel cleared. Model remains loaded in Singleton.")
    }

    // ------------------------------------------------------------------------
    // Event Handling
    // ------------------------------------------------------------------------

    fun onEvent(event: PillScanningEvent) {
        when (event) {
            is PillScanningEvent.AddTransactionDetailClicked -> handleAddTransaction(event)
            is PillScanningEvent.RescanClicked -> handleRescan()
            is PillScanningEvent.PauseClicked -> logger.i("Pause clicked.")
            PillScanningEvent.DoneClicked -> handleDone()
            is PillScanningEvent.NoteSaved -> handleNoteSaved(event)
            PillScanningEvent.NoteSkip -> handleNoteSkip()
            is PillScanningEvent.ConfirmDone -> handleConfirmDone()
            is PillScanningEvent.CancelDone -> handleCancelDone()
            is PillScanningEvent.TransactionDetailDeleted -> handleDeleteTransaction(event)
            is PillScanningEvent.AllTransactionDetailsDeleted -> handleDeleteAllTransactionDetails(
                event
            )

            is PillScanningEvent.FinalDone -> handleConfirmDialog(event)
            is PillScanningEvent.AddVialPhotoInTxn -> handleAddVialImageInTxn(event)
        }
    }

    private var addCooldownJob: Job? = null

    private fun startAddCooldown() {
        addCooldownJob?.cancel()
        _uiState.update { it.copy(isAddCooldown = true) }
        addCooldownJob = viewModelScope.launch {
            delay(3000)
            _uiState.update { it.copy(isAddCooldown = false) }
            lastAddClickTime = 0L
        }
    }

    private fun handleAddVialImageInTxn(event: PillScanningEvent.AddVialPhotoInTxn) {
        viewModelScope.launch(Dispatchers.IO) {
            val txnId = preferenceHelper.getTxnId()
            val bitmap = event.bitmap
            val filePath = try {
                if (!bitmap.isRecycled) {
                    saveBitmapToFile(
                        getApplication(),
                        bitmap,
                        "txn_detail_${System.currentTimeMillis()}.jpg",
                        "transaction_details",
                        grayscale = true
                    )
                } else null
            } catch (e: Exception) {
                logger.e("Failed saving bitmap", e)
                null
            }
            pillCountTxnDetailsDao.deleteVialByTxnId(txnId, StepState.VIAL)
            pillCountTxnDetailsDao.insert(
                PillCountTxnDetailsEntity(
                    txnId = txnId,
                    pillCount = 0,
                    imagePath = filePath,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    type = StepState.VIAL.toString()
                )
            )
        }
    }

    private fun handleAddTransaction(event: PillScanningEvent.AddTransactionDetailClicked) {
        val context: Context = getApplication<Application>().applicationContext
        val currentTime = System.currentTimeMillis()

        if (_uiState.value.isAddCooldown) {
            showToast(context, context.getString(R.string.add_button_wait))
            logger.w("Add action ignored: cooldown active.")
            return
        }

        val stepType = event.stepType
        val totalBatchCount =
            _uiState.value.txnDetailHistory.filter { it.type == stepType }.sumOf { it.count }
        val targetCount = _uiState.value.targetCount
        val currentCount = event.filteredCount
        val predictedTotal = totalBatchCount + currentCount
        val skipRestriction = stepType in listOf(StepState.CONTAINER_INITIATE)

        if (!skipRestriction) {
            if (_uiState.value.scanType == CountType.FIXED.toString() && predictedTotal > targetCount) {
                _uiState.update { it.copy(restrictAdd = true) }
                logger.w("Add blocked: predicted total exceeds target count.")
                return
            }
        }
        if (currentCount == 0) {
            showToast(context, context.getString(R.string.add_zero_detected))
            logger.w("Add blocked: detected count is 0.")
            return
        }
        triggerAddPop(currentCount)

        val signature = _uiState.value.detectedPills.joinToString(separator = "|") {
            "${"%.3f".format(it.x)}-${"%.3f".format(it.y)}"
        } + "|count=$currentCount"

        if (signature == lastAddedScanSignature) {
            showToast(context, context.getString(R.string.duplicate_scan_ignored))
            logger.w("Duplicate add prevented: no change in detection pattern.")
            return
        }

        startAddCooldown()

        lastAddClickTime = currentTime
        lastAddedScanSignature = signature
        logger.i("Adding transaction detail. Count=$currentCount")

        viewModelScope.launch(Dispatchers.IO) {
            val userId = preferenceHelper.getUserId().orEmpty()
            val user = userDao.getByUserId(userId)
            val location = locationProvider.getCurrentLocationAsString()

            val base = currentFrameBitmap
            if (base == null || base.isRecycled) {
                logger.e("Base frame bitmap is null or recycled, skipping save")
                return@launch
            }

            val workingBitmap = try {
                base.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: Exception) {
                logger.e("Failed to copy base bitmap", e)
                return@launch
            }

            val filteredPills = _uiState.value.filteredPills
            val txnId = preferenceHelper.getTxnId()
            val txn = pillCountTxnDao.getById(txnId)
            val drug = drugMasterDao.getDrugById(txn?.drugId)

            val overlayBitmap = if (filteredPills.isNotEmpty()) {
                try {
                    OverlayUtils.drawDetectionsOnBitmap(
                        bitmap = workingBitmap,
                        detectedPills = filteredPills,
                        previewWidth = cameraHelper?.getPreviewWidth() ?: workingBitmap.width,
                        previewHeight = cameraHelper?.getPreviewHeight() ?: workingBitmap.height,
                        userName = listOfNotNull(user?.fName, user?.lName).joinToString(" "),
                        userId = user?.userId,
                        location = location,
                        timestamp = System.currentTimeMillis(),
                        ndc = drug?.ndc,
                        count = currentCount.toString(),
                        rx = txn?.rxNo,
                        stepLabel = stepType.name,
                    )
                } catch (e: Exception) {
                    logger.e("Overlay drawing failed, using bitmap without overlay", e)
                    workingBitmap
                }
            } else {
                workingBitmap
            }

            val filePath = try {
                if (!overlayBitmap.isRecycled) {
                    saveBitmapToFile(
                        getApplication(),
                        overlayBitmap,
                        "txn_detail_${System.currentTimeMillis()}.jpg",
                        "transaction_details",
                        grayscale = true
                    )
                } else null
            } catch (e: Exception) {
                logger.e("Failed saving bitmap", e)
                null
            }

            pillCountTxnDetailsDao.insert(
                PillCountTxnDetailsEntity(
                    txnId = txnId,
                    pillCount = currentCount,
                    imagePath = filePath,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    type = stepType.toString()
                )
            )

            if (txn?.countType == CountType.REGULAR) {
                pillCountTxnDao.incrementLooseQty(txnId, currentCount)
            }

            if (!workingBitmap.isRecycled) workingBitmap.recycle()
            observeTxnDetailsForTxn(stepType)
            currentFrameBitmap = null

            logger.i("Transaction detail saved. Count=$currentCount, File=$filePath")
        }
    }

    private fun handleRescan() {
        _uiState.update { it.copy(detectedPills = emptyList()) }
        lastAddedScanSignature = null
        logger.i("Rescan triggered.")
    }

    fun showEndStockCountDialog(){
        _uiState.update {
            it.copy(showEndStockCountDialog = true)
        }
    }

    private fun handleConfirmDialog(event: PillScanningEvent.FinalDone) {
        if (_txnInfo.value?.countType == CountType.REGULAR) {
            _uiState.update {
                it.copy(showEndStockCountDialog = true)
            }
            return
        }

        when (event.stepType) {
            StepState.CONTAINER_INITIATE -> {
                val target = _txnInfo.value?.targetCount ?: return
                if (target < event.totalCount) {
                    _uiState.update { it.copy(showDialogForControl = true) }
                } else {
                    _uiState.update {
                        it.copy(showErrorMessage = context.getString(R.string.pills_count_should_be_greater_than_target_count))
                    }
                }
            }

            StepState.CONTAINER_PENDING -> {
                val remainingCount =
                    _uiState.value.targetCount - _uiState.value.txnDetailHistory.sumOf { it.count }
                if (remainingCount > 0) {
                    _uiState.update { it.copy(showCountMismatchDialog = true) }
                    pausePillDetection()
                } else {
                    handleDone()
                }
            }

            StepState.TARGET_VERIFICATION -> {
                if (_txnInfo.value?.countType == CountType.FIXED && _uiState.value.targetCount == _uiState.value.txnDetailHistory.sumOf { it.count }) {
                    _uiState.update { it.copy(showDialogForControl = true) }
                } else if (_txnInfo.value?.countType == CountType.REGULAR) {
                    handleDone()
                } else {
                    _uiState.update { it.copy(showErrorMessage = context.getString(R.string.pills_count_should_be_greater_than_target_count)) }
                }
            }


            StepState.TARGET_REVERIFICATION -> {
                if (_uiState.value.targetCount == _uiState.value.txnDetailHistory.sumOf { it.count }) {
                    _uiState.update { it.copy(showDialogForControl = true) }
                } else {
                    _uiState.update {
                        it.copy(showErrorMessage = context.getString(R.string.pills_count_should_be_greater_than_target_count))
                    }
                }
            }

            else -> _uiState.update { it.copy(showDialogForControl = true) }
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(showErrorMessage = null) }
    }

    fun handleDismissDialog() {
        _uiState.update { it.copy(showDialogForControl = false) }
        _uiState.update { it.copy(showCountMismatchDialog = false) }
        _uiState.update { it.copy(showEndStockCountDialog = false) }
    }

    private fun handleDone() {
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            val total = pillCountTxnDetailsDao.getTotalPillCountForTxn(txnId)
            if (total == 0) {
                _uiState.update { it.copy(showNoTransaction = true) }
                return@launch
            }
            if (txnInfo.value?.countType == CountType.REGULAR) {
                val txn = pillCountTxnDao.getById(txnId) ?: return@launch
                pillCountTxnDao.updateTxnStatus(txnId, CountStatus.COMPLETED)
                _navigationEvent.send(NavigationEvent.NavigateToBatch(txn.batchId ?: 0))
                return@launch
            }
            val remainingCount =
                _uiState.value.targetCount - _uiState.value.txnDetailHistory.sumOf { it.count }
            if (!_isTxnFromHl7.value && preferenceHelper.getShowNotesDialogSetting()) {
                _uiState.update { it.copy(showNotesDialog = true) }
            } else if (_isTxnFromHl7.value && remainingCount > 0 && _currentStep.value == StepState.CONTAINER_PENDING) {
                _uiState.update { it.copy(showNotesDialog = true) }
            } else {
                showConfirmDialogAfterDone()
            }
        }
    }

    private fun handleNoteSaved(event: PillScanningEvent.NoteSaved) {
        setNoteDialogShown(false)
        viewModelScope.launch {
            pillCountTxnDao.updateNote(preferenceHelper.getTxnId(), event.note)
            showConfirmDialogAfterDone()
        }
    }

    private fun handleNoteSkip() {
        setNoteDialogShown(false)
        showConfirmDialogAfterDone()
    }

    private fun handleConfirmDone() {
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            val total = pillCountTxnDetailsDao.getTotalPillCountForTxn(txnId)
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch
            if (total == 0) {
                _uiState.update { it.copy(showConfirmDialog = false) }
                return@launch
            }
            val status =
                if (txn.countType == CountType.FIXED && txn.targetCount != null && total < txn.targetCount) CountStatus.PARTIAL else CountStatus.COMPLETED

            if (txn.isComingFromHL7 == true) {
                pillCountTxnDao.markCompletedAndUnsynced(txnId = txnId, status = status)
            } else {
                pillCountTxnDao.updateTxnStatus(txnId, status)
            }

            _navigationEvent.send(NavigationEvent.NavigateToDashboard)
            logger.i("Transaction completed. Status=$status")
        }
    }

    private fun handleCancelDone() {
        _uiState.update { it.copy(showConfirmDialog = false) }
        logger.i("Confirm dialog cancelled.")
    }

    private fun handleDeleteTransaction(event: PillScanningEvent.TransactionDetailDeleted) {
        viewModelScope.launch {
            pillCountTxnDetailsDao.softDelete(event.txnDetailId)
            logger.i("Transaction detail deleted. Id=${event.txnDetailId}")
        }
    }

    private fun handleDeleteAllTransactionDetails(event: PillScanningEvent.AllTransactionDetailsDeleted) {
        stockCountBaseTotal = -1
        viewModelScope.launch {
            pillCountTxnDetailsDao.softDeleteAllTransaction(
                preferenceHelper.getTxnId(), type = event.stepType
            )
            logger.i("All transaction details deleted for txnId=${preferenceHelper.getTxnId()}")
        }
    }

    // ------------------------------------------------------------------------
    // UI Utility Functions
    // ------------------------------------------------------------------------

    fun resetRestrictAdd() = _uiState.update { it.copy(restrictAdd = false) }

    fun resetNoTransaction() = _uiState.update { it.copy(showNoTransaction = false) }

    fun setTargetCountDialogShown(shown: Boolean) =
        _uiState.update { it.copy(showTargetCountDialog = shown) }

    fun setNoteDialogShown(shown: Boolean) = _uiState.update { it.copy(showNotesDialog = shown) }

    fun setScanType(type: String) {
        _uiState.update { it.copy(scanType = type) }
        logger.d("Scan type set to $type")
    }

    fun updateTargetCount(target: Int) {
        _uiState.update { it.copy(targetCount = target) }
        viewModelScope.launch {
            pillCountTxnDao.updateTargetCount(preferenceHelper.getTxnId(), target)
            logger.i("Target count updated to $target")
        }
    }

    fun showTxnInfo(countType: String) {
        viewModelScope.launch {
            val txnInfo = pillCountTxnDao.getTxnWithDetails(preferenceHelper.getTxnId())
            _txnInfo.value = txnInfo

            val shouldShowDialog =
                countType == CountType.FIXED.toString() && (txnInfo?.targetCount == null || txnInfo.targetCount == 0) && !_uiState.value.showTargetCountDialog

            _uiState.update {
                it.copy(
                    drugName = txnInfo?.drugName.orEmpty(),
                    targetCount = txnInfo?.targetCount ?: 0,
                    showTargetCountDialog = shouldShowDialog,
                )
            }
            logger.d("Txn info loaded. Drug=${txnInfo?.drugName}, Target=${txnInfo?.targetCount}")
        }
    }

    fun getDrugInfo() {
        viewModelScope.launch {
            val txnInfo = pillCountTxnDao.getTxnWithDetails(preferenceHelper.getTxnId())
            _txnInfo.value = txnInfo
            val countType = txnInfo?.countType
            val isComingFromHL7 = txnInfo?.isComingFromHL7 ?: false
            val drugId = txnInfo?.drugId
            val drugInfo = drugMasterDao.getDrugById(drugId)
            val controlledSchedules = setOf(
                ScheduleCode.CII,
                ScheduleCode.CIII,
                ScheduleCode.CIV,
                ScheduleCode.CV,
                ScheduleCode.CVI
            )

            _isTxnFromHl7.value = isComingFromHL7

            _steps.value = when {
                isComingFromHL7 && drugInfo?.drugType?.let {
                    runCatching { ScheduleCode.valueOf(it) }.getOrNull()
                } in controlledSchedules -> buildWorkflowSteps(
                    isFromHl7 = true,
                    simpleFlow = false,
                    drugType = drugInfo?.drugType.orEmpty(),
                    countType = countType
                )

                isComingFromHL7 && drugInfo?.drugType?.let {
                    runCatching { ScheduleCode.valueOf(it) }.getOrNull()
                } !in controlledSchedules -> buildWorkflowSteps(
                    isFromHl7 = true,
                    simpleFlow = true,
                    drugType = drugInfo?.drugType.orEmpty(),
                    countType = countType
                )

                else -> buildWorkflowSteps(
                    isFromHl7 = false,
                    simpleFlow = true,
                    drugType = drugInfo?.drugType.orEmpty(),
                    countType = countType
                )
            }

            val currentSteps = _steps.value
            val savedWorkflowStep = txnInfo?.workflowStep
                ?.let { runCatching { StepState.valueOf(it) }.getOrNull() }

            val resolvedStep = when {
                savedWorkflowStep == null -> {
                    // No saved step yet — fall back to deriving from details history
                    val latestStep = pillCountTxnDetailsDao.getLatestType(preferenceHelper.getTxnId())
                    if (drugInfo?.drugType.equals("null", true)) {
                        latestStep ?: StepState.TARGET_VERIFICATION
                    } else {
                        latestStep ?: StepState.CONTAINER_INITIATE
                    }
                }
                savedWorkflowStep in currentSteps -> savedWorkflowStep
                else -> {
                    // Saved step was removed by a settings change (e.g. double-count or
                    // back-count toggled off).  Find the nearest valid step:
                    //   - For a middle step (TARGET_REVERIFICATION): advance to the next
                    //     step that still exists in the workflow.
                    //   - For a tail step (CONTAINER_PENDING): fall back to the last
                    //     remaining step (VIAL).
                    val savedOrdinal = savedWorkflowStep.ordinal
                    currentSteps.firstOrNull { it.ordinal > savedOrdinal } ?: currentSteps.last()
                }
            }

            _currentStep.value = resolvedStep
            if (resolvedStep == StepState.VIAL) {
                pausePillDetection()
                loadExistingVialPhoto(preferenceHelper.getTxnId())
            }
            observeTxnDetailsForTxn(resolvedStep)
        }
    }

    private fun loadExistingVialPhoto(txnId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val vialDetail = pillCountTxnDetailsDao.observeAllForTxn(txnId, StepState.VIAL).first().firstOrNull()
                val imagePath = vialDetail?.imagePath ?: return@launch
                val file = java.io.File(imagePath)
                if (!file.exists()) return@launch
                val rawBytes = file.readBytes()
                val jpegBytes = if (ImageCrypto.isEncrypted(rawBytes)) {
                    ImageCrypto.decrypt(rawBytes)
                } else {
                    rawBytes
                }
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return@launch
                _capturedBitmap.value = bitmap
                logger.i("Loaded existing vial photo from $imagePath")
            } catch (e: Exception) {
                logger.e("Failed to load existing vial photo", e)
            }
        }
    }

    private fun showConfirmDialogAfterDone() {
        _uiState.update { it.copy(showConfirmDialog = true) }
    }

    fun triggerAddPop(count: Int) {
        _addPopEvents.tryEmit(count)
    }

    fun playCountSoundIfEnabled() {
        if (preferenceHelper.isSoundEnabled()) {
            shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
        }
        if (preferenceHelper.isHapticEnabled()) {
            triggerHaptic(context)
        }
    }

    private fun triggerHaptic(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
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

    fun moveNextStep() {
        val steps = _steps.value
        if (steps.isEmpty()) {
            handleDone(); return
        }

        val current = _currentStep.value
        val index = steps.indexOf(current)
        if (index == steps.lastIndex) {
            handleDone(); return
        }

        val next = steps[index + 1]

        if (next == StepState.VIAL) pausePillDetection()
        if (next == StepState.CONTAINER_PENDING) {
            redoCaptureImage()
            resetIdleOverlay()
            _uiState.update { it.copy(targetCount = _txnInfo.value?.targetCount ?: 0) }
        }

        _currentStep.value = next
        _uiState.update { it.copy(showDialogForControl = false) }
        observeTxnDetailsForTxn(next)

        viewModelScope.launch(Dispatchers.IO) {
            pillCountTxnDao.updateWorkflowStep(preferenceHelper.getTxnId(), next.name)
        }
    }

    fun buildWorkflowSteps(
        isFromHl7: Boolean, simpleFlow: Boolean, drugType: String, countType: CountType?
    ): List<StepState> {

        if (countType?.equals(CountType.REGULAR) == true) {
            return listOf(StepState.SCAN, StepState.TARGET_VERIFICATION)
        }

        if (countType?.equals(CountType.FIXED) == true && simpleFlow && drugType.equals("null",true)) {
            return listOf(StepState.SCAN, StepState.TARGET_VERIFICATION, StepState.VIAL)
        }

        val steps = mutableListOf(
            StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION
        )

        val controlDrugTypes = preferenceHelper.getControlDrugTypes()
        val shouldDoubleCount =
            preferenceHelper.isRequireDoubleCountEnabled() && controlDrugTypes.contains(drugType)

        if (shouldDoubleCount) steps.add(StepState.TARGET_REVERIFICATION)

        steps.add(StepState.VIAL)

        if (preferenceHelper.isRequireBackCountEnabled()) steps.add(StepState.CONTAINER_PENDING)

        return steps
    }

    fun captureImage() {
        viewModelScope.launch {
            _showFlash.value = true
            delay(1300)
            _showFlash.value = false
        }
        cameraHelper?.captureImage { bitmap -> _capturedBitmap.value = bitmap }
    }

    fun redoCaptureImage() {
        _capturedBitmap.value = null
    }

    fun saveCaptureImage() {
        _capturedBitmap.value?.let { processCapturedImage(it) }
    }

    private fun processCapturedImage(bitmap: Bitmap) {
        onEvent(PillScanningEvent.AddVialPhotoInTxn(0, bitmap))
        moveNextStep()
        isPaused = false
    }

    fun pausePillDetection() {
        isPaused = true
        _uiState.update { it.copy(detectedPills = emptyList()) }
        _trayDetections.value = emptyList()
    }

    /** Resume pill detection after a [pausePillDetection] call. */
    fun resumePillDetection() {
        isPaused = false
    }

    fun setCameraPaused(paused: Boolean) {
        _cameraPaused.value = paused
        if (paused) {
            isPaused = true
            _uiState.update { it.copy(detectedPills = emptyList()) }
            _trayDetections.value = emptyList()
        } else {
            isPaused = false
        }
    }
}