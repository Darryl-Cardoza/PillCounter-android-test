package com.rite.pillcounting.feature.hl7.data.repository

import android.annotation.SuppressLint
import android.content.Context
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.StockTxnEntity
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.models.isControlledDrugType
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.scanning.data.DrugRepository
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.feature.hl7.core.Hl7MessageSender
import com.rite.pillcounting.feature.hl7.domain.model.MessageType
import com.rite.pillcounting.feature.hl7.notification.Hl7Notifier
import com.rite.pillcounting.feature.hl7.util.HL7Config
import com.rite.pillcounting.feature.hl7.util.Hl7Format
import com.rite.pillcounting.feature.hl7.util.HL7MessageBuilder
import com.rite.pillcounting.feature.hl7.util.isRejectAck
import com.rite.pillcounting.feature.hl7.util.isSuccessAck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.HL7MessageKind
import org.rite.hl7.model.segment.INVSegment
import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.model.segment.ZINSegment
import org.rite.hl7.model.segment.ZNISegment
import org.rite.hl7.model.segment.ZPRSegment
import org.rite.hl7.model.segment.ZUISegment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Hl7Repository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drugMasterDao: DrugMasterDao,
    private val locationProvider: LocationProvider,
    private val txnDao: PillCountTxnDao,
    private val txnDetailsDao: PillCountTxnDetailsDao,
    private val preferenceHelper: PreferenceHelper,
    private val pillCountTxnDao: PillCountTxnDao,
    private val stockTxnDao: StockTxnDao,
    private val bottleInfoDao: BottleInfoDao,
    private val userDao: UserDao,
    private val batchDao: BatchDao,
    private val hl7MessageSender: Hl7MessageSender,
    private val drugRepository: DrugRepository,
    private val notifier: Hl7Notifier,
    private val drugImageDownloader: DrugImageDownloader,
) {

    private val logger = AppLogger.create<Hl7Repository>()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    // Guards resendPendingHl7Transactions() so the Room-flow observer and the
    // onClientConnected trigger can never run concurrently and double-send the
    // same pending txn's HL7 message before either ACK returns.
    private val resendMutex = Mutex()

    // txnIds explicitly rejected (MSA|AR) by PMS this session. PMS closes its TCP
    // connection after every transaction regardless of outcome, which triggers our
    // auto-reconnect, which re-triggers a resend pass — an AR'd txn would otherwise get
    // resent unchanged on every one of those reconnects, forever. In-memory only
    // (no DB): cleared on app restart, not persisted.
    private val rejectedTxnIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    companion object {
        /**
         * Grace delay after a success ACK before a synced transaction is deleted from local
         * storage, allowing the PMS to pull the transaction images from the device image server.
         */
        private const val SYNCED_TXN_DELETE_DELAY_MS = 2_000L
    }

    init {
        scope.launch {
            if (preferenceHelper.isHl7Enabled()) {
                observePendingHl7Transactions()
            }
        }
    }


    /**
     * Entry point for all inbound HL7 messages received from PMS.
     * Uses the new hl7Core [HL7MessageKind] classifier instead of the old
     * CompleteHL7Message property bag.
     */
    fun handleReceivedMessage(message: HL7Message) {
        val inboundType = classifyInboundMessage(message) ?: return

        if (inboundType == MessageType.DISPENSE_REQUEST || inboundType == MessageType.EDIT_DISPENSE_REQUEST) {
            val rxe = message.segment<RXESegment>(RXESegment.NAME)
            // ZNI (Eyecon order packet) and ZUI (PMSS order data packet) can carry the
            // dispense amount standalone, without RXE.
            val zni = message.segment<ZNISegment>(ZNISegment.NAME)
            val zui = message.segment<ZUISegment>(ZUISegment.NAME)
            val dispenseStr = rxe?.dispenseAmount?.trim()?.takeIf { it.isNotBlank() }
                ?: rxe?.giveAmountMinimum?.trim()?.takeIf { it.isNotBlank() }
                ?: zni?.dispenseAmount?.trim()
                ?: zui?.orderDispenseQuantity?.trim()
            val parsedCount = dispenseStr?.toDoubleOrNull()?.toInt()

            if (parsedCount == null || parsedCount <= 0) {
                logger.e("Invalid or missing dispense count: '$dispenseStr' in ${inboundType.name}. Rejecting message.")
                throw IllegalArgumentException("Invalid or missing dispense count: $dispenseStr")
            }

            // NDC presence must be checked here, synchronously, before the ACK is built.
            // The per-segment handlers (handleRdeDispenseRequest / handleZuiOrderPacketDispenseRequest
            // / handleOrderPacketDispenseRequest) run inside scope.launch below, which is async —
            // by the time they'd notice a missing NDC and bail, handleIncomingMessage has already
            // called hl7.ack(message) and sent back AA. Checking here is what actually gates the ACK.
            val ndcStr = rxe?.giveCode?.trim()?.takeIf { it.isNotBlank() }
                ?: zni?.ndc?.trim()?.takeIf { it.isNotBlank() }
                ?: zui?.ndc?.trim()

            if (ndcStr.isNullOrBlank()) {
                logger.e("Missing NDC in ${inboundType.name}. Rejecting message.")
                throw IllegalArgumentException("Missing NDC")
            }
        }

        scope.launch {
            when (inboundType) {
                MessageType.DISPENSE_REQUEST -> {
                    val hasRxe = message.segment<RXESegment>(RXESegment.NAME) != null
                    val hasZui = message.segment<ZUISegment>(ZUISegment.NAME) != null
                    when {
                        hasRxe -> handleRdeDispenseRequest(message)
                        hasZui -> handleZuiOrderPacketDispenseRequest(message)
                        else -> handleOrderPacketDispenseRequest(message)
                    }
                }

                MessageType.EDIT_DISPENSE_REQUEST ->
                    handleOrderEdit(message)

                MessageType.INVENTORY_REQUEST ->
                    handleInrInventoryRequest(message)

                MessageType.CANCEL_ORDER ->
                    handleOrderCancellation(message)
            }
        }
    }


    /**
     * Builds outbound HL7 config from the persisted terminal/PMS host/HL7 version
     * preferences, so RDS/INR messages are versioned per the value fetched from
     * auth/me (settings.hl7Version) rather than the hardcoded default.
     */
    private fun currentHl7Config(): HL7Config {
        val hl7Format = preferenceHelper.getHl7Format()
        // Eyecon's PMS-side routing keys off MSH-3 == "EYECON" (its own format
        // name) rather than this app's display name — DispenseSure/Vivid keep
        // using the app's display name (context.getString(R.string.app_name)),
        // unchanged, since PMS already matches on that for those formats.
        val sendingApplicationName = if (hl7Format == Hl7Format.EYECON) {
            hl7Format.sendingApplication
        } else {
            context.getString(R.string.app_name)
        }
        return HL7Config.current(
            selectedTerminalName = preferenceHelper.getSelectedTerminalName() ?: "PILLCOUNTER",
            pmsHostName = preferenceHelper.getHl7PmsHost().ifBlank { "PMS" },
            hl7Version = preferenceHelper.getHl7Version(),
            hl7Format = hl7Format,
            sendingApplicationName = sendingApplicationName
        )
    }

    @SuppressLint("SimpleDateFormat")
    suspend fun buildAndSendSuccessfulDispense(
        txnId: Long
    ) {
        val txn = txnDao.getById(txnId)
            ?: run {
                logger.w("Dispense HL7 skipped — txn $txnId not found")
                return
            }
        val txnDetails = txnDetailsDao.getAllForTxn(txnId.toString())
        //Change this condition because we have transaction status that we are handling from pms
//        val totalCount = txnDetails.sumOf { it.pillCount ?: 0 }
//        if (totalCount == 0) {
//            return
//        }
        // txn.localId is a FK to UserEntity.localId, not the business userId. Resolving it with
        // getByUserId compared a Room row id against a JWT-derived string, so it never matched:
        // the operator was always null, RXD-10 was omitted, and every dispense arrived at the
        // Companion with a blank Operator column.
        val user = txn.localId?.let { userDao.getByLocalId(it) }
        if (user == null) {
            logger.w("Dispense $txnId has no resolvable operator (localId=${txn.localId}) — RXD-10 will be empty")
        }
        val location = locationProvider.getCurrentLocationAsString()

        val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }
            ?: run {
                // Without a drug row there is no NDC to put in RXD-2, so the message cannot be
                // built — but say so: this txn will sit unsynced forever and someone will ask why.
                logger.w("Dispense HL7 skipped — txn $txnId has no drug (drugId=${txn.drugId})")
                return
            }

        val substitutedDrug = if (txn.isSubstitute) txn.substitutedDrugId?.let { drugMasterDao.getDrugById(it) } else null
        val scannedNdc = substitutedDrug?.ndc ?: drug.ndc

        // Locally-scanned dispenses (isComingFromHL7 = false) have no inbound MSH-10 to reuse,
        // so HL7MessageBuilder falls back to txnId as the outbound control id. Persist that
        // here so ImageNanoServer's getByMessageControlId lookup can find this exact row when
        // the PMS pulls images by control id — without this write the DB column stays null
        // forever and that lookup always misses.
        if (txn.hl7MessageControlId.isNullOrBlank()) {
            txnDao.updateHl7MessageControlId(txnId, txnId.toString())
        }

        val message = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = txnDetails,
            drugCode = drug.ndc,
            scannedDrugCode = scannedNdc,
            drugName = drug.drugName ?: "",
            pharmacistId = user?.userId,
            pharmacistName = listOfNotNull(user?.fName, user?.lName)
                .joinToString(" "),
            pharmacistFamilyName = user?.lName,
            pharmacistGivenName = user?.fName,
            location = location,
            isControlledSubstance = isControlledDrugType(drug.drugType),
            isHazardousDrug = drug.isHazardous,
            config = currentHl7Config()
        )
        val controlId = txn.hl7MessageControlId?.takeIf { it.isNotBlank() } ?: txnId.toString()
        logger.i("Dispense HL7 message built for txn $txnId | controlId=$controlId | length=${message.length}")
        val result = hl7MessageSender.send(message)
        val ack = result.getOrNull()
        if (result.isSuccess && ack != null && isSuccessAck(ack)) {
            markTransactionSynced(txnId)
        } else if (result.isSuccess && ack != null && isRejectAck(ack)) {
            // Explicit reject (MSA|AR) — PMS rejected this exact message, not a transport
            // failure. PMS closes its TCP connection right after, which triggers our
            // auto-reconnect; without this, the next auto-reconnect's resend pass would
            // resend this same message and get rejected again, forever. Skip it from
            // automatic resend until the app restarts.
            rejectedTxnIds.add(txnId)
            logger.w("Dispense HL7 send for txnId=$txnId rejected (AR) — excluding from automatic resend: $ack")
        } else {
            logger.w(
                "Dispense HL7 send for txnId=$txnId failed or NAKed: " +
                    "${result.exceptionOrNull()?.message ?: "non-success ACK"} — leaving unsynced for retry"
            )
        }
    }

    /**
     * Builds and sends the inventory response for a batch, splitting it into
     * multiple independently-ACKed HL7 messages (chunks) when the batch is large,
     * so no single message risks exceeding PMS's message-size ceiling.
     *
     * Chunks are sent strictly in order, one at a time, each only after the
     * previous chunk's ACK succeeds. Progress is persisted
     * ([BatchDao.markChunkAcked]) immediately after every successful ACK, so if
     * PMS goes offline or the app is killed mid-sync, the next call (via
     * [resendPendingHl7BatchTransactions]) resumes at the first un-ACKed chunk
     * instead of resending chunks PMS has already applied. The batch is marked
     * synced ([BatchDao.markBatchSynced]) only once every chunk has ACKed.
     */
    suspend fun buildAndSendInventoryResponse(batchId: Long) {
        try {
            logger.i("buildAndSendInventoryResponse started, batchId=$batchId")

            val batch = batchDao.getById(batchId)
            if (batch == null) {
                logger.w("No batch found for batchId=$batchId")
                return
            }

            val txns = bottleInfoDao.getByBatchId(batchId)
            logger.i("txns count = ${txns.size}, txns = $txns")

            val chunks = HL7MessageBuilder.buildInventoryMessageChunks(
                batch = batch,
                txns = txns,
                config = currentHl7Config()
            )

            // totalChunks is fixed the first time this batch starts sending; on a
            // resume, chunk contents are rebuilt from the same DB rows, so the
            // count should match what was persisted, but persist defensively in
            // case rows changed since the last attempt (e.g. an edit while offline).
            if (batch.totalChunks != chunks.size) {
                batchDao.setTotalChunks(batchId, chunks.size)
            }

            val resumeFromChunk = batch.lastAckedChunkIndex + 1
            logger.i("Sending ${chunks.size} inventory chunk(s) for batchId=$batchId, resuming at chunk $resumeFromChunk")

            for (chunk in chunks) {
                if (chunk.chunkIndex < resumeFromChunk) {
                    // Already ACKed on a prior attempt — do not resend.
                    continue
                }

                logger.i("Sending inventory chunk ${chunk.chunkIndex}/${chunk.totalChunks} for batchId=$batchId")
                val result = hl7MessageSender.sendRaw(chunk.message)
                val ackAccepted = result.getOrNull()?.let { isSuccessAck(it) } ?: false

                if (result.isSuccess && ackAccepted) {
                    batchDao.markChunkAcked(batchId, chunk.chunkIndex)
                    logger.i("Chunk ${chunk.chunkIndex}/${chunk.totalChunks} ACKed for batchId=$batchId")
                } else {
                    // Stop here — do not send later chunks out of order. The batch
                    // stays unsynced and will resume at this exact chunk next time.
                    logger.e(
                        "Chunk ${chunk.chunkIndex}/${chunk.totalChunks} failed/NAKed for batchId=$batchId: " +
                            "${result.exceptionOrNull()?.message ?: "non-success ACK"}"
                    )
                    return
                }
            }

            batchDao.markBatchSynced(batchId)
            logger.i("All chunks sent — inventory HL7 sync complete for batchId=$batchId")

        } catch (e: Exception) {
            logger.e("buildAndSendInventoryResponse failed for batchId=$batchId", e)
        }
    }

    /**
     * Sends one dispense the moment it completes.
     *
     * Until now the only trigger for outbound dispense HL7 was
     * [resendPendingHl7Transactions], which runs when the MLLP client (re)connects. That is a
     * recovery path, not a primary one: a dispense completed while the connection was already
     * up sat unsynced until the socket happened to bounce, and a locally scanned dispense
     * (isComingFromHL7 = 0) never qualified for the resend query at all — the pharmacist
     * finished the count and the PMS never heard about it. This is the primary path; the
     * resend-on-connect sweep remains as retry for sends that fail here.
     */
    fun sendDispenseNow(txnId: Long) {
        if (preferenceHelper.isHl7Enabled()) {
            scope.launch {
                logger.i("Dispense completed — sending HL7 now, txnId=$txnId")
                buildAndSendSuccessfulDispense(txnId = txnId)
            }
        } else {
            logger.i("HL7 disabled — skipping dispense send, txnId=$txnId")
        }
    }

    /**
     * Resends every pending (unsynced, HL7-originated, completed) transaction's HL7
     * message, one at a time, waiting for each ACK before moving to the next.
     * [resendMutex] ensures the Room-flow observer and onClientConnected triggers can
     * never overlap and send the same txn's message twice concurrently.
     */
    fun resendPendingHl7Transactions() {
        scope.launch {
            if (!resendMutex.tryLock()) {
                logger.i("resendPendingHl7Transactions already in progress — skipping duplicate trigger")
                return@launch
            }
            try {
                val pendingTxn = pillCountTxnDao.getPendingHl7TxnOnce()
                    .filterNot { it.txnId in rejectedTxnIds }
                if (pendingTxn.isEmpty()) {
                    logger.i("No pending HL7 transactions to sync")
                    return@launch
                }
                logger.i("Resending ${pendingTxn.size} pending HL7 transactions")
                for (txn in pendingTxn) {
                    if (txn.isDispense) {
                        buildAndSendSuccessfulDispense(txnId = txn.txnId)
                    }
                }
            } finally {
                resendMutex.unlock()
            }
        }
    }

    fun resendPendingHl7BatchTransactions() {
        scope.launch {
            val pendingBatches = batchDao.getUnsyncedCompletedBatchesOnce()
            if (pendingBatches.isEmpty()) {
                logger.i("No pending HL7 batch transactions to sync")
                return@launch
            }
            logger.i("Resending ${pendingBatches.size} pending HL7 batch transactions")
            for (batch in pendingBatches) {
                buildAndSendInventoryResponse(batchId = batch.batchId)
            }
        }
    }

    /**
     * Marks [txnId] synced. Called only on a success ACK for the exact message that
     * was sent for this txn — never from a shared "last sent" slot, so concurrent
     * in-flight sends can't mark the wrong transaction synced.
     */
    private fun markTransactionSynced(txnId: Long) {
        scope.launch {
            // Flag the txn synced first, then — when the server disallows local storage —
            // delete it. The PMS pulls images from the device image server before sending
            // the success ACK, so the images are already retrieved by the time we delete here.
            pillCountTxnDao.markTxnSynced(txnId)
            if (!preferenceHelper.isAllowLocalStorage()) {
                // Wait before deleting: the PMS pulls the transaction images from the device
                // image server (ImageNanoServer) *after* the success ACK. Deleting immediately
                // would remove the image files before that pull completes, leaving the PMS
                // without images. The delay gives the PMS time to fetch them first.
                delay(SYNCED_TXN_DELETE_DELAY_MS)
                deleteSyncedTransaction(txnId)
            }
        }
    }

    /**
     * Removes a synced dispense transaction and its image files from local storage. Row deletion
     * cascades to its detail rows; the barcode and detail images are file-system artifacts and
     * must be deleted explicitly.
     */
    private suspend fun deleteSyncedTransaction(txnId: Long) {
        if (txnId <= 0L) return
        try {
            val txn = pillCountTxnDao.getById(txnId)
            val filesToDelete = mutableListOf<String>()
            BottleInfoJson.decode(txn?.bottleInfoListJson).mapNotNull { it.barcodeImagePath }.forEach { filesToDelete.add(it) }
            filesToDelete.addAll(pillCountTxnDao.getTransactionDetailsImages(txnId))

            pillCountTxnDao.deleteTransaction(txnId)

            filesToDelete.forEach { path ->
                val file = java.io.File(path)
                if (file.exists() && !file.delete()) {
                    logger.w("Failed to delete file for synced txn $txnId: $path")
                }
            }
            logger.i("Deleted synced txn $txnId from local storage (allowLocalStorage=false)")
        } catch (e: Exception) {
            logger.e("Failed to delete synced txn $txnId", e)
        }
    }

    // ─────────────────────────── INBOUND HANDLER: RDE^O11 (DISPENSE REQUEST) ───────────────────────────

    private suspend fun handleRdeDispenseRequest(message: HL7Message) {
        // Extract drug data from RXE segment (pharmacy encoded order)
        val rxe = message.segment<RXESegment>(RXESegment.NAME)
        val orc = message.segment<ORCSegment>(ORCSegment.NAME)

        val hl7Ndc = rxe?.giveCode?.trim().orEmpty()
        val hl7DrugName = rxe?.giveName.orEmpty()
        val dispenseStr = rxe?.dispenseAmount?.trim()?.takeIf { it.isNotBlank() } ?: rxe?.giveAmountMinimum?.trim()
        val targetCount = dispenseStr?.toDoubleOrNull()?.toInt()
        val rxNo = orc?.placerOrderNumber?.takeIf { it.isNotBlank() }

        if (hl7Ndc.isBlank()) {
            logger.w("handleRdeDispenseRequest: NDC missing from RXE — ignoring message")
            return
        }

        // Local-first check
        val localDrug = drugMasterDao.getDrugByNdc(hl7Ndc)

        val finalDrug = if (localDrug != null) {
            logger.i("Drug found in local DB for NDC: $hl7Ndc")
            localDrug
        } else {
            logger.i("Drug not found locally for NDC: $hl7Ndc, calling API")

            val request = GetNdcRequestModel(
                target_ndc = hl7Ndc,
                scanned_ndc = hl7Ndc
            )

            val drugInfo = try {
                drugRepository.getDrugInfoByNdc(request)
            } catch (e: Exception) {
                logger.e("Failed to fetch drug info from API for NDC: $hl7Ndc", e)
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(R.string.hl7_notification_drug_not_found_api_failed, hl7Ndc)
                )
                return
            }

            val resolvedNdc = drugInfo?.ndc?.takeIf { it.isNotBlank() }

            val resolvedDrugName = drugInfo?.genericName
                ?.takeIf { it.isNotBlank() }

            if (resolvedDrugName.isNullOrBlank()) {
                logger.w("No drug name resolved for NDC: $hl7Ndc")
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(R.string.hl7_notification_drug_not_found_no_drug, hl7Ndc)
                )
                return
            }

            resolvedNdc?.let {
                val imagePath = drugImageDownloader.downloadAndSave(
                    url = drugInfo.imageUrl,
                    drugName = resolvedDrugName
                )
                DrugMasterEntity(
                    ndc = it,
                    drugName = resolvedDrugName,
                    drugType = drugInfo.drugType,
                    isHazardous = drugInfo.isHazardous ?: false,
                    strength = drugInfo.strength,
                    dosageForm = drugInfo.dosageForm,
                    drugImagePath = imagePath,
                )
            }
        }

        val drugId = finalDrug?.let { drugMasterDao.upsertPreservingId(it) }

        // Priority from ZPR segment: ZPR|1|PRIORITY|<STAT|URGENT|ROUTINE|TIMED>
        val priority = TxnPriority.fromString(
            message.segment<ZPRSegment>(ZPRSegment.NAME)
                ?.takeIf { it.qualifier == "PRIORITY" }
                ?.priority
        )

        val txnStatus = mapHl7OrderStatus(orc?.orderStatus) ?: CountStatus.PARTIAL

        logger.i("MSH-10 raw messageControlId (site1/handleNewOrder) = '${message.messageControlId}'")

        val txn = PillCountTxnEntity(
            localId = preferenceHelper.getLocalId(),
            drugId = drugId,
            isDispense = true,
            targetCount = targetCount,
            status = txnStatus,
            isComingFromHL7 = true,
            isSynced = false,
            isNdcVerified = false,
            rxNo = rxNo,
            transactionOrderId = rxNo,
            priority = priority,
            hl7MessageControlId = message.messageControlId.takeIf { it.isNotBlank() },
            hl7SequenceNumber = message.sequenceNumber.takeIf { it.isNotBlank() }
        )

        logger.i("Saving txn (site1) hl7MessageControlId='${txn.hl7MessageControlId}' hl7SequenceNumber='${txn.hl7SequenceNumber}'")

        val txnId = pillCountTxnDao.upsertPreservingId(txn)
        preferenceHelper.saveTxnId(txnId)
        insertZinContainerDetails(txnId, message)

        val notifBody = if (targetCount != null && targetCount > 0) {
            context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), hl7DrugName, targetCount)
        } else {
            context.getString(R.string.hl7_notification_new_rx_multiple, rxNo.orEmpty(), 1)
        }
        notifier.show(
            title = context.getString(R.string.hl7_notification_new_rx_title),
            message = notifBody
        )
    }

    // ─────────────────────────── INBOUND HANDLER: STANDALONE ORDER-PACKET DISPENSE REQUEST ───────────────────────────

    /**
     * Handles dispense requests carried entirely in a single custom order-packet
     * segment (no RXE/ORC present) — e.g. a device-native order packet embedded
     * as a ZNI segment. Field mapping mirrors [handleRdeDispenseRequest]'s RXE/ORC
     * flow but is sourced solely from that segment.
     */
    private suspend fun handleOrderPacketDispenseRequest(message: HL7Message) {
        val orderPacket = message.segment<ZNISegment>(ZNISegment.NAME)
        if (orderPacket == null) {
            logger.w("handleOrderPacketDispenseRequest: no order-packet segment found — ignoring message")
            return
        }

        val hl7Ndc = orderPacket.ndc.trim()
        val hl7DrugName = orderPacket.drugName
        val targetCount = orderPacket.dispenseAmount.trim().toDoubleOrNull()?.toInt()
        val rxNo = orderPacket.prescriptionNumber.takeIf { it.isNotBlank() }
        val transactionOrderId = orderPacket.fillerOrderNumber.takeIf { it.isNotBlank() }
        val fillNo = orderPacket.fillNumber.takeIf { it.isNotBlank() }

        if (hl7Ndc.isBlank()) {
            logger.w("handleOrderPacketDispenseRequest: NDC missing from order-packet segment — ignoring message")
            return
        }

        // Local-first check
        val localDrug = drugMasterDao.getDrugByNdc(hl7Ndc)

        val finalDrug = if (localDrug != null) {
            logger.i("Drug found in local DB for NDC: $hl7Ndc")
            localDrug
        } else {
            logger.i("Drug not found locally for NDC: $hl7Ndc, calling API")

            val request = GetNdcRequestModel(
                target_ndc = hl7Ndc,
                scanned_ndc = hl7Ndc
            )

            val drugInfo = try {
                drugRepository.getDrugInfoByNdc(request)
            } catch (e: Exception) {
                logger.e("Failed to fetch drug info from API for NDC: $hl7Ndc", e)
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(R.string.hl7_notification_drug_not_found_api_failed, hl7Ndc)
                )
                return
            }

            val resolvedNdc = drugInfo?.ndc?.takeIf { it.isNotBlank() }

            val resolvedDrugName = drugInfo?.genericName
                ?.takeIf { it.isNotBlank() }

            if (resolvedDrugName.isNullOrBlank()) {
                logger.w("No drug name resolved for NDC: $hl7Ndc")
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(R.string.hl7_notification_drug_not_found_no_drug, hl7Ndc)
                )
                return
            }

            resolvedNdc?.let {
                DrugMasterEntity(
                    ndc = it,
                    drugName = resolvedDrugName,
                    drugType = drugInfo?.drugType,
                    isHazardous = drugInfo?.isHazardous ?: false,
                    strength = drugInfo?.strength,
                    dosageForm = drugInfo?.dosageForm,
                )
            }
        }

        val drugId = finalDrug?.let { drugMasterDao.upsertPreservingId(it) }

        // Priority from ZPR segment, if the sender included one alongside the order packet
        val priority = TxnPriority.fromString(
            message.segment<ZPRSegment>(ZPRSegment.NAME)
                ?.takeIf { it.qualifier == "PRIORITY" }
                ?.priority
        )

        logger.i("MSH-10 raw messageControlId (site2) = '${message.messageControlId}'")

        val txn = PillCountTxnEntity(
            localId = preferenceHelper.getLocalId(),
            drugId = drugId,
            targetCount = targetCount,
            status = CountStatus.PARTIAL,
            isComingFromHL7 = true,
            isSynced = false,
            isNdcVerified = false,
            rxNo = rxNo,
            transactionOrderId = transactionOrderId,
            refillNo = fillNo,
            priority = priority,
            hl7MessageControlId = message.messageControlId.takeIf { it.isNotBlank() },
            hl7SequenceNumber = message.sequenceNumber.takeIf { it.isNotBlank() },
            isDispense = true
        )

        logger.i("Saving txn (site2) hl7MessageControlId='${txn.hl7MessageControlId}' hl7SequenceNumber='${txn.hl7SequenceNumber}'")

        val txnId = pillCountTxnDao.upsertPreservingId(txn)
        preferenceHelper.saveTxnId(txnId)
        insertZinContainerDetails(txnId, message)

        val notifBody = if (targetCount != null && targetCount > 0) {
            context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), hl7DrugName, targetCount)
        } else {
            context.getString(R.string.hl7_notification_new_rx_multiple, rxNo.orEmpty(), 1)
        }
        notifier.show(
            title = context.getString(R.string.hl7_notification_new_rx_title),
            message = notifBody
        )
    }

    // ─────────────────────────── INBOUND HANDLER: ZUI ORDER DATA PACKET DISPENSE REQUEST ───────────────────────────

    /**
     * Handles dispense requests carried entirely in a ZUI order-data-packet segment
     * (PMSS → VIVID, RDE^O11, no RXE/ORC present). Field mapping mirrors
     * [handleOrderPacketDispenseRequest]'s ZNI flow but is sourced from the ZUI
     * "order data packet" accessor group.
     */
    private suspend fun handleZuiOrderPacketDispenseRequest(message: HL7Message) {
        val orderPacket = message.segment<ZUISegment>(ZUISegment.NAME)
        if (orderPacket == null) {
            logger.w("handleZuiOrderPacketDispenseRequest: no ZUI segment found — ignoring message")
            return
        }

        val hl7Ndc = orderPacket.ndc.trim()
        val hl7DrugName = orderPacket.orderDrugName
        val targetCount = orderPacket.orderDispenseQuantity.trim().toDoubleOrNull()?.toInt()
        val rxNo = orderPacket.orderRxNumber.takeIf { it.isNotBlank() }

        if (hl7Ndc.isBlank()) {
            logger.w("handleZuiOrderPacketDispenseRequest: NDC missing from ZUI segment — ignoring message")
            return
        }

        // Local-first check
        val localDrug = drugMasterDao.getDrugByNdc(hl7Ndc)

        val finalDrug = if (localDrug != null) {
            logger.i("Drug found in local DB for NDC: $hl7Ndc")
            localDrug
        } else {
            logger.i("Drug not found locally for NDC: $hl7Ndc, calling API")

            val request = GetNdcRequestModel(
                target_ndc = hl7Ndc,
                scanned_ndc = hl7Ndc
            )

            val drugInfo = try {
                drugRepository.getDrugInfoByNdc(request)
            } catch (e: Exception) {
                logger.e("Failed to fetch drug info from API for NDC: $hl7Ndc", e)
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(R.string.hl7_notification_drug_not_found_api_failed, hl7Ndc)
                )
                return
            }

            val resolvedNdc = drugInfo?.ndc?.takeIf { it.isNotBlank() }

            val resolvedDrugName = drugInfo?.genericName
                ?.takeIf { it.isNotBlank() }

            if (resolvedDrugName.isNullOrBlank()) {
                logger.w("No drug name resolved for NDC: $hl7Ndc")
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(R.string.hl7_notification_drug_not_found_no_drug, hl7Ndc)
                )
                return
            }

            resolvedNdc?.let {
                DrugMasterEntity(
                    ndc = it,
                    drugName = resolvedDrugName,
                    drugType = drugInfo?.drugType,
                    isHazardous = drugInfo?.isHazardous ?: false,
                    strength = drugInfo?.strength,
                    dosageForm = drugInfo?.dosageForm,
                )
            }
        }

        val drugId = finalDrug?.let { drugMasterDao.upsertPreservingId(it) }

        // Priority from ZPR segment, if the sender included one alongside the order packet
        val priority = TxnPriority.fromString(
            message.segment<ZPRSegment>(ZPRSegment.NAME)
                ?.takeIf { it.qualifier == "PRIORITY" }
                ?.priority
        )

        logger.i("MSH-10 raw messageControlId (site3/orderPacket) = '${message.messageControlId}'")

        val txn = PillCountTxnEntity(
            localId = preferenceHelper.getLocalId(),
            drugId = drugId,
            targetCount = targetCount,
            status = CountStatus.PARTIAL,
            isComingFromHL7 = true,
            isSynced = false,
            isNdcVerified = false,
            rxNo = rxNo,
            refillNo = orderPacket.orderFillNumber.takeIf { it.isNotBlank() },
            priority = priority,
            hl7MessageControlId = message.messageControlId.takeIf { it.isNotBlank() },
            hl7SequenceNumber = message.sequenceNumber.takeIf { it.isNotBlank() },
            transactionOrderId = orderPacket.orderTransactionOrderId.takeIf { it.isNotBlank() },
            isDispense = true
        )

        logger.i("Saving txn (site3) hl7MessageControlId='${txn.hl7MessageControlId}' hl7SequenceNumber='${txn.hl7SequenceNumber}' transactionOrderId='${txn.transactionOrderId}'")

        val txnId = pillCountTxnDao.upsertPreservingId(txn)
        preferenceHelper.saveTxnId(txnId)
        insertZinContainerDetails(txnId, message)

        val notifBody = if (targetCount != null && targetCount > 0) {
            context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), hl7DrugName, targetCount)
        } else {
            context.getString(R.string.hl7_notification_new_rx_multiple, rxNo.orEmpty(), 1)
        }
        notifier.show(
            title = context.getString(R.string.hl7_notification_new_rx_title),
            message = notifBody
        )
    }

    private data class ResolvedInventoryItem(
        val ndc: String,
        val drugId: Long,
        val resolvedName: String,
        val lot: String,
        val expiry: String,
        val targetCount: Int
    )

    // ─────────────────────────── INBOUND HANDLER: INR^U04/U06 (INVENTORY REQUEST) ───────────────────────────

    private suspend fun handleInrInventoryRequest(message: HL7Message) {
        logger.i("Handling INR Inventory Request | msgId=${message.messageControlId}")

        // INV segments carry the substance / drug info in an INR message
        val invSegments = message.segments<INVSegment>(INVSegment.NAME)

        val resolvedItems = mutableListOf<ResolvedInventoryItem>()

        if (invSegments.isNotEmpty()) {
            for (inv in invSegments) {
                val ndc = inv.substanceCode.trim()
                val lot = inv.lotNumber.trim()
                val expiry = inv.expirationDate.trim()
                val targetCount = inv.inventoryOnHandQuantity.toIntOrNull() ?: 0

                if (ndc.isBlank()) {
                    logger.w("Skipping INV segment — NDC is blank")
                    continue
                }

                resolveInventoryItem(
                    ndc = ndc,
                    fallbackName = inv.substanceName,
                    lot = lot,
                    expiry = expiry,
                    targetCount = targetCount
                )?.let { resolvedItems.add(it) }
            }
        } else {
            // No INV segments — some PMS senders (e.g. this INR^U04 variant) carry the
            // substance data in the RXE segment instead.
            val rxe = message.segment<RXESegment>(RXESegment.NAME)
            val ndc = rxe?.giveCode?.trim().orEmpty()
            if (rxe == null || ndc.isBlank()) {
                logger.w("No INV or usable RXE segment found in HL7 message — ignoring")
                return
            }
            val targetCount = (rxe.dispenseAmount.trim().takeIf { it.isNotBlank() }
                ?: rxe.giveAmountMinimum.trim())
                .toDoubleOrNull()?.toInt() ?: 0

            resolveInventoryItem(
                ndc = ndc,
                fallbackName = rxe.giveName,
                lot = "",
                expiry = "",
                targetCount = targetCount
            )?.let { resolvedItems.add(it) }
        }

        if (resolvedItems.isEmpty()) {
            logger.w("No valid drugs resolved from local DB or API. Batch will not be created.")
            notifier.show(
                title = context.getString(R.string.hl7_notification_inventory_title),
                message = context.getString(R.string.hl7_notification_inventory_no_drugs)
            )
            return
        }

        val now = System.currentTimeMillis()
        val batch = BatchEntity(
            batchId = now,
            startDateTime = now,
            endDateTime = null,
            status = BatchStatus.INPROGRESS,
            isDeleted = false,
            note = null,
            bucketId = "",
            requestIdFromPMS = message.messageControlId
        )
        val batchId = batchDao.insert(batch)

        for (item in resolvedItems) {
            val txn = StockTxnEntity(
                drugId = item.drugId,
                status = CountStatus.PARTIAL,
                batchId = batchId
            )

            val txnId = stockTxnDao.upsertPreservingId(txn)

            logger.i(
                "Inserted stock txn for NDC: ${item.ndc}, drugId: ${item.drugId}, txnId: $txnId, lotNo: ${item.lot}, expiry: ${item.expiry}, targetCount: ${item.targetCount}"
            )
        }

        // Stock txns added → persist the batch's live NDC total and running user.
        stockTxnDao.refreshBatchTotalNdcs(batchId)
        stockTxnDao.updateBatchUserName(
            batchId,
            preferenceHelper.getLoggedInEmail() ?: preferenceHelper.getUserId()
        )

        logger.i("Processed ${resolvedItems.size} inventory items for batchId: $batchId")

        notifier.show(
            title = context.getString(R.string.hl7_notification_inventory_title),
            message = context.getString(R.string.hl7_notification_inventory_items_count, resolvedItems.size)
        )
    }

    /**
     * Resolves a single inventory NDC to a [ResolvedInventoryItem], checking the local
     * DB first and falling back to the drug-info API (saving the result locally) when
     * not found. Returns null when the drug can't be resolved by either path.
     */
    private suspend fun resolveInventoryItem(
        ndc: String,
        fallbackName: String,
        lot: String,
        expiry: String,
        targetCount: Int
    ): ResolvedInventoryItem? {
        val existingDrug = drugMasterDao.getDrugByNdc(ndc)
        if (existingDrug != null) {
            val localName = existingDrug.drugName
            if (!localName.isNullOrBlank()) {
                logger.i("Drug found in local DB for NDC: $ndc")
                return ResolvedInventoryItem(
                    ndc = ndc,
                    drugId = existingDrug.drugId,
                    resolvedName = localName,
                    lot = lot,
                    expiry = expiry,
                    targetCount = targetCount
                )
            }
            logger.w("Drug found in local DB but drugName is empty for NDC: $ndc")
            return null
        }

        val request = GetNdcRequestModel(target_ndc = ndc, scanned_ndc = ndc)
        return try {
            logger.i("Drug not found locally for NDC: $ndc, calling API")

            val drugInfo = drugRepository.getDrugInfoByNdc(request)

            val resolvedDrugName = drugInfo?.genericName?.takeIf { it.isNotBlank() }
                ?: fallbackName.takeIf { it.isNotBlank() }

            if (resolvedDrugName.isNullOrBlank()) {
                logger.w("Skipping inventory item because API returned no usable drug name for NDC: $ndc")
                return null
            }

            val imagePath = drugImageDownloader.downloadAndSave(
                url = drugInfo?.imageUrl,
                drugName = resolvedDrugName ?: drugInfo?.ndc
            )
            val drugEntity = DrugMasterEntity(
                ndc = drugInfo?.ndc?.takeIf { it.isNotBlank() } ?: ndc,
                drugName = resolvedDrugName,
                drugType = drugInfo?.drugType,
                packageQty = drugInfo?.qty,
                isHazardous = drugInfo?.isHazardous ?: false,
                strength = drugInfo?.strength,
                dosageForm = drugInfo?.dosageForm,
                drugImagePath = imagePath,
            )

            val newDrugId = drugMasterDao.upsertPreservingId(drugEntity)

            logger.i("Drug resolved from API and saved locally for NDC: $ndc")
            ResolvedInventoryItem(
                ndc = ndc,
                drugId = newDrugId,
                resolvedName = resolvedDrugName,
                lot = lot,
                expiry = expiry,
                targetCount = targetCount
            )
        } catch (e: Exception) {
            logger.e("Failed to fetch drug info from API for NDC: $ndc", e)
            null
        }
    }

    /**
     * Inserts ZIN|...|EXPECTED_ON_HAND|<count> detail rows into the transaction.
     * Uses the new [ZINSegment] typed segment from hl7Core.
     */
    private suspend fun insertZinContainerDetails(txnId: Long, message: HL7Message) {
        val zinSegments = message.segments<ZINSegment>(ZINSegment.NAME)
            .filter { it.dispenseType == "EXPECTED_ON_HAND" }

        var inserted = false
        for (segment in zinSegments) {
            val pillCount = segment.quantity.toIntOrNull() ?: continue
            if (pillCount == 0) {
                logger.i("ZIN EXPECTED_ON_HAND: skipping detail insert for txnId=$txnId because pillCount=0")
                continue
            }
            txnDetailsDao.insert(
                PillCountTxnDetailsEntity(
                    txnId = txnId,
                    pillCount = pillCount,
                    type = StepState.CONTAINER_INITIATE.name,
                    isManual = false
                )
            )
            logger.i("ZIN EXPECTED_ON_HAND: inserted detail txnId=$txnId pillCount=$pillCount")
            inserted = true
        }
        if (inserted) {
            pillCountTxnDao.updateWorkflowStep(txnId, StepState.TARGET_VERIFICATION.name)
            logger.i("ZIN: workflowStep updated to TARGET_VERIFICATION for txnId=$txnId")
        }
    }

    // ─────────────────────────── MESSAGE CLASSIFICATION ───────────────────────────

    /**
     * Classifies an inbound [HL7Message] using the new hl7Core [HL7MessageKind] enum.
     */
    private fun classifyInboundMessage(message: HL7Message): MessageType? {
        val orc = message.segment<ORCSegment>(ORCSegment.NAME)
        val orderControl = orc?.orderControl?.uppercase()
        val rxe = message.segment<RXESegment>(RXESegment.NAME)
        val hasRxe = rxe != null
        // A standalone order-packet segment (e.g. ZNI, ZUI) can carry a full dispense
        // request on its own, without RXE/ORC.
        val hasOrderPacket = message.segment<ZNISegment>(ZNISegment.NAME) != null ||
            message.segment<ZUISegment>(ZUISegment.NAME) != null

        return when (message.kind) {
            HL7MessageKind.CANCEL_ORDER ->
                MessageType.CANCEL_ORDER

            HL7MessageKind.DISPENSE_ORDER -> when {
                orderControl == "XO" && !orc?.placerOrderNumber.isNullOrBlank() && hasRxe ->
                    MessageType.EDIT_DISPENSE_REQUEST
                hasRxe || hasOrderPacket ->
                    MessageType.DISPENSE_REQUEST
                else -> null
            }

            HL7MessageKind.INVENTORY_REQUEST,
            HL7MessageKind.INVENTORY_RESPONSE ->
                MessageType.INVENTORY_REQUEST

            else -> null
        }
    }

    // ─────────────────────────── INBOUND HANDLER: ORC|CA (CANCEL) ───────────────────────────

    private suspend fun handleOrderCancellation(message: HL7Message) {
        val rxNo = message.segment<ORCSegment>(ORCSegment.NAME)?.placerOrderNumber
            ?.takeIf { it.isNotBlank() } ?: return
        logger.i("Received ORC|CA for rxNo=$rxNo — soft-deleting transaction")
        pillCountTxnDao.softDeleteByRxNo(rxNo)
        logger.i("Transaction with rxNo=$rxNo marked as deleted")
    }

    // ─────────────────────────── INBOUND HANDLER: ORC|XO (EDIT) ───────────────────────────

    /**
     * Handles ORC|XO (change-order) messages from PMS.
     *
     * Finds the active (PARTIAL, non-deleted) transaction for the given Rx number
     * and updates its drug, target count, and priority with the new values from the
     * incoming HL7 message.  If the transaction has already been completed or doesn't
     * exist, the edit is ignored and a notification is shown.
     *
     * Fields updated:
     * - [PillCountTxnEntity.drugId]      — resolved from the incoming NDC
     * - [PillCountTxnEntity.targetCount] — from RXE quantity
     * - [PillCountTxnEntity.isSynced]    — reset to false so the updated result is re-sent
     */
    private suspend fun handleOrderEdit(message: HL7Message) {
        val orc = message.segment<ORCSegment>(ORCSegment.NAME) ?: return
        val rxNo = orc.placerOrderNumber.takeIf { it.isNotBlank() } ?: return
        val rxe = message.segment<RXESegment>(RXESegment.NAME) ?: return

        val orderStatusRaw = orc.orderStatus.uppercase()

        logger.i("Received ORC|XO for rxNo=$rxNo orderStatus=$orderStatusRaw — looking up existing transaction")

        // 1. Locate the active transaction; if soft-deleted, restore it so the edit can be applied
        var existingTxn = pillCountTxnDao.getActiveByRxNo(rxNo)
        if (existingTxn == null) {
            val deletedTxn = pillCountTxnDao.getDeletedByRxNo(rxNo)
            if (deletedTxn != null) {
                logger.i("ORC|XO: restoring deleted txnId=${deletedTxn.txnId} for rxNo=$rxNo before applying status=$orderStatusRaw")
                pillCountTxnDao.restoreDeletedTxn(deletedTxn.txnId)
                existingTxn = deletedTxn.copy(isDeleted = false, status = CountStatus.PARTIAL)
            }
        }
        if (existingTxn == null) {
            logger.w("ORC|XO ignored: no active or restorable transaction found for rxNo=$rxNo")
            notifier.show(
                title = context.getString(R.string.hl7_notification_edit_rx_title),
                message = context.getString(R.string.hl7_notification_edit_rx_not_found, rxNo)
            )
            return
        }

        // 2. Resolve the updated drug (local DB first, then API fallback)
        val hl7Ndc = rxe.giveCode.trim()
        val hl7DrugName = rxe.giveName
        val dispenseStr = rxe.dispenseAmount.trim().takeIf { it.isNotBlank() } ?: rxe.giveAmountMinimum.trim()
        val newTargetCount = dispenseStr.toDoubleOrNull()?.toInt()

        val localDrug = drugMasterDao.getDrugByNdc(hl7Ndc)

        val resolvedDrug = if (localDrug != null) {
            logger.i("ORC|XO: drug found locally for NDC=$hl7Ndc")
            localDrug
        } else {
            logger.i("ORC|XO: drug not found locally for NDC=$hl7Ndc, calling API")
            val request = GetNdcRequestModel(target_ndc = hl7Ndc, scanned_ndc = hl7Ndc)
            try {
                val drugInfo = drugRepository.getDrugInfoByNdc(request)
                val resolvedName = drugInfo?.genericName?.takeIf { it.isNotBlank() }
                if (resolvedName.isNullOrBlank()) {
                    logger.w("ORC|XO: API returned no drug name for NDC=$hl7Ndc — aborting edit")
                    notifier.show(
                        title = context.getString(R.string.hl7_notification_edit_rx_title),
                        message = context.getString(
                            R.string.hl7_notification_edit_rx_drug_not_found,
                            rxNo,
                            hl7Ndc
                        )
                    )
                    return
                }
                val imagePath = drugImageDownloader.downloadAndSave(
                    url = drugInfo.imageUrl,
                    drugName = resolvedName
                )
                DrugMasterEntity(
                    ndc = drugInfo.ndc.takeIf { it.isNotBlank() } ?: hl7Ndc,
                    drugName = resolvedName,
                    drugType = drugInfo.drugType,
                    isHazardous = drugInfo.isHazardous ?: false,
                    strength = drugInfo.strength,
                    dosageForm = drugInfo.dosageForm,
                    drugImagePath = imagePath,
                )
            } catch (e: Exception) {
                logger.e("ORC|XO: API call failed for NDC=$hl7Ndc", e)
                notifier.show(
                    title = context.getString(R.string.hl7_notification_edit_rx_title),
                    message = context.getString(
                        R.string.hl7_notification_edit_rx_drug_not_found,
                        rxNo,
                        hl7Ndc
                    )
                )
                return
            }
        }

        val newDrugId = resolvedDrug?.let { drugMasterDao.upsertPreservingId(it) }
            ?: existingTxn.drugId   // keep the old drugId if resolution somehow returned null

        // 3. Parse priority from ZPR segment
        val newPriority = TxnPriority.fromString(
            message.segment<ZPRSegment>(ZPRSegment.NAME)
                ?.takeIf { it.qualifier == "PRIORITY" }
                ?.priority
        )

        // 4. Map order status and apply the edit
        val newStatus = mapHl7OrderStatus(orderStatusRaw)

        pillCountTxnDao.updateFromHl7Edit(
            txnId = existingTxn.txnId,
            drugId = newDrugId,
            targetCount = newTargetCount,
            priority = newPriority,
            status = newStatus
        )

        logger.i(
            "ORC|XO applied: txnId=${existingTxn.txnId}, rxNo=$rxNo, " +
                "drugId=$newDrugId, targetCount=$newTargetCount, priority=$newPriority, status=$orderStatusRaw"
        )

        // 5. If status is CA, soft-delete after applying the edit
        if (orderStatusRaw == "CA") {
            logger.i("ORC|XO with status=CA — soft-deleting txnId=${existingTxn.txnId} after update")
            pillCountTxnDao.softDeleteByRxNo(rxNo)
            return
        }

        notifier.show(
            title = context.getString(R.string.hl7_notification_edit_rx_title),
            message = context.getString(
                R.string.hl7_notification_edit_rx_updated,
                rxNo,
                resolvedDrug?.drugName ?: hl7DrugName,
                newTargetCount ?: 0
            )
        )
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    /**
     * Maps an HL7 ORC-5 order status code to the app's [CountStatus].
     * IP = in-progress/resume → PARTIAL
     * CM = complete          → COMPLETED
     * HD = on-hold           → ON_HOLD
     * CA is handled via soft-delete in [handleOrderCancellation]; returns null here.
     */
    private fun mapHl7OrderStatus(orderStatus: String?): CountStatus? {
        return when (orderStatus?.uppercase()) {
            "IP" -> CountStatus.PARTIAL
            "CM" -> CountStatus.COMPLETED
            "HD" -> CountStatus.ON_HOLD
            else -> null
        }
    }

    private fun observePendingHl7Transactions() {
        scope.launch {
            pillCountTxnDao.observePendingHl7Txn()
                .collect { pendingTxn ->
                    logger.i("HL7 observer fired, pending=${pendingTxn.size}")
                    resendPendingHl7Transactions()
                }
        }
    }
}