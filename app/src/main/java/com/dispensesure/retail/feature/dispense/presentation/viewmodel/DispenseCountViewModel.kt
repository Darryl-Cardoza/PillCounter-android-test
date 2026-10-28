package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.dispensesure.retail.R
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.models.DISPENSED_QUANTITY_STEP
import com.dispensesure.retail.core.models.ScheduleCode
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.scanning.domain.model.TxnDetail
import com.dispensesure.retail.core.scanning.engine.CaptureResult
import com.dispensesure.retail.core.scanning.engine.EngineEvent
import com.dispensesure.retail.core.scanning.engine.EngineState
import com.dispensesure.retail.core.scanning.engine.PhotoLabel
import com.dispensesure.retail.core.scanning.engine.PillCountingEngine
import com.dispensesure.retail.core.scanning.engine.toastText
import com.dispensesure.retail.core.scanning.logic.CameraHelper
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.security.ImageCrypto
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourActionTimeoutMs
import com.dispensesure.retail.core.tourguide.TutorialData
import com.dispensesure.retail.core.utils.common.HelperFunctions.saveBitmapToFile
import com.dispensesure.retail.core.utils.common.OverlayUtils
import com.dispensesure.retail.core.utils.common.SoundUtils
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
import com.dispensesure.retail.core.utils.common.deleteFilesQuietly
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountNavigation
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountUiState
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import javax.inject.Inject

/**
 * ViewModel responsible for:
 * - Managing camera frame analysis (via [PillCountingEngine]).
 * - Updating UI state for pill counting workflow.
 * - Managing database transactions.
 * - Preventing duplicate "Add" operations without a new scan.
 */
