package com.rite.pillcounting.core.scanning.presentation.viewmodel

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
import androidx.room.withTransaction
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.insertNewInProgressBatch
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.BottleInfoEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.StockTxnEntity
import com.rite.pillcounting.core.room.models.dtos.TxnWithDetails
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.models.ScheduleCode
import com.rite.pillcounting.core.models.isControlledDrugType
import com.rite.pillcounting.core.security.ImageCrypto
import com.rite.pillcounting.core.utils.common.HelperFunctions.saveBitmapToFile
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.common.OverlayUtils
import com.rite.pillcounting.core.utils.common.SoundUtils
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.logger.PerformanceLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.scanning.logic.PillDetectionModelLoader
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.data.NavigationEvent
import com.rite.pillcounting.core.scanning.domain.data.PillScanningEvent
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.scanning.domain.model.DetectedPill
import com.rite.pillcounting.core.scanning.domain.model.PillScanningUiState
import com.rite.pillcounting.core.scanning.domain.model.TxnDetail
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.scanning.logic.Detection
import com.rite.pillcounting.core.scanning.logic.GloveDetection
import com.rite.pillcounting.core.scanning.logic.PillAnalyzer
import com.rite.pillcounting.core.scanning.logic.TrayColor
import com.rite.pillcounting.core.scanning.logic.TrayDetection
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
    private val stockTxnDao: StockTxnDao,
    private val bottleInfoDao: BottleInfoDao,
    private val batchDao: BatchDao,
    private val userDao: UserDao,
    private val pillCountTxnDetailsDao: PillCountTxnDetailsDao,
    private val locationProvider: LocationProvider,
    private val drugMasterDao: DrugMasterDao,
    private val modelLoader: PillDetectionModelLoader,
    private val performanceLogger: PerformanceLogger,
    private val barcodeDecoder: BarcodeDecoder,
    private val drugRepository: IDrugRepository,
    private val drugImageDownloader: DrugImageDownloader,
    private val hl7Repository: Hl7Repository,
    private val appDatabase: AppDatabase,
) : AndroidViewModel(app) {

    private val logger = AppLogger("PillScanningVM")
    val context: Context = getApplication<Application>().applicationContext
    private var currentFrameBitmap: Bitmap? = null
    private var lastTransformationMatrix: Matrix? = null
    @Volatile private var isAnalyzingFrame = false
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

    private var stockCountBatchId = 0L

    // --- Stock-count (normalized) session state ---
    // A stock loose-count session writes NOTHING to pill_count_txn / pill_count_txn_details.
    // ADDs are held in the in-memory staging buffer for the whole session and, on Done, the
    // aggregate is written as looseQty onto the active BottleInfo line ([stockBottleId]).
    private var isStockCountSession = false
    private var stockBottleId = 0L
    // StockTxn header resolved on the compulsory NDC scan. For the "Scan Pills" (loose) flow the
    // BottleInfo row is not created up front — Done inserts one fresh row per counting session
    // ([flushStagedDetails]), so this header id (not a bottle id) is what Done needs.
    private var stockTxnId = 0L
    // Lot/expiry decoded on the compulsory NDC scan, remembered so Done can stamp the new line.
    private var stockLotNo: String? = null
    private var stockExpNo: String? = null
    // Deferred DispenseFlow stock session: remembered so [flushStagedDetails] can create
    // the batch + stock_txn + bottle_info atomically at Done. Zero/null in the pre-existing
    // hand-off paths (stockTxnId != 0L), where the rows already exist.
    private var stockDrugId: Long = 0L
    private var stockBucketId: String? = null
    // One-shot: batchId minted at Done for a deferred DispenseFlow stock session, so the
    // screen can publish it to InventoryScan for Recent Counts adoption. Cleared via
    // [consumeStockCountCommittedBatchId] after the screen forwards it.
    private val _stockCountCommittedBatchId = MutableStateFlow<Long?>(null)
    val stockCountCommittedBatchId: StateFlow<Long?> = _stockCountCommittedBatchId.asStateFlow()
    fun consumeStockCountCommittedBatchId() { _stockCountCommittedBatchId.value = null }

    // --- Dispense-flow bottle tracking (rescan-same-NDC during counting) ---
    // No in-memory cache of the bottle list: every scan re-reads
    // PillCountTxnEntity.bottleInfoListJson fresh from Room, so a resumed/killed-and-restarted
    // process always compares against the real persisted state. isProcessingBottleScan only
    // guards against the same physical scan firing this handler multiple times in a row
    // (camera frame analyzer re-detecting the same barcode across consecutive frames) while a
    // scan is already being resolved or its confirm dialog is awaiting a user tap.
    @Volatile private var isProcessingBottleScan = false
    private var pendingBottleScan: BottleInfo? = null

    /**
     * Enter a stock loose-count session from the merged DispenseFlow. All three
     * of `bottleId` / `stockTxnId` / `batchId` may be `0L`, in which case this is
     * a fully deferred session: no batch, stock_txn or bottle_info row exists
     * yet, and [flushStagedDetails] will create them atomically at All Done
     * using [stockDrugId] + [stockBucketId]. Back-out before Done leaves zero
     * DB rows. Loads the drug, then sets up the synthetic txn info and lands on
     * the pill-count step.
     */
    fun enterStockCountSession(
        bottleId: Long,
        stockTxnId: Long,
        batchId: Long,
        drugId: Long,
        bucketId: String? = null,
    ) {
        isStockCountSession = true
        stockBottleId = bottleId
        stockCountBatchId = batchId
        this.stockTxnId = stockTxnId
        stockDrugId = drugId
        stockBucketId = bucketId
        // DispenseFlow doesn't decode lot/exp — keep the private fields null so
        // flushStagedDetails' fresh insert leaves those columns null (matches the
        // `(stockTxn, null, null)` keying documented on the stock-line contract).
        stockLotNo = null
        stockExpNo = null
        isPaused = false
        viewModelScope.launch {
            val drug = drugMasterDao.getDrugById(drugId) ?: return@launch
            startStockCounting(drug, stockTxnId)
        }
    }

    private val shutterSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
    }

    private val _capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedBitmap: StateFlow<Bitmap?> = _capturedBitmap

    private val _showFlash = MutableStateFlow(false)
    val showFlash: StateFlow<Boolean> = _showFlash

    // True from an accepted capture request until its bitmap or error lands.
    // capturedBitmap is too late to gate the shutter — it only appears when the
    // capture is already done, which is the window rapid taps slipped through.
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    // Done stays enabled after a commit when VIAL is the last step (the still is
    // kept under the confirm dialog), so this is what stops a second tap from
    // writing the vial photo twice. Cleared whenever the still is discarded.
    private var captureCommitted = false

    private val _isTxnFromHl7 = MutableStateFlow(false)
    val isTxnFromHl7: StateFlow<Boolean> = _isTxnFromHl7

    private val _txnInfo = MutableStateFlow<TxnWithDetails?>(null)
    val txnInfo: StateFlow<TxnWithDetails?> = _txnInfo

    // ── Glove detection control: stop running glove model once gloves are detected ──
    private val _glovesDetected = MutableStateFlow(false)
    val glovesDetected: StateFlow<Boolean> = _glovesDetected.asStateFlow()

    var shouldRunGloveDetection = true

    // Consecutive-frame counter guarding against single-frame flicker between
    // "gloves" and "no_gloves" classes — require several consecutive strong
    // detections before locking the session state, so one flickery frame
    // doesn't permanently commit "gloves detected".
    private var consecutiveGloveFrames = 0

    // Tray color detection: always enabled during COUNTING stage.
    // @Volatile ensures main-thread write is visible to Dispatchers.Default immediately.
    @Volatile var isTrayColorDetectionEnabled = false
    // true when drug is hazardous AND global "Hazardous Drug" setting is ON → show popup.
    private var hazardousTrayPopupEnabled = false
    // Prevents saving hazardousTrayDetected to DB more than once per transaction.
    private var hazardousTrayResultSaved = false
    // Remembered so resetIdleOverlay() can restore detection without re-calling setHazardousTransaction.
    private var lastHazardousByDrug = false
    // Tracks colors already prompted this session to avoid repeated popups per color.
    private val promptedTrayColors = mutableSetOf<TrayColor>()
    // In-memory cache of the single saved hazardous tray color (null if none saved yet).
    private var cachedHazardousColor: String? = null
    // Whether the current transaction is for a hazardous drug.
    private var isHazardousTxn = false
    // Prevents the "using hazardous tray" toast from firing on every frame for non-hazardous txns.
    private var hazardousTrayToastShown = false

    private var lastPreviewWidth = 0
    private var lastPreviewHeight = 0

    // ── NEW: expose tray detections so the UI can draw the tray boundary ──────
    private val _trayDetections = MutableStateFlow<List<TrayDetection>>(emptyList())
    val trayDetections: StateFlow<List<TrayDetection>> = _trayDetections.asStateFlow()

    private var observeTxnDetailsJob: Job? = null
    private var stockCountBaseTotal: Int = -1

    // ── Staging buffer for the legacy loose-pill counting session ────────────
    // ADDs during a counting session are held here (NOT inserted) and only
    // flushed to the DB when the user confirms Done. On back-out they are
    // discarded. Earlier committed rows (sealed bottles + previously-Done loose
    // pills) live in the DB and are never touched by staging.
    // Each staged entity has txnDetailsId = 0 (not yet persisted); we assign a
    // temporary negative id to the derived TxnDetail UI rows for delete-matching.
    private val stagedDetails = mutableListOf<PillCountTxnDetailsEntity>()
    private var stagingActive = false

    // Staging (defer-to-Done + discard-on-back-out) is ONLY for the inventory
    // SCAN PILLS hand-off (stock loose counting). The regular dispense flow must
    // persist each ADD immediately so a session backed out before Done is still
    // saved and shows up under Pending Items. A stock session stages for its whole
    // duration — nothing is written to pill_count_txn / pill_count_txn_details;
    // the aggregate lands on BottleInfo.looseQty on Done.
    private val stagingEnabled: Boolean
        get() = isStockCountSession

    private val _isSoundOverride = MutableStateFlow(preferenceHelper.isSoundOverride())
    val isSoundEnabled: StateFlow<Boolean> = _isSoundOverride.asStateFlow()

    // Performance monitoring
    private var performanceMonitorJob: Job? = null
    private val performanceSnapshotInterval = 10_000L // 10 seconds

    companion object {
        private const val ZERO_DETECTIONS_THRESHOLD = 25

        /**
         * How long Add stays disabled after a tap. The Add handler captures the
         * current camera frame, draws the detection overlay, and writes a JPEG to
         * disk on a background thread. This debounce guards that window so a second
         * Add can't fire before the frame + detections have refreshed and the save
         * has run — otherwise we'd persist a stale/duplicate image for the next
         * transaction detail. Kept short so the UI stays responsive.
         */
        private const val ADD_COOLDOWN_MS = 1000L

        /**
         * Consecutive frames of a strong "gloves" detection required before the
         * gate locks in. At ~4 fps glove inference, 3 frames is a brief ~750ms
         * hold — enough to reject a single flickery misclassification without
         * feeling slow to the user.
         */
        private const val GLOVE_CONFIRM_FRAMES = 3
    }

    /** Model initialization states */
    sealed class ModelState {
        object Idle : ModelState()
        object Loading : ModelState()
        data class Ready(val analyzer: PillAnalyzer) : ModelState()
        data class Error(val message: String, val cause: Throwable? = null) : ModelState()
    }

    init {
        resetIdleTimer()
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
                    // While a counting session is staging in memory, the DB observer
                    // must NOT overwrite uiState — staging owns the running total and
                    // history. refreshStagedHistory() keeps uiState populated instead.
                    if (stagingActive) return@collectLatest
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
                    val isStockCount = _txnInfo.value?.isDispense == false
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
     * Refresh uiState (history + running total) from the in-memory staging
     * buffer for the given [step]. REPLACES the DB observer while a session is
     * staging. Since staging starts empty, the running total is the staged sum
     * directly — prior committed pills are intentionally NOT shown during the
     * session ("only this session" requirement).
     *
     * Staged rows are not persisted yet, so they have no real PK. We assign a
     * temporary NEGATIVE id (-(index+1)) so the history/delete UI has a stable
     * key that can never collide with a real (positive) autogen PK.
     */
    private fun refreshStagedHistory(step: StepState) {
        val staged = stagedDetails.filter { it.type == step.toString() }
        val history = staged.mapIndexed { index, entity ->
            TxnDetail(
                txnDetailId = -(index + 1).toLong(),
                count = entity.pillCount ?: 0,
                image = entity.imagePath,
                createdAt = entity.createdAt,
                type = step
            )
        }
        val sessionTotal = history.sumOf { it.count }
        _uiState.update { state ->
            state.copy(txnDetailHistory = history, stockCountSessionTotal = sessionTotal)
        }
    }

    /**
     * Flush all staged details to the DB. Inserts each staged row and, for
     * REGULAR transactions, increments looseQty ONCE by the total staged sum.
     * Clears the buffer and ends the staging session. Must be called on a
     * coroutine. Earlier committed rows are untouched.
     *
     * Returns true on success, false when the deferred-session atomic insert
     * throws (rolled back by withTransaction). On false the staging buffer is
     * left intact so the user can retap Done without losing their count, and
     * [showErrorMessage] is surfaced for the toast handler.
     */
    private suspend fun flushStagedDetails(txnId: Long): Boolean {
        if (stagedDetails.isEmpty()) {
            stagingActive = false
            return true
        }
        val stagedSum = stagedDetails.sumOf { it.pillCount ?: 0 }
        if (isStockCountSession) {
            // Stock loose counting: the per-count detail rows/images are session-only —
            // only the aggregate loose total is persisted onto a BottleInfo line. For
            // controlled substances (DEA CII–CVI) the captured pill-image paths from
            // this session are also persisted; non-controlled sessions leave them null
            // so the DB is not bloated with paths we never need.
            if (stagedSum > 0) {
                // Derive from _txnInfo (populated by startStockCounting) rather than a
                // cached flag — one source of truth, no drift risk if a future entry path
                // forgets to set the flag.
                val isControlled = isControlledDrugType(_txnInfo.value?.drugType)
                val controlledPaths: List<String>? = if (isControlled) {
                    stagedDetails.mapNotNull { it.imagePath }.takeIf { it.isNotEmpty() }
                } else null
                if (stockBottleId != 0L) {
                    // DispenseFlow hand-off ([enterStockCountSession]) with a pre-existing bottle:
                    // accumulate this session's loose total onto it. Single atomic UPDATE so a
                    // process death mid-flush can't save the count while dropping the image paths.
                    bottleInfoDao.incrementLooseQtyAndImages(stockBottleId, stagedSum, controlledPaths)
                    logger.i("Flushed stock loose count onto existing line. stagedSum=$stagedSum bottleId=$stockBottleId controlledPaths=${controlledPaths?.size ?: 0}")
                } else if (stockTxnId != 0L) {
                    // "Scan Pills" loose flow: every counting session is its own line so the same
                    // NDC keeps separate loose entries rather than merging onto one line. This line
                    // holds only loose/open pills, so bottleQty stays 0 — it must NOT count as a
                    // sealed bottle.
                    val newBottleId = bottleInfoDao.insert(
                        BottleInfoEntity(
                            stockTxnId = stockTxnId,
                            batchId = stockCountBatchId,
                            lotNo = stockLotNo,
                            expNo = stockExpNo,
                            bottleQty = 0,
                            looseQty = stagedSum,
                            controlledImagePaths = controlledPaths,
                        )
                    )
                    logger.i("Flushed stock loose count as new line. stagedSum=$stagedSum stockTxnId=$stockTxnId bottleId=$newBottleId controlledPaths=${controlledPaths?.size ?: 0}")
                } else if (stockDrugId != 0L) {
                    // Deferred DispenseFlow stock session. Two entry paths land here:
                    //  1. Fresh stock count (no batch context) — [stockCountBatchId] == 0L.
                    //     Mint batch + stock_txn + bottle_info atomically.
                    //  2. Continuing an existing batch from the "Scan Pills" hand-off —
                    //     [stockCountBatchId] != 0L (seeded via [enterStockCountSession]).
                    //     Reuse the existing batchId; if the drug already has a stock_txn
                    //     header under that batch, append a new bottle line onto it —
                    //     otherwise insert a fresh stock_txn under the same batch. This
                    //     prevents every counting session from spawning a duplicate batch.
                    //
                    // Wrapped in try/catch because a DB failure here (unique constraint,
                    // disk full, foreign-key violation) would otherwise crash the app on
                    // All Done. On failure the transaction rolls back (withTransaction is
                    // atomic), the staging buffer is kept intact so the user can retap
                    // Done without losing the count, and a toast surfaces the failure.
                    try {
                        // withTransaction returns the freshly-committed ids so the field
                        // mirrors + published event only run on success. Publishing inside
                        // the block would leak a batchId to InventoryScan that the rollback
                        // then wiped from the DB, leaving Recent Counts bound to a phantom.
                        val committed = appDatabase.withTransaction {
                            val effectiveBatchId = if (stockCountBatchId != 0L) {
                                stockCountBatchId
                            } else {
                                batchDao.insertNewInProgressBatch(bucketId = stockBucketId)
                            }
                            val existingStockTxn = stockTxnDao.findByDrugInBatch(effectiveBatchId, stockDrugId)
                            val effectiveStockTxnId = existingStockTxn?.txnId
                                ?: stockTxnDao.upsertPreservingId(
                                    StockTxnEntity(
                                        drugId = stockDrugId,
                                        status = CountStatus.PARTIAL,
                                        batchId = effectiveBatchId,
                                        bucketId = stockBucketId,
                                    )
                                )
                            bottleInfoDao.insert(
                                BottleInfoEntity(
                                    stockTxnId = effectiveStockTxnId,
                                    batchId = effectiveBatchId,
                                    lotNo = stockLotNo,
                                    expNo = stockExpNo,
                                    bottleQty = 0,
                                    looseQty = stagedSum,
                                    controlledImagePaths = controlledPaths,
                                )
                            )
                            stockTxnDao.refreshBatchTotalNdcs(effectiveBatchId)
                            stockTxnDao.updateBatchUserName(
                                effectiveBatchId,
                                preferenceHelper.getLoggedInEmail() ?: preferenceHelper.getUserId()
                            )
                            effectiveBatchId to effectiveStockTxnId
                        }
                        // Transaction committed — mirror ids into VM fields so subsequent
                        // flushes in this same session accumulate onto the same line/header,
                        // and publish the batchId so the screen can adopt it into
                        // InventoryScan's Recent Counts.
                        stockCountBatchId = committed.first
                        stockTxnId = committed.second
                        _stockCountCommittedBatchId.value = committed.first
                        logger.i("Committed deferred stock session. stagedSum=$stagedSum drugId=$stockDrugId batchId=$stockCountBatchId stockTxnId=$stockTxnId controlledPaths=${controlledPaths?.size ?: 0}")
                    } catch (e: Exception) {
                        logger.e("Deferred stock session commit failed — staging preserved for retry", e)
                        _uiState.update {
                            it.copy(showErrorMessage = context.getString(R.string.batch_stock_count_save_failed))
                        }
                        return false
                    }
                }
                // Non-controlled stock sessions never persist image paths onto
                // BottleInfo, so the on-disk JPEGs written during Add taps have no
                // DB row referencing them post-commit. Delete them here so they
                // don't accumulate as orphans. Controlled sessions keep the files —
                // BottleInfo.controlledImagePaths still points at them.
                if (!isControlled) {
                    deleteStagedImageFiles()
                }
            }
        } else {
            // Regular dispense flow: each staged detail hits pill_count_txn_details, plus a
            // second write to bump the parent txn's bottle-info list (linkDetailToActiveBottle
            // decodes, appends the new detail id to the active bottle, then re-encodes and
            // persists). A process death between those two writes previously left a detail
            // row unreferenced by any bottle. withTransaction wraps the whole flush so either
            // all details + their linkages commit or nothing does.
            appDatabase.withTransaction {
                val txn = pillCountTxnDao.getById(txnId)
                val bottles = BottleInfoJson.decode(txn?.bottleInfoListJson)
                stagedDetails.forEach { entity ->
                    val newDetailsId = pillCountTxnDetailsDao.insert(entity.copy(txnId = txnId))
                    linkDetailToActiveBottle(txnId, bottles, newDetailsId)
                }
            }
            logger.i("Flushed ${stagedDetails.size} staged details to DB. stagedSum=$stagedSum txnId=$txnId")
        }
        stagedDetails.clear()
        stagingActive = false
        return true
    }

    /**
     * Appends [newDetailsId] to the currently active (last) bottle's [BottleInfo.txnDetailsIds]
     * and persists it. No-op if the txn has no bottle entries yet (no bottle scanned before
     * this pill was counted — legacy/no-GS1-scan txns). [bottles] is the list resolved once by
     * the caller (avoids a redundant decode per staged row when flushing several at once);
     * the write itself re-reads+re-writes fresh each call so concurrent appends don't clobber
     * each other.
     */
    private suspend fun linkDetailToActiveBottle(txnId: Long, bottles: List<BottleInfo>, newDetailsId: Long) {
        if (bottles.isEmpty()) return
        val txn = pillCountTxnDao.getById(txnId) ?: return
        val current = BottleInfoJson.decode(txn.bottleInfoListJson).toMutableList()
        if (current.isEmpty()) return
        val lastIndex = current.lastIndex
        current[lastIndex] = current[lastIndex].copy(
            txnDetailsIds = current[lastIndex].txnDetailsIds + newDetailsId
        )
        pillCountTxnDao.updateBottleInfoList(txnId, BottleInfoJson.encode(current))
    }

    /**
     * Discard the in-memory staged count on back-out (no Done). Earlier
     * committed rows and looseQty are preserved — only this session's staged
     * (un-inserted) data is dropped. On a stock-count session the JPEGs written
     * during Add taps are ALSO deleted here so an abandoned session leaves no
     * orphan files on disk (previously these piled up indefinitely under
     * `transaction_details/` since no DB row ever referenced them).
     */
    fun discardStagedCount() {
        // Only the SCAN PILLS hand-off stages and discards on back-out. In the
        // regular dispense flow each ADD is already persisted, so there is
        // nothing to discard — leave the DB rows (and the DB-driven uiState)
        // intact so the session is saved and resumable under Pending Items.
        if (!stagingEnabled) {
            stockCountBaseTotal = -1
            return
        }
        if (stagedDetails.isNotEmpty()) {
            logger.i("Discarding ${stagedDetails.size} staged details (back-out, no Done).")
            deleteStagedImageFiles()
        }
        stagedDetails.clear()
        stagingActive = false
        stockCountBaseTotal = -1
        _uiState.update { it.copy(txnDetailHistory = emptyList(), stockCountSessionTotal = 0) }
    }

    /**
     * Delete on-disk JPEG files whose paths are in [stagedDetails]. Called from
     * [discardStagedCount] (back-out) and from the non-controlled branch of
     * [flushStagedDetails] on commit success — non-controlled stock sessions do
     * not persist image paths onto BottleInfo, so nothing else references these
     * files after commit. Controlled sessions keep the files because
     * BottleInfo.controlledImagePaths still points at them.
     */
    private fun deleteStagedImageFiles() {
        stagedDetails.forEach { detail ->
            val path = detail.imagePath ?: return@forEach
            try {
                val file = java.io.File(path)
                if (file.exists() && !file.delete()) {
                    logger.w("Failed to delete staged image file: $path")
                }
            } catch (e: Exception) {
                logger.e("Error deleting staged image file: $path", e)
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
        lastPreviewWidth = viewWidth
        lastPreviewHeight = viewHeight

        if (_modelState.value is ModelState.Ready) {
            logger.w("Interpreter already initialized, skipping reinitialization.")
            return
        }

        viewModelScope.launch {
            _modelState.value = ModelState.Loading

            try {
                // Load pill + tray only; glove is deferred until a hazardous drug is confirmed.
                val models = modelLoader.getOrLoadInterpreters(includeGlove = false)

                val analyzer = PillAnalyzer(
                    pillInterpreter  = models.pillInterpreter,
                    traySegDetector = models.traySegDetector,
                    gloveInterpreter = models.gloveInterpreter,
                    performanceLogger = performanceLogger,
                    shouldRunGloveDetection = { shouldRunGloveDetection },
                    shouldDetectTrayColor = { isTrayColorDetectionEnabled },
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
                logger.i("Pill + tray interpreters initialized (glove deferred).")

            } catch (e: Exception) {
                logger.e("Interpreter init failed", e)
                _modelState.value = ModelState.Error("Interpreter initialization failed", e)
            }
        }
    }

    /**
     * Loads the glove detection model and rebuilds [PillAnalyzer] to include it.
     * Called only when the confirmed drug is hazardous and the COUNTING stage begins.
     * Starts a 20-second timeout: if gloves are not confirmed by then, the model is
     * unloaded automatically to free memory.
     * No-op if glove is already loaded.
     */
    fun loadGloveModelAndRebuildAnalyzer() {
        if ((_modelState.value as? ModelState.Ready)?.analyzer?.hasGloveInterpreter == true) {
            logger.i("Glove model already loaded — skipping rebuild")
            return
        }

        viewModelScope.launch {
            try {
                val models = modelLoader.getOrLoadInterpreters(includeGlove = true)
                val w = lastPreviewWidth
                val h = lastPreviewHeight

                val analyzer = PillAnalyzer(
                    pillInterpreter  = models.pillInterpreter,
                    traySegDetector = models.traySegDetector,
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
                            previewWidth  = w,
                            previewHeight = h,
                            imageWidth    = imageWidth,
                            imageHeight   = imageHeight
                        )
                    }
                )

                _modelState.value = ModelState.Ready(analyzer)
                logger.i("Glove model loaded — analyzer rebuilt for hazardous drug.")
            } catch (e: Exception) {
                logger.e("Glove model load failed", e)
            }
        }
    }

    /**
     * Closes the glove TFLite interpreter and rebuilds [PillAnalyzer] without it.
     * Pill + tray interpreters remain in the loader cache so detection continues uninterrupted.
     */
    private suspend fun unloadGloveAndRebuildAnalyzer() {
        modelLoader.unloadGloveModel()
        val models = modelLoader.getOrLoadInterpreters(includeGlove = false)
        val w = lastPreviewWidth
        val h = lastPreviewHeight
        val analyzer = PillAnalyzer(
            pillInterpreter  = models.pillInterpreter,
            traySegDetector = models.traySegDetector,
            gloveInterpreter = null,
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
                    previewWidth  = w,
                    previewHeight = h,
                    imageWidth    = imageWidth,
                    imageHeight   = imageHeight
                )
            }
        )
        _modelState.value = ModelState.Ready(analyzer)
        logger.i("Analyzer rebuilt without glove model")
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

        // ── Tray color classification + hazardousTrayDetected DB save ────────
        // Runs only for hazardous drug transactions. Saves exactly once per txn.
        //
        // Scenario 1 (setting OFF): immediate save — true if in hazardous list, false otherwise.
        // Scenario 2 (setting ON):  true if in hazardous list (immediate), otherwise show popup;
        //                           user response (yes/no) determines the saved value.
        logger.d(
            "Frame: detectionEnabled=$isTrayColorDetectionEnabled popupEnabled=$hazardousTrayPopupEnabled " +
            "resultSaved=$hazardousTrayResultSaved trays=${trayDets.size} " +
            "pending=${_uiState.value.pendingTrayColorForClassification?.label}"
        )
        if (isTrayColorDetectionEnabled) {
            val trayColor = trayDets.firstOrNull { it.trayColor != TrayColor.UNKNOWN }?.trayColor
            logger.d("  First known tray color: ${trayColor?.label ?: "none"} hazardousColor=$cachedHazardousColor isHazardousTxn=$isHazardousTxn")

            if (trayColor != null) {
                if (isHazardousTxn && !hazardousTrayResultSaved) {
                    when {
                        cachedHazardousColor == null -> {
                            // No hazardous color saved yet — first hazardous transaction.
                            // Show prompt so the user can designate this tray (setting must be ON).
                            if (hazardousTrayPopupEnabled &&
                                _uiState.value.pendingTrayColorForClassification == null &&
                                trayColor !in promptedTrayColors) {
                                promptedTrayColors.add(trayColor)
                                _uiState.update { it.copy(pendingTrayColorForClassification = trayColor) }
                                logger.i("[HAZARDOUS] First hazardous txn — showing tray classification popup for ${trayColor.label}")
                            } else if (!hazardousTrayPopupEnabled) {
                                // Global setting OFF — nothing to classify, save false.
                                logger.i("[HAZARDOUS] No saved color, setting OFF — saving false for ${trayColor.label}")
                                saveHazardousTrayDetected(false)
                            }
                        }
                        trayColor.name == cachedHazardousColor -> {
                            // Correct hazardous tray detected — save true.
                            logger.i("[HAZARDOUS] Tray ${trayColor.label} matches hazardous color — saving true")
                            saveHazardousTrayDetected(true)
                        }
                        else -> {
                            // A hazardous color is saved but this tray does not match it.
                            if (!hazardousTrayPopupEnabled) {
                                // Global setting OFF — save false silently.
                                logger.i("[HAZARDOUS] Setting OFF, tray ${trayColor.label} != hazardous color — saving false")
                                saveHazardousTrayDetected(false)
                            } else if (!hazardousTrayToastShown) {
                                // Setting ON — warn user to swap to the correct hazardous tray.
                                hazardousTrayToastShown = true
                                logger.i("[HAZARDOUS] Tray ${trayColor.label} is NOT the hazardous tray — showing warning toast")
                                viewModelScope.launch(Dispatchers.Main) {
                                    showToast(context, context.getString(R.string.non_hazardous_tray_warning))
                                }
                            }
                        }
                    }
                } else if (!isHazardousTxn && !hazardousTrayToastShown && trayColor.name == cachedHazardousColor) {
                    // Non-hazardous transaction using the hazardous tray — warn the user.
                    hazardousTrayToastShown = true
                    logger.i("[HAZARDOUS] Hazardous tray ${trayColor.label} detected in non-hazardous transaction — showing toast")
                    viewModelScope.launch(Dispatchers.Main) {
                        showToast(context, context.getString(R.string.hazardous_tray_warning))
                    }
                }
            }
        }

        // ── Check if gloves detected - if yes, stop running glove detection ──
        // Require several CONSECUTIVE strong detections before locking the session
        // state — a single flickery frame (e.g. a bare hand momentarily misclassified
        // as "gloves") must not permanently commit "gloves detected".
        if (!_glovesDetected.value) {
            val strongGloveThisFrame = gloveDets.any { it.classId == 0 && it.confidence >= 0.75f }
            consecutiveGloveFrames = if (strongGloveThisFrame) consecutiveGloveFrames + 1 else 0

            if (consecutiveGloveFrames >= GLOVE_CONFIRM_FRAMES) {
                _glovesDetected.value = true
                shouldRunGloveDetection = false
                val best = gloveDets.filter { it.classId == 0 }.maxByOrNull { it.confidence }
                logger.i("GLOVE_ICON — turning GREEN (bestConfidence=${best?.confidence}, consecutiveFrames=$consecutiveGloveFrames) - unloading glove model and saving DB flag")
                viewModelScope.launch {
                    unloadGloveAndRebuildAnalyzer()
                    val txnId = preferenceHelper.getTxnId()
                    if (txnId != 0L) {
                        pillCountTxnDao.updateGlovesPresent(txnId, true)
                        logger.i("isGlovesPresent saved for txn=$txnId")
                    }
                }
            }
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
        _uiState.update { it.copy(showIdleOverlay = true, gloveDetections = emptyList(), pendingTrayColorForClassification = null) }
        _trayDetections.value = emptyList()
        isTrayColorDetectionEnabled = false
        hazardousTrayPopupEnabled = false
        promptedTrayColors.clear()
        isHazardousTxn = false
        hazardousTrayToastShown = false
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
        _uiState.update { it.copy(showIdleOverlay = false, pendingTrayColorForClassification = null) }

        _lastTenDetections.value.clear()
        lastDetectedSnapshot = emptyList()
        lastChangeTimestamp = System.currentTimeMillis()
        lastAddedScanSignature = null
        _trayDetections.value = emptyList()
        _uiState.update { it.copy(gloveDetections = emptyList()) }

        // Restore detection flags using the drug flag remembered from setHazardousTransaction().
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
     * Called when the COUNTING stage begins. Enables tray color detection and caches
     * the current hazardous/non-hazardous color lists from preferences.
     */
    /**
     * Called when COUNTING stage begins.
     *
     * Scenario 1 — setting OFF + drug hazardous:
     *   Detection runs, no popup. Result saved immediately based on hazardous-list lookup.
     * Scenario 2 — setting ON + drug hazardous:
     *   Detection runs, popup shown if color not in hazardous list. Result saved on user response.
     * If drug is NOT hazardous: nothing runs, no DB write.
     */
    fun setHazardousTransaction(isHazardousByDrug: Boolean) {
        val globalSettingOn = preferenceHelper.isHazardousDrugEnabled()
        lastHazardousByDrug = isHazardousByDrug
        isHazardousTxn = isHazardousByDrug
        isTrayColorDetectionEnabled = true
        hazardousTrayPopupEnabled = isHazardousByDrug && globalSettingOn
        hazardousTrayResultSaved = false
        hazardousTrayToastShown = false
        cachedHazardousColor = preferenceHelper.getHazardousTrayColor()

        logger.d("=== setHazardousTransaction ===")
        logger.d("  drugFlag=$isHazardousByDrug  globalSettingOn=$globalSettingOn")
        logger.d("  detectionEnabled=$isTrayColorDetectionEnabled  popupEnabled=$hazardousTrayPopupEnabled  hazardousColor=$cachedHazardousColor")

        if (isHazardousByDrug) {
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
    fun classifyTrayColor(color: TrayColor, isHazardous: Boolean) {
        if (isHazardous) {
            preferenceHelper.setHazardousTrayColor(color.name)
            cachedHazardousColor = color.name
            logger.i("Tray color ${color.label} saved as hazardous color → saving true to DB")
        } else {
            logger.i("Tray color ${color.label} not classified as hazardous → saving false to DB")
        }
        saveHazardousTrayDetected(isHazardous)
        _uiState.update { it.copy(pendingTrayColorForClassification = null) }
    }

    private fun saveHazardousTrayDetected(detected: Boolean) {
        if (hazardousTrayResultSaved) return
        hazardousTrayResultSaved = true
        viewModelScope.launch(Dispatchers.IO) {
            val txnId = preferenceHelper.getTxnId()
            if (txnId != 0L) {
                pillCountTxnDao.updateHazardousTrayDetected(txnId, detected)
                logger.i("hazardousTrayDetected=$detected saved for txnId=$txnId")
                logger.d("DB updated: hazardousTrayDetected=$detected txnId=$txnId")
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
    fun resetGloveDetection() {
        _glovesDetected.value = false
        shouldRunGloveDetection = true
        consecutiveGloveFrames = 0
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
            is PillScanningEvent.ConfirmAddBottle -> handleConfirmAddBottle()
            is PillScanningEvent.CancelAddBottle -> handleCancelBottleDialog(isAdd = true)
            is PillScanningEvent.ConfirmReplaceBottle -> handleConfirmReplaceBottle()
            is PillScanningEvent.CancelReplaceBottle -> handleCancelBottleDialog(isAdd = false)
        }
    }

    private var addCooldownJob: Job? = null

    /** Disables Add for [ADD_COOLDOWN_MS] while the captured frame is saved. */
    private fun startAddCooldown() {
        addCooldownJob?.cancel()
        _uiState.update { it.copy(isAddCooldown = true) }
        addCooldownJob = viewModelScope.launch {
            delay(ADD_COOLDOWN_MS)
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
            if (workingBitmap == null) {
                logger.e("Bitmap.copy() returned null, skipping save")
                return@launch
            }

            val filteredPills = _uiState.value.filteredPills
            val txnId = preferenceHelper.getTxnId()
            val txn = pillCountTxnDao.getById(txnId)
            val drug = drugMasterDao.getDrugById(txn?.drugId)

            // Resolve the currently active bottle (last one scanned) once — used both to
            // watermark this photo with its lot/exp/serial and to tag the detail row below.
            val bottles = BottleInfoJson.decode(txn?.bottleInfoListJson)
            val activeBottle = bottles.lastOrNull()

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
                        lotNumber = activeBottle?.lotNumber,
                        expirationDate = activeBottle?.expirationDate,
                        serialNumber = activeBottle?.serialNumber,
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

            val detail = PillCountTxnDetailsEntity(
                txnId = txnId,
                pillCount = currentCount,
                imagePath = filePath,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                type = stepType.toString(),
            )

            if (stagingEnabled) {
                // SCAN PILLS hand-off: STAGE in memory instead of writing to the
                // DB. The image file IS saved to disk above (staging on disk is
                // fine); only the DB row + looseQty increment are deferred until
                // the user confirms Done. looseQty for REGULAR is incremented
                // once, on flush, for the whole staged sum (see handleConfirmDone
                // / handleDone flush blocks). On back-out these are discarded.
                // Bottle linkage (txnDetailsIds) is done in flushStagedDetails, once
                // real row ids exist — staged rows have no id yet.
                stagedDetails.add(detail)
                stagingActive = true

                if (!workingBitmap.isRecycled) workingBitmap.recycle()
                refreshStagedHistory(stepType)
                currentFrameBitmap = null

                logger.i("Transaction detail STAGED (in memory). Count=$currentCount, File=$filePath, stagedCount=${stagedDetails.size}")
            } else {
                // Regular dispense flow: persist each ADD immediately so a session
                // backed out before Done is still saved (shows under Pending). The
                // DB observer (observeTxnDetailsForTxn) drives uiState because
                // stagingActive stays false. looseQty for REGULAR is incremented
                // per-ADD here to match the per-row insert.
                val newDetailsId = pillCountTxnDetailsDao.insert(detail)
                linkDetailToActiveBottle(txnId, bottles, newDetailsId)

                if (!workingBitmap.isRecycled) workingBitmap.recycle()
                currentFrameBitmap = null

                logger.i("Transaction detail INSERTED (immediate). Count=$currentCount, File=$filePath")
            }
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
        if (_txnInfo.value?.isDispense == false) {
            _uiState.update {
                it.copy(showEndStockCountDialog = true)
            }
            return
        }

        when (event.stepType) {
            StepState.CONTAINER_INITIATE -> {
                val target = _txnInfo.value?.targetCount ?: return
                if (target <= event.totalCount) {
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
                if (_txnInfo.value?.isDispense == true && _uiState.value.targetCount == _uiState.value.txnDetailHistory.sumOf { it.count }) {
                    moveNextStep()
                } else if (_txnInfo.value?.isDispense == false) {
                    handleDone()
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

    fun clearErrorMessage() {
        _uiState.update { it.copy(showErrorMessage = null) }
    }

    fun handleDismissDialog() {
        _uiState.update { it.copy(showDialogForControl = false) }
        _uiState.update { it.copy(showCountMismatchDialog = false) }
        _uiState.update { it.copy(showEndStockCountDialog = false) }
    }

    private fun handleDone() {
        _uiState.update { it.copy(showCountMismatchDialog = false) }
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            // Staged rows are not in the DB yet, so include the staged sum in the
            // total==0 guard, otherwise a session that only staged pills would be
            // wrongly reported as "no transaction".
            val total = pillCountTxnDetailsDao.getTotalPillCountForTxn(txnId) +
                    stagedDetails.sumOf { it.pillCount ?: 0 }
            if (total == 0) {
                _uiState.update { it.copy(showNoTransaction = true) }
                return@launch
            }
            if (txnInfo.value?.isDispense == false) {
                // Stock: flush the loose count onto the BottleInfo line, complete the stock
                // header, and return to the batch screen. Nothing is written to pill_count_txn.
                // Use the VM's [stockTxnId] field (not _txnInfo.value.txnId): a deferred
                // DispenseFlow session enters with stockTxnId = 0L, so _txnInfo holds 0L
                // even after flushStagedDetails mints the real stock_txn row — reading
                // from _txnInfo here would updateStatus(0L, COMPLETED) which matches no
                // row and leaves the freshly-inserted header stuck on PARTIAL.
                //
                // Bail early if the deferred-session insert failed: the staging buffer is
                // preserved by flushStagedDetails so the user can retap Done, and the
                // failure toast has already been surfaced. Do NOT mark COMPLETED or
                // navigate — there is nothing committed to complete.
                if (!flushStagedDetails(txnId)) return@launch
                if (stockTxnId != 0L) {
                    stockTxnDao.updateStatus(stockTxnId, CountStatus.COMPLETED)
                }
                val batchId = stockCountBatchId
                if (batchId != 0L) {
                    _navigationEvent.send(NavigationEvent.NavigateToBatch(batchId))
                } else {
                    _navigationEvent.send(NavigationEvent.NavigateToDashboard)
                }
                return@launch
            }
            val remainingCount =
                _uiState.value.targetCount - _uiState.value.txnDetailHistory.sumOf { it.count }
            if (!_isTxnFromHl7.value && preferenceHelper.getShowNotesDialogSetting()) {
                _uiState.update { it.copy(showNotesDialog = true) }
            } else if (remainingCount > 0 && _currentStep.value == StepState.CONTAINER_PENDING) {
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
            // Flush staged details to the DB BEFORE reading the total / deciding
            // status, so getTotalPillCountForTxn sees the freshly committed rows
            // plus any prior committed rows = the correct grand total.
            flushStagedDetails(txnId)
            val total = pillCountTxnDetailsDao.getTotalPillCountForTxn(txnId)
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch
            if (total == 0) {
                _uiState.update { it.copy(showConfirmDialog = false) }
                return@launch
            }
            val status =
                if (txn.isDispense && txn.targetCount != null && total < txn.targetCount) CountStatus.PARTIAL else CountStatus.COMPLETED

            if (txn.isComingFromHL7 == true) {
                pillCountTxnDao.markCompletedAndUnsynced(txnId = txnId, status = status)
            } else {
                pillCountTxnDao.updateTxnStatus(txnId, status)
            }

            // Send the dispense to the PMS now, not on the next reconnect. Completion used to
            // only persist the status; the HL7 message went out when the MLLP connection next
            // re-established, so a pharmacist finishing a count on a stable connection saw
            // nothing arrive at the Companion. Failures are fine — the row stays unsynced and
            // the resend-on-connect sweep retries it.
            if (txn.isDispense) {
                hl7Repository.sendDispenseNow(txnId)
            }

            _capturedBitmap.value = null
            _uiState.update { it.copy(showConfirmDialog = false) }
            _navigationEvent.send(NavigationEvent.NavigateToDashboard)
            logger.i("Transaction completed. Status=$status")
        }
    }

    private fun handleCancelDone() {
        // Clear the captured still so the VIAL step returns to the live camera view,
        // allowing the user to capture a new photo or click Done again.
        _capturedBitmap.value = null
        captureCommitted = false
        _uiState.update { it.copy(showConfirmDialog = false) }
        logger.i("Confirm dialog cancelled.")
    }

    private fun handleDeleteTransaction(event: PillScanningEvent.TransactionDetailDeleted) {
        // While staging, mutate the in-memory buffer instead of the DB. Staged
        // rows use a temporary negative id (-(index+1)) assigned in
        // refreshStagedHistory, so a negative id always identifies a staged row.
        if (stagingActive) {
            val step = _currentStep.value
            val staged = stagedDetails.filter { it.type == step.toString() }
            val index = (-event.txnDetailId - 1).toInt()
            if (index in staged.indices) {
                stagedDetails.remove(staged[index])
                logger.i("Staged detail removed at index=$index (id=${event.txnDetailId})")
            } else {
                logger.w("Staged delete ignored: id=${event.txnDetailId} out of range")
            }
            refreshStagedHistory(step)
            return
        }
        viewModelScope.launch {
            pillCountTxnDetailsDao.softDelete(event.txnDetailId)
            logger.i("Transaction detail deleted. Id=${event.txnDetailId}")
        }
    }

    private fun handleDeleteAllTransactionDetails(event: PillScanningEvent.AllTransactionDetailsDeleted) {
        stockCountBaseTotal = -1
        // While staging, clear the in-memory buffer instead of soft-deleting DB
        // rows (which would wrongly remove prior committed counts).
        if (stagingActive) {
            stagedDetails.removeAll { it.type == event.stepType.toString() }
            logger.i("All STAGED details cleared for step=${event.stepType}")
            refreshStagedHistory(event.stepType)
            return
        }
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
                    ndc = txnInfo?.ndc.orEmpty(),
                    strength = txnInfo?.strength.orEmpty(),
                    dosageForm = txnInfo?.dosageForm.orEmpty(),
                    bucket = txnInfo?.bucketId?.takeIf { b -> b.isNotBlank() } ?: "Normal",
                    targetCount = txnInfo?.targetCount ?: 0,
                    showTargetCountDialog = shouldShowDialog,
                    drugImage = txnInfo?.drugImage.orEmpty()
                )
            }
            logger.d("Txn info loaded. Drug=${txnInfo?.drugName}, Target=${txnInfo?.targetCount}")
        }
    }

    fun getDrugInfo(forceStartStep: StepState? = null) {
        viewModelScope.launch {
            val txnInfo = pillCountTxnDao.getTxnWithDetails(preferenceHelper.getTxnId())
            _txnInfo.value = txnInfo
            val isDispense = txnInfo?.isDispense
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
                    isDispense = isDispense
                )

                isComingFromHL7 && drugInfo?.drugType?.let {
                    runCatching { ScheduleCode.valueOf(it) }.getOrNull()
                } !in controlledSchedules -> buildWorkflowSteps(
                    isFromHl7 = true,
                    simpleFlow = true,
                    drugType = drugInfo?.drugType.orEmpty(),
                    isDispense = isDispense
                )

                else -> buildWorkflowSteps(
                    isFromHl7 = false,
                    simpleFlow = true,
                    drugType = drugInfo?.drugType.orEmpty(),
                    isDispense = isDispense
                )
            }

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
                    val latestStep = pillCountTxnDetailsDao.getLatestType(preferenceHelper.getTxnId())
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
        if (_txnInfo.value?.isDispense != true) return
        if (isProcessingBottleScan) return
        isProcessingBottleScan = true

        viewModelScope.launch {
            try {
                val txnId = preferenceHelper.getTxnId()
                val activeNdc = _txnInfo.value?.ndc
                if (activeNdc.isNullOrBlank()) return@launch

                val isGs1 = barcodeDecoder.isGs1Barcode(rawValue)
                val decoded = if (isGs1) barcodeDecoder.decode(rawValue) else null
                val extractedGtin = if (isGs1) decoded?.gtin else barcodeDecoder.toGtin14(rawValue)
                val gtin14 = extractedGtin?.let { barcodeDecoder.toGtin14(it) }

                val scannedDrug = gtin14?.let { drugMasterDao.getDrugByGtin(it) ?: drugMasterDao.getDrugByNdc(it) }
                if (scannedDrug == null || scannedDrug.ndc != activeNdc) {
                    // Not the same drug (or unreadable) — ignore, no popup, no lookup, no DB write.
                    return@launch
                }

                val lotNumber = decoded?.lotNumber
                val expirationDate = decoded?.expirationDate?.format(
                    java.time.format.DateTimeFormatter.ofPattern("MM-dd-yyyy")
                )
                val serialNumber = decoded?.serialNumber

                val txn = pillCountTxnDao.getById(txnId) ?: return@launch
                val bottles = BottleInfoJson.decode(txn.bottleInfoListJson).toMutableList()
                val lastBottle = bottles.lastOrNull()

                if (lastBottle != null && isGs1 &&
                    lastBottle.lotNumber == lotNumber &&
                    lastBottle.expirationDate == expirationDate &&
                    lastBottle.serialNumber == serialNumber
                ) {
                    _uiState.update { it.copy(showErrorMessage = context.getString(R.string.bottle_already_scanned)) }
                    return@launch
                }

                val currentCount = pillCountTxnDetailsDao.getTotalPillCountForTxn(txnId)
                val scannedBottle = BottleInfo(
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
                logger.e("BOTTLE_SCAN onNdcRescannedDuringCount failed", e)
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
            val txnId = preferenceHelper.getTxnId()
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch
            val bottles = BottleInfoJson.decode(txn.bottleInfoListJson).toMutableList()
            bottles.add(pending)
            pillCountTxnDao.updateBottleInfoList(txnId, BottleInfoJson.encode(bottles))
            pendingBottleScan = null
            logger.i("BOTTLE_SCAN added new bottle for txnId=$txnId, total bottles=${bottles.size}")
        }
    }

    private fun handleConfirmReplaceBottle() {
        val pending = pendingBottleScan
        _uiState.update { it.copy(showReplaceBottleDialog = false) }
        if (pending == null) return
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch
            val bottles = BottleInfoJson.decode(txn.bottleInfoListJson).toMutableList()
            if (bottles.isEmpty()) {
                bottles.add(pending)
            } else {
                bottles[bottles.lastIndex] = pending
            }
            pillCountTxnDao.updateBottleInfoList(txnId, BottleInfoJson.encode(bottles))
            pendingBottleScan = null
            logger.i("BOTTLE_SCAN replaced last bottle for txnId=$txnId")
        }
    }

    private fun handleCancelBottleDialog(isAdd: Boolean) {
        pendingBottleScan = null
        _uiState.update {
            if (isAdd) it.copy(showAddBottleDialog = false) else it.copy(showReplaceBottleDialog = false)
        }
    }

    /**
     * After an NDC is scanned on the SCAN step, set up a synthetic [TxnWithDetails] for the
     * stock drug (stock counts have no `pill_count_txn` row) and land on TARGET_VERIFICATION
     * (the pill-count step). The running total is driven by the in-memory staging buffer, so
     * we prime [stagingActive] and refresh the staged history instead of observing the DB.
     */
    private fun startStockCounting(drug: DrugMasterEntity, stockTxnId: Long) {
        _txnInfo.value = TxnWithDetails(
            txnId = stockTxnId,
            drugName = drug.drugName,
            drugId = drug.drugId,
            ndc = drug.ndc,
            targetCount = null,
            note = null,
            createdAt = System.currentTimeMillis(),
            bottleInfoListJson = null,
            totalPillCount = 0,
            isDispense = false,
            drugType = drug.drugType,
            strength = drug.strength,
            dosageForm = drug.dosageForm,
            bucketId = null,
            txnDetails = emptyList(),
            isComingFromHL7 = false,
        )
        _isTxnFromHl7.value = false
        _steps.value = buildWorkflowSteps(
            isFromHl7 = false,
            simpleFlow = true,
            drugType = drug.drugType.orEmpty(),
            isDispense = false,
        )
        // Stock counts have no pill_count_txn row, so showTxnInfo()'s DB lookup
        // (getTxnWithDetails(txnId=0)) would return null and wipe both the synthetic
        // _txnInfo set above and the header drug fields. Populate the header directly
        // from the resolved drug instead, and keep _txnInfo (countType=REGULAR) intact
        // so FinalDone routes to the end-stock-count dialog rather than the (bogus)
        // "pills count should be greater than target count" error.
        _uiState.update {
            it.copy(
                scanType = CountType.REGULAR.name,
                drugName = drug.drugName.orEmpty(),
                ndc = drug.ndc,
                strength = drug.strength.orEmpty(),
                dosageForm = drug.dosageForm.orEmpty(),
                bucket = "Normal",
                targetCount = 0,
            )
        }
        _currentStep.value = StepState.TARGET_VERIFICATION
        // Stock uses the in-memory staging buffer for its running total; prime it so the DB
        // observer never clobbers the session count.
        stagingActive = true
        refreshStagedHistory(StepState.TARGET_VERIFICATION)
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
                // Re-entering VIAL with an existing photo must leave Done usable.
                captureCommitted = false
                logger.i("Loaded existing vial photo from $imagePath")
            } catch (e: Exception) {
                logger.e("Failed to load existing vial photo", e)
            }
        }
    }

    private fun showConfirmDialogAfterDone() {
        // Do NOT clear capturedBitmap here. The confirm dialog is shown on top of
        // the captured still, so the user never sees the live camera underneath.
        // capturedBitmap is cleared in handleCancelDone (returns user to live VIAL
        // camera) and is irrelevant on confirm (navigation destroys the screen).
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
        // first TARGET_VERIFICATION entry instead of "0/30"). Re-entering after
        // BACK looked correct only because discardStagedCount() had already
        // cleared these fields.
        _uiState.update {
            it.copy(
                showDialogForControl = false,
                txnDetailHistory = emptyList(),
                stockCountSessionTotal = 0,
            )
        }
        observeTxnDetailsForTxn(next)

        viewModelScope.launch(Dispatchers.IO) {
            pillCountTxnDao.updateWorkflowStep(preferenceHelper.getTxnId(), next.name)
        }
    }

    fun buildWorkflowSteps(
        isFromHl7: Boolean, simpleFlow: Boolean, drugType: String, isDispense: Boolean?
    ): List<StepState> {

        if (isDispense == false) {
            return listOf(StepState.SCAN, StepState.TARGET_VERIFICATION)
        }

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
     * outright (show the "Confirm Done" dialog) or merely capture the still and
     * wait for the user to tap Done before advancing to the remaining step(s).
     */
    fun isVialLastStep(): Boolean = _steps.value.lastOrNull() == StepState.VIAL

    /**
     * Capture the VIAL still photo.
     *
     * @param autoConfirm when true (auto-capture path, where the vial's RX matched
     *   the active transaction), immediately commit the photo as if the user tapped
     *   "Done" once the bitmap lands — this advances the workflow and surfaces the
     *   "Confirm Done" dialog. When false (manual capture), the still is shown and
     *   the user confirms via the Done button.
     *
     * A second call while a capture is still in flight is a no-op: no sound, no
     * flash, no capture request. See [isCapturing].
     */
    fun captureImage(autoConfirm: Boolean = false) {
        val helper = cameraHelper ?: return
        if (_isCapturing.value) return
        _isCapturing.value = true

        val requested = helper.captureImage(
            onCaptured = { bitmap ->
                _isCapturing.value = false
                // A capture that lands after the user already left VIAL would
                // re-show the still overlay on top of the next step.
                if (_currentStep.value == StepState.VIAL) {
                    _capturedBitmap.value = bitmap
                    if (autoConfirm) saveCaptureImage()
                }
            },
            onCaptureError = { _isCapturing.value = false }
        )
        if (!requested) {
            _isCapturing.value = false
            return
        }

        SoundUtils.playCaptureSound(context)
        viewModelScope.launch {
            _showFlash.value = true
            delay(350)
            _showFlash.value = false
        }
    }

    fun redoCaptureImage() {
        _capturedBitmap.value = null
        captureCommitted = false
    }

    fun saveCaptureImage() {
        if (captureCommitted) return
        _capturedBitmap.value?.let {
            captureCommitted = true
            processCapturedImage(it)
        }
    }

    private fun processCapturedImage(bitmap: Bitmap) {
        onEvent(PillScanningEvent.AddVialPhotoInTxn(0, bitmap))
        moveNextStep()
        isPaused = false
        _cameraPaused.value = false
        _uiState.update { it.copy(showIdleOverlay = false) }
        // Clear the captured still only when the step has actually advanced beyond
        // VIAL (i.e. moveNextStep moved to CONTAINER_PENDING or similar). When the
        // step is still VIAL it means handleDone() was called and its coroutine
        // will finish with showConfirmDialogAfterDone(), which clears capturedBitmap
        // and sets showConfirmDialog in the same synchronous dispatch — preventing
        // CameraPreviewSection from briefly resuming the live camera in the window
        // between the image disappearing and the dialog appearing.
        if (_currentStep.value != StepState.VIAL) {
            _capturedBitmap.value = null
        }
        resetIdleTimer()
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