@HiltViewModel
class DispenseCountViewModel @Inject constructor(
    app: Application,
    private val preferenceHelper: PreferenceHelper,
    private val dispenseTxnDao: DispenseTxnDao,
    private val operatorNameProvider: OperatorNameProvider,
    private val dispenseCountDao: DispenseCountDao,
    private val drugMasterDao: DrugMasterDao,
    private val scanDecoder: ScanDecoder,
    private val hl7Repository: Hl7Repository,
    private val appDatabase: AppDatabase,
    engineFactory: PillCountingEngine.Factory,
) : AndroidViewModel(app) {

    private val logger = AppLogger("DispenseCountVM")
    val context: Context = getApplication<Application>().applicationContext
    // Camera, ML, idle, gloves, tray and the Add photo. Declared before anything that reads it.
    private val engine = engineFactory.create(viewModelScope)
    private var resetAvailabilityJob: Job? = null

    private val _uiState = MutableStateFlow(DispenseCountUiState())
    val uiState: StateFlow<DispenseCountUiState> = _uiState.asStateFlow()

    private val _navigation = Channel<DispenseCountNavigation>()
    val navigation = _navigation.receiveAsFlow()

    val cameraPaused: StateFlow<Boolean> = engine.state
        .map { engineState -> engineState.isCameraPaused }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Public read-only flow for observing recent detection counts. */
    val lastTenDetections: StateFlow<ArrayDeque<Int>> = engine.lastTenDetections

    /** The engine's live camera state, for the count overlay. */
    val engineState: StateFlow<EngineState> = engine.state

    val addPopEvents: Flow<Int> = engine.addPops

    private val _currentStep = MutableStateFlow(StepState.TARGET_VERIFICATION)
    val currentStep = _currentStep.asStateFlow()

    private val _steps = MutableStateFlow<List<StepState>>(emptyList())
    val steps: StateFlow<List<StepState>> = _steps

    // --- Dispense-flow bottle tracking (rescan-same-NDC during counting) ---
    // No in-memory cache of the bottle list: every scan re-reads
    // DispenseTxnEntity.bottlesJson fresh from Room, so a resumed/killed-and-restarted
    // process always compares against the real persisted state. isProcessingBottleScan only
    // guards against the same physical scan firing this handler multiple times in a row
    // (camera frame analyzer re-detecting the same barcode across consecutive frames) while a
    // scan is already being resolved or its confirm dialog is awaiting a user tap.
    @Volatile private var isProcessingBottleScan = false
    private var pendingBottleScan: DispenseBottle? = null

    private val _capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedBitmap: StateFlow<Bitmap?> = _capturedBitmap

    private val _showFlash = MutableStateFlow(false)
    val showFlash: StateFlow<Boolean> = _showFlash

    // True from an accepted capture request until its bitmap or error lands.
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    // True once the still on screen has been written, so a Done re-tap cannot write it twice.
    private var captureCommitted = false

    /** Tour: set once Done goes straight to confirm (notes setting off), so the VIAL wait ends. */
    private var confirmDoneStarted = false

    private val _txnInfo = MutableStateFlow<DispenseTxnDetail?>(null)
    val txnInfo: StateFlow<DispenseTxnDetail?> = _txnInfo

    // From [EngineState.glovesDetected].
    val glovesDetected: StateFlow<Boolean> = engine.state
        .map { engineState -> engineState.glovesDetected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var observeTxnDetailsJob: Job? = null

    private val _isSoundOverride = MutableStateFlow(preferenceHelper.isSoundOverride())
    val isSoundEnabled: StateFlow<Boolean> = _isSoundOverride.asStateFlow()

    // Must stay below _uiState: the first copy runs here.
    init {
        mirrorEngineState()
        viewModelScope.launch { engine.events.collect { engineEvent -> onEngineEvent(engineEvent) } }
    }

    /** Copies the engine's state into the flows the count screens already read. */
    private fun mirrorEngineState() {
        viewModelScope.launch {
            engine.state.collect { engineState ->
                _uiState.update {
                    it.copy(
                        detectedPills = engineState.detectedPills,
                        filteredPills = engineState.filteredPills,
                        gloveDetections = engineState.gloveDetections,
                        showIdleOverlay = engineState.showIdleOverlay,
                        isAddCooldown = engineState.isAddCooldown,
                        pendingTrayColorForClassification = engineState.pendingTrayColor,
                    )
                }
            }
        }
    }

    /** Saves the engine's gloves / tray results on the dispense txn and shows its tray warnings; the +N pop goes straight to the screen. */
    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            EngineEvent.GlovesConfirmed -> saveGlovesPresent()
            is EngineEvent.HazardousTrayResult -> saveHazardousTrayDetected(event.detected)
            is EngineEvent.Warning -> showToast(context.getString(event.messageRes))
        }
    }

    // ------------------------------------------------------------------------
    // Initialization and Observation
    // ------------------------------------------------------------------------

    /** Attach a CameraHelper instance for lifecycle control. */
    fun attachCameraHelper(helper: CameraHelper) {
        engine.attachCamera(helper)
        // A capture lost to an unbound camera never calls back, so clear the stuck flag here.
        _isCapturing.value = false
    }

    /** Observe all transaction details for the current transaction. */
    fun observeTxnDetailsForTxn(step: StepState) {
        observeTxnDetailsJob?.cancel()

        observeTxnDetailsJob = viewModelScope.launch {
            dispenseCountDao.observeAllForTxn(preferenceHelper.getTxnId(), step)
                .catch { e -> logger.e("Failed observing txn details for step=$step", e, event = LogEvent.PILL_COUNT_FAILED) }
                .collectLatest { entities ->
                    val history = entities.map {
                        TxnDetail(
                            txnDetailId = it.countId,
                            count = it.pillCount ?: 0,
                            image = it.imagePath,
                            createdAt = it.createdAt,
                            type = step
                        )
                    }

                    _uiState.update { state ->
                        state.copy(txnDetailHistory = history)
                    }

                    if (step == StepState.CONTAINER_PENDING) {
                        val countedPills = dispenseCountDao.observeAllForTxn(
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
     * Appends [newCountId] to the currently active (last) bottle's [DispenseBottle.countIds]
     * and persists it. No-op if the txn has no bottle entries yet (no bottle scanned before
     * this pill was counted — legacy/no-GS1-scan txns). [bottles] is the list resolved once by
     * the caller (avoids a redundant decode per staged row when flushing several at once);
     * the write itself re-reads+re-writes fresh each call so concurrent appends don't clobber
     * each other.
     */
    private suspend fun linkCountToActiveBottle(txnId: Long, bottles: List<DispenseBottle>, newCountId: Long) {
        if (bottles.isEmpty()) return
        val txn = dispenseTxnDao.getById(txnId) ?: return
        val current = DispenseBottleJson.decode(txn.bottlesJson).toMutableList()
        if (current.isEmpty()) return
        val lastIndex = current.lastIndex
        current[lastIndex] = current[lastIndex].copy(
            countIds = current[lastIndex].countIds + newCountId
        )
        dispenseTxnDao.updateBottles(txnId, DispenseBottleJson.encode(current))
    }

    /** Removes deleted detail ids from the txn's bottle list so it holds no dead ids. */
    private suspend fun unlinkDetailsFromBottles(txnId: Long, deletedIds: Set<Long>) {
        val txn = dispenseTxnDao.getById(txnId) ?: return
        val bottles = DispenseBottleJson.decode(txn.bottlesJson)
        if (bottles.none { bottle -> bottle.countIds.any { it in deletedIds } }) return
        val cleaned = bottles.map { it.copy(countIds = it.countIds - deletedIds) }
        dispenseTxnDao.updateBottles(txnId, DispenseBottleJson.encode(cleaned))
    }

    /** Loads the ML models once the preview size is known; see [PillCountingEngine.start]. */
    fun initializeInterpreter() = engine.start()

    /** Loads the glove model for a hazardous drug; see [PillCountingEngine.loadGloveModel]. */
    fun loadGloveModelAndRebuildAnalyzer() = engine.loadGloveModel()

    // ------------------------------------------------------------------------
    // Frame Processing and Detection Logic
    // ------------------------------------------------------------------------

    fun resetIdleTimer() = engine.armIdleTimer()

    /** Mark counting activity so the watchdog holds off. */
    fun noteCountActivity() = engine.noteActivity()

    fun pauseIdleTimer() = engine.stopIdleTimer()

    /** Process an incoming frame from CameraX. */
    fun onFrameCaptured(image: ImageProxy) = engine.onFrame(image)

    /** Reset idle overlay and resume camera analysis. */
    fun resetIdleOverlay() = engine.resumeFromIdle()

    fun updateFilteredPills(filtered: List<DetectedPill>) = engine.onFilteredPills(filtered)

    /** Called when the COUNTING stage begins; see [PillCountingEngine.configureHazardous]. */
    fun setHazardousTransaction(isHazardousByDrug: Boolean) = engine.configureHazardous(isHazardousByDrug)

    /** Saves the user's popup answer; a yes makes [color] the saved hazardous tray colour. */
    fun classifyTrayColor(color: TrayColor, isHazardous: Boolean) = engine.classifyTray(color, isHazardous)

    private fun saveHazardousTrayDetected(detected: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val txnId = preferenceHelper.getTxnId()
            if (txnId != 0L) {
                dispenseTxnDao.updateHazardousTrayDetected(txnId, detected)
                logger.i("hazardousTrayDetected=$detected saved for txnId=$txnId")
                logger.d("DB updated: hazardousTrayDetected=$detected txnId=$txnId")
            }
        }
    }

    /** Saves the engine's glove lock-in on the current dispense txn. */
    private fun saveGlovesPresent() {
        viewModelScope.launch {
            try {
                val txnId = preferenceHelper.getTxnId()
                if (txnId != 0L) {
                    dispenseTxnDao.updateGlovesPresent(txnId, true)
                    logger.i("isGlovesPresent saved for txn=$txnId")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to save glovesPresent flag", e, event = LogEvent.GLOVE_DETECT_FAILED)
            }
        }
    }

    /**
     * Throws away everything collected for the active count so it can restart at the
     * container scan. Hard deletes only — nothing here is recoverable.
     *
     * Runs on viewModelScope rather than the caller's: leaving the screen mid-reset must
     * not strand a txn whose details are deleted but whose flags are still set. The caller
     * joins, so the stage flip still happens after the delete.
     *
     * @return false when the reset failed (an error toast is shown); the caller must not move on.
     */
    suspend fun resetTransaction(): Boolean {
        var ok = false
        viewModelScope.launch {
            try {
                performReset()
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to reset transaction", e, event = LogEvent.PILL_COUNT_RESET_FAILED)
                showGenericError()
            }
        }.join()
        return ok
    }

    private fun showGenericError() {
        _uiState.update { it.copy(showErrorMessage = context.getString(R.string.error_generic)) }
    }

    private suspend fun performReset() {
        val txnId = preferenceHelper.getTxnId()
        if (txnId != 0L) {
            withContext(Dispatchers.IO) {
                // Files first: once the rows are gone their paths are unrecoverable.
                deleteFilesQuietly(dispenseCountDao.getImagePathsForTxn(txnId), logger)
                dispenseCountDao.deleteAllForTxn(txnId)
                dispenseTxnDao.resetForRecount(txnId)
            }
        }

        _capturedBitmap.value = null
        _txnInfo.value = null
        engine.clearDetectedPills()
        _uiState.update {
            it.copy(
                txnDetailHistory = emptyList(),
            )
        }
        logger.i("Transaction reset. txnId=$txnId")
    }

    /**
     * Keeps reset availability live. A count that has reached the pharmacy system can no
     * longer be thrown away locally, and that can land while the screen is still up.
     */
    fun observeResetAvailability() {
        resetAvailabilityJob?.cancel()
        resetAvailabilityJob = viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            when {
                txnId != 0L -> dispenseTxnDao.observeById(txnId)
                    .catch { e -> logger.e("Failed observing txn reset availability. txnId=$txnId", e, event = LogEvent.PILL_COUNT_RESET_FAILED) }
                    .collect { txn ->
                    _uiState.update { it.copy(canReset = txn?.isSynced != true) }
                }
                // No txn yet: nothing has reached the pharmacy system.
                else -> _uiState.update { it.copy(canReset = true) }
            }
        }
    }

    /** Clear the workflow step list so the WorkflowStepper hides when returning to QUEUE/PRE_RX. */
    fun resetWorkflowSteps() {
        _steps.value = emptyList()
        _currentStep.value = StepState.TARGET_VERIFICATION
    }

    /**
     * Reset glove detection state.
     * Called when the pill scanning screen is loaded or when resuming from idle.
     */
    fun resetGloveDetection() = engine.resetGloves()

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }

    // ------------------------------------------------------------------------
    // Action Handling
    // ------------------------------------------------------------------------

    fun onAction(action: DispenseCountAction) {
        when (action) {
            is DispenseCountAction.AddTransactionDetailClicked -> handleAddTransaction(action)
            is DispenseCountAction.RescanClicked -> handleRescan()
            is DispenseCountAction.PauseClicked -> logger.i("Pause clicked.")
            DispenseCountAction.DoneClicked -> handleDone()
            is DispenseCountAction.NoteSaved -> handleNoteSaved(action)
            DispenseCountAction.NoteSkip -> handleNoteSkip()
            is DispenseCountAction.TransactionDetailDeleted -> handleDeleteTransaction(action)
            is DispenseCountAction.AllTransactionDetailsDeleted -> handleDeleteAllTransactionDetails(
                action
            )

            is DispenseCountAction.FinalDone -> handleConfirmDialog(action)
            is DispenseCountAction.AddVialPhotoInTxn -> handleAddVialImageInTxn(action)
            is DispenseCountAction.ConfirmAddBottle -> handleConfirmAddBottle()
            is DispenseCountAction.CancelAddBottle -> handleCancelBottleDialog(isAdd = true)
            is DispenseCountAction.ConfirmReplaceBottle -> handleConfirmReplaceBottle()
            is DispenseCountAction.CancelReplaceBottle -> handleCancelBottleDialog(isAdd = false)
        }
    }

    /** Tutorial txns are marked by the demo Rx prefix or the demo drug, the same markers the purge uses. */
    private suspend fun isDemoTxn(txnId: Long): Boolean {
        val txn = dispenseTxnDao.getById(txnId) ?: return false
        return txn.rxNo?.startsWith(TutorialData.RX_PREFIX) == true ||
            drugMasterDao.getDrugById(txn.drugId)?.ndc == TutorialData.DEMO_NDC
    }

    private fun handleAddVialImageInTxn(action: DispenseCountAction.AddVialPhotoInTxn) {
        FtueController.track(viewModelScope.launch(Dispatchers.IO) {
            try {
                val txnId = preferenceHelper.getTxnId()
                val bitmap = action.bitmap
                // The tour's grey placeholder isn't worth a file an interruption could orphan.
                val filePath = if (FtueController.state.isActive) null else try {
                    if (!bitmap.isRecycled) {
                        saveBitmapToFile(
                            getApplication(),
                            bitmap,
                            "txn_detail_${System.currentTimeMillis()}.jpg",
                            "transaction_details",
                            grayscale = isGrayscaleImage(),
                        )
                    } else null
                } catch (e: Exception) {
                    logger.e("Failed saving bitmap", e, event = LogEvent.FILE_WRITE_ERROR)
                    null
                }
                dispenseCountDao.deleteVialByTxnId(txnId, StepState.VIAL)
                dispenseCountDao.insert(
                    DispenseCountEntity(
                        txnId = txnId,
                        pillCount = 0,
                        imagePath = filePath,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        step = StepState.VIAL.toString()
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to save vial photo transaction detail", e, event = LogEvent.VIAL_SCAN_FAILED)
            }
        })
    }

    private fun handleAddTransaction(action: DispenseCountAction.AddTransactionDetailClicked) {
        val context: Context = getApplication<Application>().applicationContext

        val stepType = action.stepType
        val totalBatchCount =
            _uiState.value.txnDetailHistory.filter { historyRow -> historyRow.type == stepType }.sumOf { historyRow -> historyRow.count }
        val targetCount = _uiState.value.targetCount
        val currentCount = action.filteredCount
        val predictedTotal = totalBatchCount + currentCount
        val skipRestriction = stepType in listOf(StepState.CONTAINER_INITIATE)

        if (!skipRestriction) {
            if (predictedTotal > targetCount) {
                _uiState.update { it.copy(restrictAdd = true) }
                logger.w("Add blocked: predicted total exceeds target count.")
                return
            }
        }
        val capturedAdd = when (val captureResult = engine.captureCount(currentCount)) {
            is CaptureResult.Rejected -> {
                showToast(context.getString(captureResult.reason.toastText()))
                return
            }
            is CaptureResult.Captured -> captureResult
        }
        logger.i("Adding transaction detail. Count=$currentCount")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val txnId = preferenceHelper.getTxnId()
                val dispenseTxn = dispenseTxnDao.getById(txnId)
                val orderedDrug = drugMasterDao.getDrugById(dispenseTxn?.drugId)

                // Resolve the currently active bottle (last one scanned) once — used both to
                // watermark this photo with its lot/exp/serial and to tag the detail row below.
                val scannedBottles = DispenseBottleJson.decode(dispenseTxn?.bottlesJson)
                val activeBottle = scannedBottles.lastOrNull()

                // Name, image and dispensed NDC follow the substitute when there is one.
                val dispensedDrug = dispenseTxn?.takeIf { txn -> txn.isSubstitute }?.substitutedDrugId
                    ?.let { substituteDrugId -> drugMasterDao.getDrugById(substituteDrugId) } ?: orderedDrug

                val photoLabel = PhotoLabel(
                    drugName = dispensedDrug?.drugName,
                    drugImagePath = dispensedDrug?.drugImagePath,
                    isStockCount = false,
                    requestedNdc = orderedDrug?.ndc,
                    dispensedNdc = dispensedDrug?.ndc,
                    lotNumber = activeBottle?.lotNumber,
                    serialNumber = activeBottle?.serialNumber,
                    expirationDate = activeBottle?.expirationDate,
                    rxRefill = OverlayUtils.rxRefill(dispenseTxn?.rxNo, dispenseTxn?.refillNo),
                    stepLabel = stepType.name,
                )
                // TARGET_VERIFICATION also keeps the crop with nothing drawn on it, for PMS.
                val savedPhoto = engine.saveCountPhoto(
                    capturedAdd,
                    photoLabel,
                    keepRawCrop = stepType == StepState.TARGET_VERIFICATION,
                )

                val countRow = DispenseCountEntity(
                    txnId = txnId,
                    pillCount = currentCount,
                    imagePath = savedPhoto.imagePath,
                    rawImagePath = savedPhoto.rawImagePath,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    step = stepType.toString(),
                )

                // Persist each ADD immediately so a session backed out before Done is
                // still saved (shows under Pending). The DB observer
                // (observeTxnDetailsForTxn) drives uiState.
                // One transaction so a View All delete can't overwrite the bottle list mid-link.
                appDatabase.withTransaction {
                    val newCountId = dispenseCountDao.insert(countRow)
                    linkCountToActiveBottle(txnId, scannedBottles, newCountId)
                }

                logger.i("Transaction detail INSERTED (immediate). Count=$currentCount, File=${savedPhoto.imagePath}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to add transaction detail", e, event = LogEvent.PILL_COUNT_FAILED)
                // Let the same pills be added again.
                engine.forgetLastAdd()
                showGenericError()
            } finally {
                if (!capturedAdd.frame.isRecycled) capturedAdd.frame.recycle()
            }
        }
    }

    private fun handleRescan() = engine.rescan()

    private fun handleConfirmDialog(action: DispenseCountAction.FinalDone) {
        when (action.stepType) {
            StepState.CONTAINER_INITIATE -> {
                val target = _txnInfo.value?.targetCount ?: return
                if (target <= action.totalCount) {
                    moveNextStep()
                } else {
                    _uiState.update {
                        it.copy(showErrorMessage = context.getString(R.string.pills_count_should_be_greater_than_target_count))
                    }
                }
            }

            StepState.CONTAINER_PENDING -> {
                val remainingCount =
                    _uiState.value.targetCount - _uiState.value.txnDetailHistory.sumOf { it.count }
                if (remainingCount > 0 && _currentStep.value == StepState.CONTAINER_PENDING) {
                    _uiState.update { it.copy(showCountMismatchDialog = true) }
                    pausePillDetection()
                } else {
                    handleDone()
                }
            }

            StepState.TARGET_VERIFICATION -> {
                if (_txnInfo.value != null && _uiState.value.targetCount == _uiState.value.txnDetailHistory.sumOf { it.count }) {
                    moveNextStep()
                } else {
                    _uiState.update { it.copy(showErrorMessage = context.getString(R.string.pills_count_should_be_greater_than_target_count)) }
                }
            }


            StepState.TARGET_REVERIFICATION -> {
                if (_uiState.value.targetCount == _uiState.value.txnDetailHistory.sumOf { it.count }) {
                    moveNextStep()
                } else {
                    _uiState.update {
                        it.copy(showErrorMessage = context.getString(R.string.pills_count_should_be_greater_than_target_count))
                    }
                }
            }

            else -> moveNextStep()
        }
    }

    /**
     * Tour-only escape hatch for "count_add_circle"'s dummy "Next": seeds a manual txn-detail
     * row since [handleConfirmDialog] validates against real DB-backed
     * [DispenseCountUiState.txnDetailHistory], ignoring the event's own totalCount; loops since
     * one tap must clear every chained [StepState] sharing this circle, not just the current one.
     */
    fun dummyCompleteCount() {
        // Hard guard, not just an unreachable-UI assumption: this seeds fabricated
        // DB rows, so it must never run outside an active tutorial regardless of
        // what calls it.
        if (!FtueController.state.isActive) return
        FtueController.track(viewModelScope.launch {
            // The txn pointer is global and an HL7 order may have set it: never write a demo count to a real txn.
            if (!isDemoTxn(preferenceHelper.getTxnId())) return@launch
            val countingCircleSteps = setOf(
                StepState.CONTAINER_INITIATE,
                StepState.TARGET_VERIFICATION,
                StepState.TARGET_REVERIFICATION,
            )
            var guard = 0
            while (_currentStep.value in countingCircleSteps && guard < countingCircleSteps.size + 1) {
                guard++
                val stepType = _currentStep.value
                val target = _uiState.value.targetCount
                val already = _uiState.value.txnDetailHistory.filter { it.type == stepType }.sumOf { it.count }
                val shortfall = target - already
                if (shortfall > 0) {
                    val txnId = preferenceHelper.getTxnId()
                    val detail = DispenseCountEntity(
                        txnId = txnId,
                        pillCount = shortfall,
                        imagePath = null,
                        step = stepType.toString(),
                        isManual = true,
                    )
                    val newId = dispenseCountDao.insert(detail)
                    // Update uiState synchronously here rather than waiting on the DB's
                    // reactive Flow observer (observeTxnDetailsForTxn) to catch up — the
                    // FinalDone dispatched right below reads _uiState.value.txnDetailHistory
                    // immediately, before that Flow would have had a chance to re-emit.
                    _uiState.update { state ->
                        state.copy(
                            txnDetailHistory = state.txnDetailHistory + TxnDetail(
                                txnDetailId = newId,
                                count = shortfall,
                                createdAt = detail.createdAt,
                                image = null,
                                type = stepType,
                            )
                        )
                    }
                }
                val totalCount = _uiState.value.txnDetailHistory.filter { it.type == stepType }.sumOf { it.count }
                val before = _currentStep.value
                onAction(DispenseCountAction.FinalDone(stepType = stepType, totalCount = totalCount))
                // handleConfirmDialog → moveNextStep() assigns _currentStep.value
                // synchronously (not suspending), so this is already up to date for the
                // loop condition above — bail out if it somehow didn't move, rather than
                // spinning on a step our shortfall calc didn't actually satisfy.
                if (_currentStep.value == before) break
            }
        })
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(showErrorMessage = null) }
    }

    fun handleDismissDialog() {
        _uiState.update { it.copy(showDialogForControl = false) }
        _uiState.update { it.copy(showCountMismatchDialog = false) }
    }

    private fun handleDone() {
        _uiState.update { it.copy(showCountMismatchDialog = false) }
        viewModelScope.launch {
            try {
                val txnId = preferenceHelper.getTxnId()
                val total = dispenseCountDao.getTotalPillCountForTxn(txnId)
                if (total == 0) {
                    _uiState.update { it.copy(showNoTransaction = true) }
                    return@launch
                }
                val remainingCount =
                    _uiState.value.targetCount - _uiState.value.txnDetailHistory.sumOf { it.count }
                if (preferenceHelper.getShowNotesDialogSetting()) {
                    _uiState.update { it.copy(showNotesDialog = true) }
                } else if (remainingCount > 0 && _currentStep.value == StepState.CONTAINER_PENDING) {
                    _uiState.update { it.copy(showNotesDialog = true) }
                } else {
                    handleConfirmDone()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to finalize Done for current step", e, event = LogEvent.PILL_COUNT_FAILED)
                showGenericError()
            }
        }
    }

    private fun handleNoteSaved(action: DispenseCountAction.NoteSaved) {
        setNoteDialogShown(false)
        viewModelScope.launch {
            try {
                dispenseTxnDao.updateNote(preferenceHelper.getTxnId(), action.note)
                handleConfirmDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to save note and confirm done", e, event = LogEvent.PILL_COUNT_FAILED)
                showGenericError()
            }
        }
    }

    private fun handleNoteSkip() {
        setNoteDialogShown(false)
        handleConfirmDone()
    }

    private fun handleConfirmDone() {
        confirmDoneStarted = true
        FtueController.track(
            viewModelScope.launch {
                try {
                    val txnId = preferenceHelper.getTxnId()
                    val total = dispenseCountDao.getTotalPillCountForTxn(txnId)
                    val txn = dispenseTxnDao.getById(txnId) ?: return@launch
                    if (total == 0) {
                        return@launch
                    }
                    // Only the prescribed-count step decides partial vs complete. The pour-out and
                    // recount steps count other pills and used to push this past the target.
                    val dispensedCount = dispenseCountDao
                        .getPillCountForStep(txnId, DISPENSED_QUANTITY_STEP.name)
                    val status =
                        if (txn.targetCount != null && dispensedCount < txn.targetCount) CountStatus.PARTIAL else CountStatus.COMPLETED

                    // Stamp who ran this count now, not at send time: an unsynced txn resent later
                    // must still report this operator, not whoever is at the device then.
                    // MUST be written before the status flips to COMPLETED — that write wakes
                    // observePendingHl7Txn, whose resend sweep reads this row and would build a
                    // message with a blank operator if it got there first.
                    val operator = operatorNameProvider()
                    dispenseTxnDao.updateOperatorName(txnId, operator.firstName, operator.lastName)

                    if (txn.isComingFromHL7 == true) {
                        dispenseTxnDao.markCompletedAndUnsynced(txnId = txnId, status = status)
                    } else {
                        dispenseTxnDao.updateTxnStatus(txnId, status)
                    }

                    // Send the dispense to the PMS now, not on the next reconnect. Completion used to
                    // only persist the status; the HL7 message went out when the MLLP connection next
                    // re-established, so a pharmacist finishing a count on a stable connection saw
                    // nothing arrive at the Companion. Failures are fine — the row stays unsynced and
                    // the resend-on-connect sweep retries it.
                    hl7Repository.sendDispenseNow(txnId)

                    _capturedBitmap.value = null
                    _navigation.send(DispenseCountNavigation.NavigateToDashboard)
                    logger.i("Transaction completed. Status=$status")

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(
                        "Failed to confirm/complete transaction",
                        e,
                        event = LogEvent.PILL_COUNT_FAILED
                    )
                    showGenericError()
                }
            },
        )
    }

    private fun handleDeleteTransaction(action: DispenseCountAction.TransactionDetailDeleted) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Files first: once the row is gone its paths are unrecoverable.
                deleteFilesQuietly(dispenseCountDao.getImagePathsForCount(action.txnDetailId), logger)
                // One transaction so an Add can't overwrite the bottle list mid-unlink.
                appDatabase.withTransaction {
                    dispenseCountDao.hardDelete(action.txnDetailId)
                    unlinkDetailsFromBottles(preferenceHelper.getTxnId(), setOf(action.txnDetailId))
                }
                logger.i("Transaction detail deleted. Id=${action.txnDetailId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to delete transaction detail. Id=${action.txnDetailId}", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    private fun handleDeleteAllTransactionDetails(action: DispenseCountAction.AllTransactionDetailsDeleted) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val txnId = preferenceHelper.getTxnId()
                // Files first: once the rows are gone their paths are unrecoverable.
                deleteFilesQuietly(dispenseCountDao.getImagePathsForStep(txnId, action.stepType), logger)
                // One transaction so an Add can't overwrite the bottle list mid-unlink.
                appDatabase.withTransaction {
                    val deletedIds = dispenseCountDao.getIdsForStep(txnId, action.stepType).toSet()
                    dispenseCountDao.hardDeleteAllForStep(txnId, action.stepType)
                    unlinkDetailsFromBottles(txnId, deletedIds)
                }
                logger.i("All transaction details deleted for txnId=$txnId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to delete all transaction details for step=${action.stepType}", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    // ------------------------------------------------------------------------
    // UI Utility Functions
    // ------------------------------------------------------------------------

    /** True when the backend's colour-image setting is off, so saved photos go grayscale. */
    fun isGrayscaleImage(): Boolean = engine.isGrayscale()

    fun resetRestrictAdd() = _uiState.update { it.copy(restrictAdd = false) }

    fun resetNoTransaction() = _uiState.update { it.copy(showNoTransaction = false) }

    fun setTargetCountDialogShown(shown: Boolean) =
        _uiState.update { it.copy(showTargetCountDialog = shown) }

    fun setNoteDialogShown(shown: Boolean) = _uiState.update { it.copy(showNotesDialog = shown) }

    fun updateTargetCount(target: Int) {
        _uiState.update { it.copy(targetCount = target) }
        viewModelScope.launch {
            try {
                dispenseTxnDao.updateTargetCount(preferenceHelper.getTxnId(), target)
                logger.i("Target count updated to $target")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to update target count to $target", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    fun showTxnInfo() {
        viewModelScope.launch {
            try {
                val txnInfo = dispenseTxnDao.getDispenseTxnDetail(preferenceHelper.getTxnId())
                _txnInfo.value = txnInfo

                val shouldShowDialog =
                    (txnInfo?.targetCount == null || txnInfo.targetCount == 0) && !_uiState.value.showTargetCountDialog

                _uiState.update {
                    it.copy(
                        drugName = txnInfo?.drugName.orEmpty(),
                        ndc = txnInfo?.ndc.orEmpty(),
                        strength = txnInfo?.strength.orEmpty(),
                        dosageForm = txnInfo?.dosageForm.orEmpty(),
                        bucket = txnInfo?.bucketId?.takeIf { b -> b.isNotBlank() }
                            ?: context.getString(R.string.default_bucket),
                        targetCount = txnInfo?.targetCount ?: 0,
                        showTargetCountDialog = shouldShowDialog,
                        drugImage = txnInfo?.drugImage.orEmpty()
                    )
                }
                logger.d("Txn info loaded. Drug=${txnInfo?.drugName}, Target=${txnInfo?.targetCount}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to load txn info", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    fun getDrugInfo(forceStartStep: StepState? = null) {
        viewModelScope.launch {
            try {
                val txnInfo = dispenseTxnDao.getDispenseTxnDetail(preferenceHelper.getTxnId())
                _txnInfo.value = txnInfo
                val drugId = txnInfo?.drugId
                val drugInfo = drugMasterDao.getDrugById(drugId)

                _steps.value = resolveWorkflowSteps(txnInfo, drugInfo)

                val currentSteps = _steps.value
                val savedWorkflowStep = txnInfo?.workflowStep
                    ?.let { runCatching { StepState.valueOf(it) }.getOrNull() }

                val resolvedStep = when {
                    // Caller explicitly overrides the start step (e.g. DispenseFlowScreen
                    // entering COUNTING after NDC was already scanned in PRE_NDC, so we
                    // skip straight to the pill-count step).
                    forceStartStep != null -> forceStartStep
                    savedWorkflowStep == null -> {
                        // No saved step yet — fall back to deriving from details history
                        val latestStep = dispenseCountDao.getLatestStep(preferenceHelper.getTxnId())
                        if (drugInfo?.drugType.isNullOrEmpty() || drugInfo?.drugType.equals("null", true)) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to load drug info / resolve workflow step", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    /**
     * Always-on rescan hook for the dispense pill-counting steps (TARGET_VERIFICATION /
     * TARGET_REVERIFICATION). Fires on every barcode the camera decodes while the
     * pharmacist is actively counting pills for a dispense txn, so a second bottle of the same
     * drug can be detected mid-count.
     *
     * Behavior (bottle info is always read fresh from Room — no in-memory cache — so a
     * killed/resumed process still compares against the real persisted state):
     * - Ignored if not currently on a dispense counting step, or the decoded NDC doesn't match
     *   the active txn's NDC (wrong-drug scans are dropped silently, no lookup/popup).
     * - A non-GS1 barcode carries no lot/exp/serial: treated as a count-only bottle entry.
     * - If the decoded lot+exp+serial exactly match the last bottle on file → toast only.
     * - Else if pills have already been counted since the last bottle was recorded → show the
     *   "add new bottle" confirm dialog (adds a new bottle entry on confirm).
     * - Else (nothing counted yet against the last bottle) → show the "replace bottle" confirm
     *   dialog (overwrites the last bottle's lot/exp/serial on confirm).
     */
    fun onNdcRescannedDuringCount(rawValue: String, imagePath: String?) {
        val countingSteps = setOf(StepState.TARGET_VERIFICATION, StepState.TARGET_REVERIFICATION)
        if (_currentStep.value !in countingSteps) return
        if (_txnInfo.value == null) return
        if (isProcessingBottleScan) return
        isProcessingBottleScan = true

        viewModelScope.launch {
            try {
                val txnId = preferenceHelper.getTxnId()
                val activeNdc = _txnInfo.value?.ndc
                if (activeNdc.isNullOrBlank()) return@launch

                val scan = scanDecoder.decode(rawValue)

                val scannedDrug = scan.gtin14?.let { drugMasterDao.getDrugByGtin(it) ?: drugMasterDao.getDrugByNdc(it) }
                if (scannedDrug == null || scannedDrug.ndc != activeNdc) {
                    // Not the same drug (or unreadable) — ignore, no popup, no lookup, no DB write.
                    return@launch
                }

                val lotNumber = scan.lot
                val expirationDate = scan.expiry
                val serialNumber = scan.serial

                val txn = dispenseTxnDao.getById(txnId) ?: return@launch
                val bottles = DispenseBottleJson.decode(txn.bottlesJson).toMutableList()
                val lastBottle = bottles.lastOrNull()

                if (lastBottle != null && scan.isGs1 &&
                    lastBottle.lotNumber == lotNumber &&
                    lastBottle.expirationDate == expirationDate &&
                    lastBottle.serialNumber == serialNumber
                ) {
                    _uiState.update { it.copy(showErrorMessage = context.getString(R.string.bottle_already_scanned)) }
                    return@launch
                }

                val currentCount = dispenseCountDao.getTotalPillCountForTxn(txnId)
                val scannedBottle = DispenseBottle(
                    lotNumber = lotNumber,
                    expirationDate = expirationDate,
                    serialNumber = serialNumber,
                    txnId = txnId,
                    barcodeImagePath = imagePath,
                )
                pendingBottleScan = scannedBottle
                if (currentCount > 0 || bottles.isEmpty()) {
                    _uiState.update { it.copy(showAddBottleDialog = true) }
                } else {
                    _uiState.update { it.copy(showReplaceBottleDialog = true) }
                }
            } catch (e: Exception) {
                logger.e("BOTTLE_SCAN onNdcRescannedDuringCount failed", e, event = LogEvent.NDC_SCAN_FAILED)
            } finally {
                isProcessingBottleScan = false
            }
        }
    }

    private fun handleConfirmAddBottle() {
        val pending = pendingBottleScan
        _uiState.update { it.copy(showAddBottleDialog = false) }
        if (pending == null) return
        viewModelScope.launch {
            try {
                val txnId = preferenceHelper.getTxnId()
                val txn = dispenseTxnDao.getById(txnId) ?: return@launch
                val bottles = DispenseBottleJson.decode(txn.bottlesJson).toMutableList()
                bottles.add(pending)
                dispenseTxnDao.updateBottles(txnId, DispenseBottleJson.encode(bottles))
                pendingBottleScan = null
                logger.i("BOTTLE_SCAN added new bottle for txnId=$txnId, total bottles=${bottles.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("BOTTLE_SCAN failed to add new bottle", e, event = LogEvent.NDC_SCAN_FAILED)
            }
        }
    }

    private fun handleConfirmReplaceBottle() {
        val pending = pendingBottleScan
        _uiState.update { it.copy(showReplaceBottleDialog = false) }
        if (pending == null) return
        viewModelScope.launch {
            try {
                val txnId = preferenceHelper.getTxnId()
                val txn = dispenseTxnDao.getById(txnId) ?: return@launch
                val bottles = DispenseBottleJson.decode(txn.bottlesJson).toMutableList()
                if (bottles.isEmpty()) {
                    bottles.add(pending)
                } else {
                    bottles[bottles.lastIndex] = pending
                }
                dispenseTxnDao.updateBottles(txnId, DispenseBottleJson.encode(bottles))
                pendingBottleScan = null
                logger.i("BOTTLE_SCAN replaced last bottle for txnId=$txnId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("BOTTLE_SCAN failed to replace last bottle", e, event = LogEvent.NDC_SCAN_FAILED)
            }
        }
    }

    private fun handleCancelBottleDialog(isAdd: Boolean) {
        pendingBottleScan = null
        _uiState.update {
            if (isAdd) it.copy(showAddBottleDialog = false) else it.copy(showReplaceBottleDialog = false)
        }
    }

    private fun loadExistingVialPhoto(txnId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val vialDetail = dispenseCountDao.observeAllForTxn(txnId, StepState.VIAL).first().firstOrNull()
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
                // Re-entering VIAL with an existing photo must leave Done usable.
                captureCommitted = false
                logger.i("Loaded existing vial photo from $imagePath")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to load existing vial photo", e, event = LogEvent.VIAL_SCAN_FAILED)
            }
        }
    }

    fun playCountSoundIfEnabled() = engine.playCountSoundIfEnabled()

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

        if (next == StepState.VIAL) {
            pausePillDetection()
            // The VIAL step is a still-photo capture: the live counting camera is
            // intentionally off, so no frames arrive to keep the idle watchdog
            // alive. Cancel it here, otherwise it fires after ~60s on the photo
            // screen and leaves the "Counting paused / RESUME" overlay up once the
            // user finishes the vial photo (the overlay is cleared again in
            // processCapturedImage when leaving VIAL).
            pauseIdleTimer()
        }
        if (next == StepState.CONTAINER_PENDING) {
            redoCaptureImage()
            resetIdleOverlay()
            _uiState.update { it.copy(targetCount = _txnInfo.value?.targetCount ?: 0) }
        }

        _currentStep.value = next
        // Clear the previous step's history/running total BEFORE wiring up the
        // new step's observer. Each step counts into its own StepState rows, so
        // on entry the new step starts at 0. observeTxnDetailsForTxn() only
        // overwrites uiState once its first DB emission lands; without this reset
        // the panel briefly shows the prior step's count (e.g. "30/30" on the
        // first TARGET_VERIFICATION entry instead of "0/30").
        _uiState.update {
            it.copy(
                showDialogForControl = false,
                txnDetailHistory = emptyList(),
            )
        }
        observeTxnDetailsForTxn(next)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                dispenseTxnDao.updateWorkflowStep(preferenceHelper.getTxnId(), next.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to persist workflow step=$next", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    /**
     * Derives the workflow steps for a transaction. HL7 entries on a controlled drug
     * run the full flow; everything else runs the simple one.
     */
    private fun resolveWorkflowSteps(
        txnInfo: DispenseTxnDetail?, drugInfo: DrugMasterEntity?
    ): List<StepState> {
        val isComingFromHL7 = txnInfo?.isComingFromHL7 ?: false
        val controlledSchedules = setOf(
            ScheduleCode.CII,
            ScheduleCode.CIII,
            ScheduleCode.CIV,
            ScheduleCode.CV,
            ScheduleCode.CVI
        )
        val isControlled = drugInfo?.drugType?.let {
            runCatching { ScheduleCode.valueOf(it) }.getOrNull()
        } in controlledSchedules

        return buildWorkflowSteps(
            isFromHl7 = isComingFromHL7,
            simpleFlow = !(isComingFromHL7 && isControlled),
            drugType = drugInfo?.drugType.orEmpty(),
            // Every dispense_txn row is a dispense; null keeps the no-transaction fallback.
            isDispense = if (txnInfo != null) true else null
        )
    }

    /**
     * Lands the workflow on the SCAN step: publishes the steps and marks SCAN as the
     * current one, so the stepper can be shown there like any other step.
     * [getDrugInfo] reassigns both when counting starts. The saved workflowStep column
     * is deliberately not touched — a resume must not come back to SCAN.
     */
    fun enterScanStep() {
        // Set first, so the stepper never renders a stale step while the steps load.
        _currentStep.value = StepState.SCAN
        viewModelScope.launch {
            try {
                val txnInfo = dispenseTxnDao.getDispenseTxnDetail(preferenceHelper.getTxnId())
                _steps.value = resolveWorkflowSteps(txnInfo, drugMasterDao.getDrugById(txnInfo?.drugId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to resolve workflow steps entering SCAN step", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    fun buildWorkflowSteps(
        isFromHl7: Boolean, simpleFlow: Boolean, drugType: String, isDispense: Boolean?
    ): List<StepState> {

        if (isDispense == true && simpleFlow && (drugType.isEmpty() || drugType.equals("null", true))) {
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

    /**
     * True when VIAL is the final step of the active workflow. The auto-capture
     * path uses this to decide whether scanning the vial should finish the flow
     * outright (complete the transaction, or show the notes prompt when that
     * setting is on) or merely capture the still and wait for the user to tap Done
     * before advancing to the remaining step(s).
     */
    fun isVialLastStep(): Boolean = _steps.value.lastOrNull() == StepState.VIAL

    /**
     * Capture the VIAL still photo.
     *
     * @param autoConfirm when true (auto-capture path, where the vial's RX matched
     *   the active transaction), immediately commit the photo as if the user tapped
     *   "Done" once the bitmap lands — this advances the workflow and completes the
     *   transaction. When false (manual capture), the still is shown and the user
     *   confirms via the Done button.
     *
     * A second call while a capture is still in flight is a no-op: no sound, no
     * flash, no capture request. See [isCapturing].
     */
    fun captureImage(autoConfirm: Boolean = false) {
        if (_isCapturing.value) return
        _isCapturing.value = true

        val captureStarted = engine.captureStill(
            onCaptured = { vialStill ->
                _isCapturing.value = false
                // A capture that lands after the user already left VIAL would
                // re-show the still overlay on top of the next step.
                if (_currentStep.value == StepState.VIAL) {
                    _capturedBitmap.value = vialStill
                    // A fresh still is unsaved, so the flag can never outlive the still it guards.
                    captureCommitted = false
                    if (autoConfirm) saveCaptureImage()
                }
            },
            onError = { _isCapturing.value = false }
        )
        if (!captureStarted) {
            _isCapturing.value = false
            return
        }

        SoundUtils.playCaptureSound()
        viewModelScope.launch {
            _showFlash.value = true
            delay(350)
            _showFlash.value = false
        }
    }

    /** Tour: saves a blank demo vial photo (no camera needed); returns once the workflow has left VIAL, else throws. */
    suspend fun captureVialForTour() {
        if (!FtueController.state.isActive) return
        check(isDemoTxn(preferenceHelper.getTxnId())) { "Tour vial capture on a non-demo txn" }
        confirmDoneStarted = false
        if (_currentStep.value == StepState.VIAL && !captureCommitted) {
            _capturedBitmap.value = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
            saveCaptureImage()
        }
        // VIAL stays the current step until the note is answered (notes on) or the txn is
        // confirmed (notes off), so either of those is as good as leaving VIAL.
        withTimeout(TourActionTimeoutMs) {
            while (_currentStep.value == StepState.VIAL && !_uiState.value.showNotesDialog && !confirmDoneStarted) delay(50)
        }
    }

    fun redoCaptureImage() {
        _capturedBitmap.value = null
        captureCommitted = false
    }

    fun saveCaptureImage() {
        _capturedBitmap.value?.let { processCapturedImage(it) }
    }

    /**
     * Advance past VIAL, or offer to skip the back count.
     *
     * CONTAINER_PENDING re-counts what is left in the stock bottle. When the bottle was
     * poured out and fully dispensed there is nothing left, so the step is offered as a
     * skip instead of being walked into with a target of 0. The CONTAINER_INITIATE total
     * is read from the DAO because uiState only ever holds the current step's rows.
     */
    private fun advanceFromVial() {
        val steps = _steps.value
        val next = steps.getOrNull(steps.indexOf(StepState.VIAL) + 1)
        if (next != StepState.CONTAINER_PENDING) {
            moveNextStep()
            return
        }
        viewModelScope.launch {
            try {
                val poured = dispenseCountDao
                    .observeAllForTxn(preferenceHelper.getTxnId(), StepState.CONTAINER_INITIATE)
                    .first().sumOf { it.pillCount ?: 0 }
                if (poured == (_txnInfo.value?.targetCount ?: 0)) {
                    if (FtueController.state.isActive) {
                        // Tutorial: this dialog is real business UI the tour doesn't
                        // gate/explain — an extra manual tap here interrupts the guided
                        // flow for no reason, since skipping the back-count step is
                        // already the only sensible outcome (there's nothing left to
                        // scan). Take it automatically instead, same as tapping SKIP
                        // would, and let the tour continue uninterrupted.
                        skipBackCount()
                    } else {
                        // No camera frames arrive while the dialog sits on the vial still,
                        // so the watchdog would fire and drop the idle overlay behind it.
                        pauseIdleTimer()
                        _uiState.update { it.copy(showSkipStepDialog = true) }
                    }
                } else {
                    moveNextStep()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to advance from VIAL step", e, event = LogEvent.VIAL_SCAN_FAILED)
            }
        }
    }

    /** Single-button "Skip": finish the txn without entering CONTAINER_PENDING. */
    fun skipBackCount() {
        _uiState.update { it.copy(showSkipStepDialog = false) }
        handleDone()
    }

    private fun processCapturedImage(bitmap: Bitmap) {
        // Guard only the photo write. handleDone has exits that leave the user on VIAL
        // (notes dialog dismissed, total 0), so a Done re-tap must still advance the flow.
        if (!captureCommitted) {
            captureCommitted = true
            onAction(DispenseCountAction.AddVialPhotoInTxn(0, bitmap))
        }
        advanceFromVial()
        // The still is left up here on purpose. Whichever way advanceFromVial goes it
        // clears the bitmap itself — redoCaptureImage on the CONTAINER_PENDING entry,
        // handleConfirmDone on completion — so clearing it here only flashes the live
        // camera underneath.
        engine.resumeLiveCounting()
    }

    fun pausePillDetection() = engine.pauseDetection()

    /** Resume pill detection after a [pausePillDetection] call. */
    fun resumePillDetection() = engine.resumeDetection()

    fun setCameraPaused(paused: Boolean) = engine.setCameraPaused(paused)
}
