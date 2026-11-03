package com.dispensesure.retail.feature.hl7.data.repository

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.DataPurged
import com.dispensesure.retail.core.audit.model.DispenseSyncAbandoned
import com.dispensesure.retail.core.audit.model.DispenseTxnCreated
import com.dispensesure.retail.core.audit.model.FieldChange
import com.dispensesure.retail.core.audit.model.Hl7DispenseSent
import com.dispensesure.retail.core.audit.model.Hl7SendOutcome
import com.dispensesure.retail.core.audit.model.OrderCancelled
import com.dispensesure.retail.core.audit.model.OrderDiscontinued
import com.dispensesure.retail.core.audit.model.OrderEdited
import com.dispensesure.retail.core.audit.model.OrderHeld
import com.dispensesure.retail.core.audit.model.OrderReleased
import com.dispensesure.retail.core.audit.model.PurgeReason
import com.dispensesure.retail.core.audit.model.SendTrigger
import com.dispensesure.retail.core.audit.model.TxnSource
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.models.isControlledDrugType
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.region.wording
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.core.Hl7MessageSender
import com.dispensesure.retail.feature.hl7.data.Hl7ConfigProvider
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.order.NativeOrderKey
import com.dispensesure.retail.feature.hl7.parsing.Hl7Parser
import com.dispensesure.retail.feature.hl7.util.DispenseMessageBuilder
import com.dispensesure.retail.feature.hl7.util.isRejectAck
import com.dispensesure.retail.feature.hl7.util.isSuccessAck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.model.segment.ZDRSegment
import org.rite.hl7.model.segment.ZINSegment
import org.rite.hl7.model.segment.ZNISegment
import org.rite.hl7.model.segment.ZUISegment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispense HL7: sends RDS^O13 when a count completes and resends unsynced ones,
 * and saves PMS orders (ZNI, ZUI packets), their ZIN counts and XO edits.
 */
@Singleton
class DispenseHl7Repository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drugMasterDao: DrugMasterDao,
    private val locationProvider: LocationProvider,
    private val dispenseTxnDao: DispenseTxnDao,
    private val dispenseCountDao: DispenseCountDao,
    private val preferenceHelper: PreferenceHelper,
    private val hl7MessageSender: Hl7MessageSender,
    private val drugResolver: DrugResolver,
    private val notifier: Hl7Notifier,
    private val hl7ConfigProvider: Hl7ConfigProvider,
    private val regionProvider: RegionProvider,
    private val auditLogger: AuditLogger,
) {

    private val logger = AppLogger.create<DispenseHl7Repository>()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Guards resendPendingDispenses() so the Room-flow observer and the
    // onClientConnected trigger can never run concurrently and double-send the
    // same pending txn's HL7 message before either ACK returns.
    private val resendMutex = Mutex()

    // txnIds explicitly rejected (MSA|AR) by PMS this session. PMS closes its TCP
    // connection after every transaction regardless of outcome, which triggers our
    // auto-reconnect, which re-triggers a resend pass — an AR'd txn would otherwise get
    // resent unchanged on every one of those reconnects, forever. In-memory only
    // (no DB): cleared on app restart, not persisted.
    private val rejectedTxnIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    // Txns just completed, not yet sent. Whichever path sends one first logs FIRST_SEND, the
    // same as StockHl7Repository.firstSendBatchIds; any later attempt is AUTO_RESEND.
    private val firstSendTxnIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    companion object {
        /**
         * Grace delay after a success ACK before a synced transaction is deleted from local
         * storage, allowing the PMS to pull the transaction images from the device image server.
         */
        private const val SYNCED_TXN_DELETE_DELAY_MS = 2_000L
    }

    init {
        observePendingHl7Transactions()
    }

    /** @param trigger First send at completion, or a resend; recorded on HL7_DISPENSE_SENT. */
    suspend fun sendDispense(
        txnId: Long,
        trigger: SendTrigger = SendTrigger.AUTO_RESEND
    ) {
        try {
            sendDispenseInternal(txnId, trigger)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Failed to build/send dispense HL7 for txnId=$txnId — leaving unsynced for retry", e, event = LogEvent.HL7_SEND_FAILED)
            auditLogger.log(Hl7DispenseSent(txnId, controlId = null, trigger = trigger, outcome = Hl7SendOutcome.FAILED))
        }
    }

    private suspend fun sendDispenseInternal(
        txnId: Long,
        trigger: SendTrigger
    ) {
        val txn = dispenseTxnDao.getById(txnId)
            ?: run {
                logger.w("Dispense HL7 skipped — txn $txnId not found")
                return
            }
        // Re-read under the caller's lock: completion triggers both sendDispenseNow and the
        // observer's resend sweep, so whichever gets here second must not send a second copy.
        if (txn.isSynced == true) {
            logger.i("Dispense HL7 skipped — txn $txnId already synced")
            return
        }
        val txnDetails = dispenseCountDao.getAllForTxn(txnId.toString())
        //Change this condition because we have transaction status that we are handling from pms
//        val totalCount = txnDetails.sumOf { it.pillCount ?: 0 }
//        if (totalCount == 0) {
//            return
//        }
        val location = locationProvider.getCurrentLocationAsString()

        val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }
            ?: run {
                // Without a drug row there is no NDC to put in RXD-2, so the message cannot be
                // built — but say so: this txn will sit unsynced forever and someone will ask why.
                logger.w("Dispense HL7 skipped — txn $txnId has no drug (drugId=${txn.drugId})")
                auditLogger.log(Hl7DispenseSent(txnId, txn.hl7MessageControlId, trigger, Hl7SendOutcome.SKIPPED_NO_DRUG))
                return
            }

        val substitutedDrug = if (txn.isSubstitute) txn.substitutedDrugId?.let { drugMasterDao.getDrugById(it) } else null
        val scannedNdc = substitutedDrug?.ndc ?: drug.ndc

        // Locally-scanned dispenses (isComingFromHL7 = false) have no inbound MSH-10 to reuse,
        // so DispenseMessageBuilder falls back to txnId as the outbound control id. Persist that
        // here so ImageNanoServer's getByMessageControlId lookup can find this exact row when
        // the PMS pulls images by control id — without this write the DB column stays null
        // forever and that lookup always misses.
        if (txn.hl7MessageControlId.isNullOrBlank()) {
            dispenseTxnDao.updateHl7MessageControlId(txnId, txnId.toString())
        }

        val message = DispenseMessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = txnDetails,
            drugCode = drug.ndc,
            scannedDrugCode = scannedNdc,
            drugName = drug.drugName ?: "",
            pharmacistName = listOfNotNull(txn.operatorFirstName, txn.operatorLastName)
                .joinToString(" "),
            pharmacistFamilyName = txn.operatorLastName,
            pharmacistGivenName = txn.operatorFirstName,
            location = location,
            isControlledSubstance = isControlledDrugType(drug.drugType, drug.region),
            isHazardousDrug = drug.isHazardous,
            drugCodeLabel = if (regionProvider.current.hl7OrdersAcceptDin) "DIN" else "NDC",
            config = hl7ConfigProvider.current()
        )
        val controlId = txn.hl7MessageControlId?.takeIf { it.isNotBlank() } ?: txnId.toString()
        logger.i("Dispense HL7 message built for txn $txnId | controlId=$controlId | length=${message.length}")
        val result = hl7MessageSender.send(message)
        val ack = result.getOrNull()
        if (result.isSuccess && ack != null && isSuccessAck(ack)) {
            markTransactionSynced(txnId)
            auditLogger.log(Hl7DispenseSent(txnId, controlId, trigger, Hl7SendOutcome.ACCEPTED))
        } else if (result.isSuccess && ack != null && isRejectAck(ack)) {
            // Explicit reject (MSA|AR) — PMS rejected this exact message, not a transport
            // failure. PMS closes its TCP connection right after, which triggers our
            // auto-reconnect; without this, the next auto-reconnect's resend pass would
            // resend this same message and get rejected again, forever. Skip it from
            // automatic resend until the app restarts.
            rejectedTxnIds.add(txnId)
            logger.w("Dispense HL7 send for txnId=$txnId rejected (AR) — excluding from automatic resend: $ack")
            auditLogger.log(Hl7DispenseSent(txnId, controlId, trigger, Hl7SendOutcome.REJECTED))
            auditLogger.log(DispenseSyncAbandoned(txnId, controlId))
        } else {
            logger.w(
                "Dispense HL7 send for txnId=$txnId failed or NAKed: " +
                    "${result.exceptionOrNull()?.message ?: "non-success ACK"} — leaving unsynced for retry"
            )
            auditLogger.log(Hl7DispenseSent(txnId, controlId, trigger, Hl7SendOutcome.FAILED))
        }
    }

    /**
     * Sends one dispense the moment it completes.
     *
     * Until now the only trigger for outbound dispense HL7 was
     * [resendPendingDispenses], which runs when the MLLP client (re)connects. That is a
     * recovery path, not a primary one: a dispense completed while the connection was already
     * up sat unsynced until the socket happened to bounce, and a locally scanned dispense
     * (isComingFromHL7 = 0) never qualified for the resend query at all — the pharmacist
     * finished the count and the PMS never heard about it. This is the primary path; the
     * resend-on-connect sweep remains as retry for sends that fail here.
     *
     * Takes [resendMutex]: the same completion also flips the status, which wakes the resend
     * sweep, and without the lock both paths send the same dispense.
     */
    fun sendDispenseNow(txnId: Long) {
        if (preferenceHelper.isHl7Enabled()) {
            // Marked before the launch, so a resend sweep that wins the lock still logs FIRST_SEND.
            firstSendTxnIds.add(txnId)
            scope.launch {
                try {
                    if (txnId in dispenseTxnDao.getTutorialTxnIds()) {
                        firstSendTxnIds.remove(txnId)
                        logger.i("Tutorial entry — never sending to PMS, txnId=$txnId")
                        return@launch
                    }
                    logger.i("Dispense completed — sending HL7 now, txnId=$txnId")
                    resendMutex.withLock {
                        sendDispense(txnId = txnId, trigger = triggerFor(txnId))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("sendDispenseNow failed for txnId=$txnId", e, event = LogEvent.HL7_SEND_FAILED)
                }
            }
        } else {
            logger.i("HL7 disabled — skipping dispense send, txnId=$txnId")
        }
    }

    /**
     * Resends every pending (unsynced, HL7-originated, completed) transaction's HL7
     * message, one at a time, waiting for each ACK — and for the isSynced write that
     * follows it — before moving to the next.
     * [resendMutex] ensures the Room-flow observer and onClientConnected triggers can
     * never overlap and send the same txn's message twice concurrently.
     */
    fun resendPendingDispenses() {
        scope.launch {
            if (!resendMutex.tryLock()) {
                logger.i("resendPendingDispenses already in progress — skipping duplicate trigger")
                return@launch
            }
            try {
                val pendingTxn = dispenseTxnDao.getPendingHl7TxnOnce()
                    .filterNot { it.txnId in rejectedTxnIds }
                if (pendingTxn.isEmpty()) {
                    logger.i("No pending HL7 transactions to sync")
                    return@launch
                }
                logger.i("Resending ${pendingTxn.size} pending HL7 transactions")
                for (txn in pendingTxn) {
                    sendDispense(txnId = txn.txnId, trigger = triggerFor(txn.txnId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("resendPendingDispenses failed", e, event = LogEvent.HL7_RESEND_FAILED)
            } finally {
                resendMutex.unlock()
            }
        }
    }

    private fun triggerFor(txnId: Long): SendTrigger =
        if (firstSendTxnIds.remove(txnId)) SendTrigger.FIRST_SEND else SendTrigger.AUTO_RESEND

    /**
     * Marks [txnId] synced. Called only on a success ACK for the exact message that
     * was sent for this txn — never from a shared "last sent" slot, so concurrent
     * in-flight sends can't mark the wrong transaction synced.
     */
    private suspend fun markTransactionSynced(txnId: Long) {
        // A synced txn is never resent, so its cached ZUI-8 image encodings can be dropped now.
        DispenseMessageBuilder.evictImageCache(txnId)
        // Flag the txn synced first, then — when the server disallows local storage —
        // delete it. The PMS pulls images from the device image server before sending
        // the success ACK, so the images are already retrieved by the time we delete here.
        // Awaited, not launched: the caller still holds resendMutex, and releasing it before
        // this row lands would let a concurrent send read isSynced = 0 and send a second copy.
        dispenseTxnDao.markTxnSynced(txnId)
        if (!preferenceHelper.isAllowLocalStorage()) {
            scope.launch {
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
            val txn = dispenseTxnDao.getById(txnId)
            val filesToDelete = mutableListOf<String>()
            DispenseBottleJson.decode(txn?.bottlesJson).mapNotNull { it.barcodeImagePath }.forEach { filesToDelete.add(it) }
            filesToDelete.addAll(dispenseTxnDao.getTransactionDetailsImages(txnId))

            // Already gone (e.g. the dashboard cleanup got it first): nothing to purge or log.
            if (dispenseTxnDao.deleteTransaction(txnId) == 0) return
            auditLogger.log(DataPurged(PurgeReason.LOCAL_STORAGE_NOT_ALLOWED, count = 1, txnIds = listOf(txnId), unsyncedIds = emptyList()))

            filesToDelete.forEach { path ->
                val file = java.io.File(path)
                if (file.exists() && !file.delete()) {
                    logger.w("Failed to delete file for synced txn $txnId: $path")
                }
            }
            logger.i("Deleted synced txn $txnId from local storage (allowLocalStorage=false)")
        } catch (e: Exception) {
            logger.e("Failed to delete synced txn $txnId", e, event = LogEvent.DATABASE_ERROR)
        }
    }

    // ─────────────────────────── INBOUND HANDLER: STANDALONE ORDER-PACKET DISPENSE REQUEST ───────────────────────────

    /**
     * Handles dispense requests carried entirely in a single custom order-packet
     * segment (no RXE/ORC present) — e.g. a device-native order packet embedded
     * as a ZNI segment. Field mapping follows the same RXE/ORC
     * flow but is sourced solely from that segment.
     */
    internal suspend fun handleOrderPacketDispenseRequest(message: HL7Message) {
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

        val finalDrug = when (val lookup = drugResolver.resolve(hl7Ndc, expectedNdc = hl7Ndc)) {
            is DrugLookup.Found -> lookup.drug
            is DrugLookup.Failed -> {
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(regionProvider.current.wording(R.string.hl7_notification_drug_not_found_api_failed), hl7Ndc)
                )
                return
            }
            DrugLookup.NoName, DrugLookup.NotFound -> {
                logger.w("No drug name resolved for NDC: $hl7Ndc")
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(regionProvider.current.wording(R.string.hl7_notification_drug_not_found_no_drug), hl7Ndc)
                )
                return
            }
        }

        val drugId = finalDrug.drugId

        logger.i("MSH-10 raw messageControlId (site2) = '${message.header?.messageControlId.orEmpty()}'")

        val txn = DispenseTxnEntity(
            userLocalId = preferenceHelper.getLocalId(),
            drugId = drugId,
            targetCount = targetCount,
            status = CountStatus.PARTIAL,
            isComingFromHL7 = true,
            isSynced = false,
            isNdcVerified = false,
            rxNo = rxNo,
            transactionOrderId = transactionOrderId,
            refillNo = fillNo,
            priority = null,
            hl7MessageControlId = message.header?.messageControlId.orEmpty().takeIf { it.isNotBlank() },
            hl7SequenceNumber = message.header?.sequenceNumber?.takeIf { it.isNotBlank() },
        )

        logger.i("Saving txn (site2) hl7MessageControlId='${txn.hl7MessageControlId}' hl7SequenceNumber='${txn.hl7SequenceNumber}'")

        val txnId = dispenseTxnDao.upsertPreservingId(txn)
        logPacketTxnCreated(txnId, txn, hl7Ndc)
        insertZinContainerDetails(txnId, message)

        val notifBody = if (targetCount != null && targetCount > 0) {
            context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), hl7DrugName, targetCount)
        } else {
            context.getString(R.string.hl7_notification_new_rx_multiple, rxNo.orEmpty(), 1)
        }
        notifier.show(
            title = context.getString(R.string.hl7_notification_new_rx_title),
            message = notifBody,
            dispenseTxnId = txnId,
        )
    }

    // ─────────────────────────── INBOUND HANDLER: ZUI ORDER DATA PACKET DISPENSE REQUEST ───────────────────────────

    /**
     * Handles dispense requests carried entirely in a ZUI order-data-packet segment
     * (PMSS → VIVID, RDE^O11, no RXE/ORC present). Field mapping mirrors
     * [handleOrderPacketDispenseRequest]'s ZNI flow but is sourced from the ZUI
     * "order data packet" accessor group.
     */
    internal suspend fun handleZuiOrderPacketDispenseRequest(message: HL7Message) {
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

        val finalDrug = when (val lookup = drugResolver.resolve(hl7Ndc, expectedNdc = hl7Ndc)) {
            is DrugLookup.Found -> lookup.drug
            is DrugLookup.Failed -> {
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(regionProvider.current.wording(R.string.hl7_notification_drug_not_found_api_failed), hl7Ndc)
                )
                return
            }
            DrugLookup.NoName, DrugLookup.NotFound -> {
                logger.w("No drug name resolved for NDC: $hl7Ndc")
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(regionProvider.current.wording(R.string.hl7_notification_drug_not_found_no_drug), hl7Ndc)
                )
                return
            }
        }

        val drugId = finalDrug.drugId

        logger.i("MSH-10 raw messageControlId (site3/orderPacket) = '${message.header?.messageControlId.orEmpty()}'")

        val txn = DispenseTxnEntity(
            userLocalId = preferenceHelper.getLocalId(),
            drugId = drugId,
            targetCount = targetCount,
            status = CountStatus.PARTIAL,
            isComingFromHL7 = true,
            isSynced = false,
            isNdcVerified = false,
            rxNo = rxNo,
            refillNo = orderPacket.orderFillNumber.takeIf { it.isNotBlank() },
            priority = null,
            hl7MessageControlId = message.header?.messageControlId.orEmpty().takeIf { it.isNotBlank() },
            hl7SequenceNumber = message.header?.sequenceNumber?.takeIf { it.isNotBlank() },
            transactionOrderId = orderPacket.orderTransactionOrderId.takeIf { it.isNotBlank() },
        )

        logger.i("Saving txn (site3) hl7MessageControlId='${txn.hl7MessageControlId}' hl7SequenceNumber='${txn.hl7SequenceNumber}' transactionOrderId='${txn.transactionOrderId}'")

        val txnId = dispenseTxnDao.upsertPreservingId(txn)
        logPacketTxnCreated(txnId, txn, hl7Ndc)
        insertZinContainerDetails(txnId, message)

        val notifBody = if (targetCount != null && targetCount > 0) {
            context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), hl7DrugName, targetCount)
        } else {
            context.getString(R.string.hl7_notification_new_rx_multiple, rxNo.orEmpty(), 1)
        }
        notifier.show(
            title = context.getString(R.string.hl7_notification_new_rx_title),
            message = notifBody,
            dispenseTxnId = txnId,
        )
    }

    // ─────────────────────────── INBOUND HANDLER: ZDR NATIVE DISPENSE REQUEST ───────────────────────────

    /**
     * Native (post-validation) entry point for NW/RF/XO. The caller has already validated the message
     * and ACKed it, so nothing here may influence the ACK. [sequenceNumber] is MSH-13, when present.
     * Orders are identified by [NativeOrderKey.resolve], the same key the validator checked.
     */
    internal suspend fun handleZdrOrder(zdr: ZDRSegment, messageControlId: String?, sequenceNumber: String? = null) {
        val mcid = messageControlId?.takeIf { it.isNotBlank() }
        val orderAction = zdr.orderAction.uppercase()
        val rxNo = zdr.rxNumber.takeIf { it.isNotBlank() }
        val orderKey = NativeOrderKey.resolve(zdr)
        logger.i("ZDR order received | action=$orderAction | rxNo=$rxNo | orderKey=$orderKey | msgId=${mcid.orEmpty()}")
        when (orderAction) {
            "NW", "RF" -> handleZdrDispense(zdr, orderAction == "RF", sequenceNumber, mcid, rxNo)
            "XO" -> handleZdrChangeOrder(zdr)
            else -> logger.w("ZDR: unexpected action='$orderAction' in handleZdrOrder — ignoring")
        }
    }

    /** Native (post-validation) entry point for CA/HD/RL/DC. Returns whether the action changed a row. */
    internal suspend fun handleZdrControl(zdr: ZDRSegment, action: String): Boolean {
        val orderKey = NativeOrderKey.resolve(zdr)
        logger.i("ZDR control received | action=$action | orderKey=$orderKey")
        return when (action.uppercase()) {
            "CA" -> handleZdrCancel(orderKey)
            "HD" -> handleZdrHold(orderKey)
            "RL" -> handleZdrRelease(orderKey)
            "DC" -> handleZdrDiscontinue(orderKey)
            else -> { logger.w("ZDR: unhandled control action='$action' — ignoring"); false }
        }
    }

    /** Legacy message-based entry point; delegates to [handleZdrOrder]. */
    internal suspend fun handleZdrNewOrRefill(message: HL7Message) {
        val zdr = message.segment<ZDRSegment>(ZDRSegment.NAME) ?: run {
            logger.w("handleZdrNewOrRefill: no ZDR segment — ignoring")
            return
        }
        val action = zdr.orderAction.uppercase()
        if (action != "NW" && action != "RF") {
            logger.w("ZDR: unexpected action='$action' in handleZdrNewOrRefill — ignoring")
            return
        }
        handleZdrOrder(
            zdr,
            message.header?.messageControlId,
            message.header?.sequenceNumber?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * CA/HD/RL/DC: runs synchronously so the caller can return NACK when no txn is found.
     * Returns true if the action was applied to at least one row, false if the order was
     * not found — caller should send AE ACK on false.
     */
    internal suspend fun handleZdrStatusChange(message: HL7Message): Boolean {
        val zdr = message.segment<ZDRSegment>(ZDRSegment.NAME) ?: run {
            logger.w("handleZdrStatusChange: no ZDR segment — ignoring")
            return false
        }
        return handleZdrControl(zdr, zdr.orderAction.uppercase())
    }

    /**
     * NW and RF share one path (as on iOS) and act on the single drug the validator guarantees.
     * They differ only in the fill number: NW defaults a blank ZDR-17 to "0", RF derives it from the
     * most recent fill of the Rx (and also enforces refillsAllowed). The transaction is keyed by
     * the resolved order key; a COMPLETED/FORCE_COMPLETED match is treated as absent so a new
     * transaction is created.
     */
    private suspend fun handleZdrDispense(
        zdr: ZDRSegment,
        isRefill: Boolean,
        sequenceNumber: String?,
        messageControlId: String?,
        rxNo: String?,
    ) {
        val label = if (isRefill) "RF" else "NW"
        val drug = zdr.drugs.first()
        val ndc = drug.ndc.trim()
        val targetCount = zdr.quantityToDispense.trim().toDoubleOrNull()?.toInt()

        val fillNo = zdr.fillNumber.takeIf { it.isNotBlank() } ?: when {
            !isRefill -> "0"
            else -> {
                val mostRecent = if (rxNo != null) dispenseTxnDao.getMostRecentByRxNo(rxNo) else null
                ((mostRecent?.refillNo?.toIntOrNull() ?: 0) + 1).toString()
            }
        }

        // When ZDR-15 is blank the key falls back to rx+fill, but the caller resolved it before
        // [fillNo] was derived — so every refill on an Rx with a blank ZDR-17 would collapse to
        // "rx-0" and the second refill would overwrite the first in place, losing a dispense.
        // Re-derive from the resolved fill. An explicit ZDR-15 still wins.
        val resolvedKey = zdr.orderReferenceNumber.takeIf { it.isNotBlank() }
            ?: NativeOrderKey.compose(rxNo.orEmpty(), fillNo)

        if (isRefill) {
            val refillsAllowed = zdr.refillsAllowed.trim().toIntOrNull()
            if (refillsAllowed != null && refillsAllowed <= 0) {
                logger.w("ZDR RF: refills exhausted for rxNo=$rxNo (refillsAllowed=$refillsAllowed) — notifying")
                notifier.show(
                    title = context.getString(R.string.hl7_notification_new_rx_title),
                    message = context.getString(R.string.hl7_notification_refill_exhausted, rxNo.orEmpty(), refillsAllowed),
                )
                return
            }
        }

        val existing = dispenseTxnDao.getByTransactionOrderId(resolvedKey)?.takeUnless { it.isZdrFinished() }

        val finalDrug = zdrResolveDrug(ndc, drug.name) ?: return
        val drugId = drugMasterDao.upsertPreservingId(finalDrug)
        val priority = Hl7Parser.mapPriority(zdr.priority)

        val txnId: Long
        if (existing != null) {
            dispenseTxnDao.updateFromHl7Edit(
                txnId = existing.txnId,
                drugId = drugId,
                targetCount = targetCount,
                priority = priority,
                status = null,
            )
            txnId = existing.txnId
            logger.i("ZDR $label: updated existing txnId=$txnId orderKey=$resolvedKey ndc=$ndc")
        } else {
            val txn = DispenseTxnEntity(
                userLocalId = preferenceHelper.getLocalId(),
                drugId = drugId,
                targetCount = targetCount,
                status = CountStatus.PARTIAL,
                isComingFromHL7 = true,
                isSynced = false,
                isNdcVerified = false,
                rxNo = rxNo,
                refillNo = fillNo,
                transactionOrderId = resolvedKey,
                priority = priority,
                hl7MessageControlId = messageControlId,
                hl7SequenceNumber = sequenceNumber,
            )
            txnId = dispenseTxnDao.upsertPreservingId(txn)
            logger.i("ZDR $label: created txnId=$txnId hl7MessageControlId=$messageControlId orderKey=$resolvedKey ndc=$ndc")
            logPacketTxnCreated(txnId, txn, ndc)
        }

        notifier.show(
            title = context.getString(R.string.hl7_notification_new_rx_title),
            message = context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), drug.name, targetCount ?: 0),
            dispenseTxnId = txnId,
        )
    }

    internal suspend fun handleZdrChangeOrder(message: HL7Message): Boolean {
        val zdr = message.segment<ZDRSegment>(ZDRSegment.NAME) ?: run {
            logger.w("ZDR XO: no ZDR segment in message")
            return false
        }
        return handleZdrChangeOrder(zdr)
    }

    private suspend fun handleZdrChangeOrder(zdr: ZDRSegment): Boolean {
        // Must use the same resolver the validator used: it accepted this XO against the
        // resolved key (ZDR-15, else rx-fill), so matching on raw ZDR-15 here would ACK an
        // AA and then silently apply nothing whenever ZDR-15 is blank.
        val orderId = NativeOrderKey.resolve(zdr)
        val existing = dispenseTxnDao.getByTransactionOrderId(orderId) ?: run {
            logger.w("ZDR XO: no txn found for transactionOrderId=$orderId")
            return false
        }
        val newQty = zdr.quantityToDispense.trim().toDoubleOrNull()?.toInt()
        if (newQty == null || newQty <= 0) {
            logger.w("ZDR XO: invalid quantity='${zdr.quantityToDispense}' for orderId=$orderId")
            return false
        }
        val ndc = zdr.drugs.firstOrNull()?.ndc?.trim()
        if (ndc.isNullOrBlank()) {
            logger.w("ZDR XO: blank NDC for orderId=$orderId")
            return false
        }
        val finalDrug = zdrResolveDrug(ndc, zdr.drugs.firstOrNull()?.name) ?: return false
        val drugId = drugMasterDao.upsertPreservingId(finalDrug)
        dispenseTxnDao.updateFromHl7Edit(
            txnId = existing.txnId,
            drugId = drugId,
            targetCount = newQty,
            priority = Hl7Parser.mapPriority(zdr.priority),
            status = null,
        )
        logger.i("ZDR XO: updated txnId=${existing.txnId} orderId=$orderId qty=$newQty ndc=$ndc")
        return true
    }

    private suspend fun handleZdrCancel(orderId: String): Boolean {
        logger.i("ZDR CA for orderId=$orderId — cancelling active txns")
        val cancelled = dispenseTxnDao.cancelActiveTxnsByOrderId(orderId)
        if (cancelled.isEmpty()) {
            logger.w("ZDR CA: no active txns found for orderId=$orderId")
            return false
        }
        auditLogger.log(OrderCancelled(txnIds = cancelled))
        return true
    }

    private suspend fun handleZdrHold(orderId: String): Boolean {
        logger.i("ZDR HD for orderId=$orderId — marking ON_HOLD")
        val rowsChanged = dispenseTxnDao.updateStatusByOrderIdIfPartial(orderId, CountStatus.ON_HOLD)
        if (rowsChanged == 0) {
            logger.w("ZDR HD: no active PARTIAL txns found for orderId=$orderId")
            return false
        }
        auditLogger.log(OrderHeld(orderId = orderId, rowsChanged = rowsChanged))
        return true
    }

    private suspend fun handleZdrRelease(orderId: String): Boolean {
        logger.i("ZDR RL for orderId=$orderId — restoring to PARTIAL")
        val oldStatus = dispenseTxnDao.getActiveStatusByOrderId(orderId)
        val rowsChanged = dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold(orderId, CountStatus.PARTIAL)
        if (rowsChanged == 0) {
            logger.w("ZDR RL: no active txns found for orderId=$orderId")
            return false
        }
        auditLogger.log(OrderReleased(orderId = orderId, oldStatus = oldStatus?.name, rowsChanged = rowsChanged))
        return true
    }

    private suspend fun handleZdrDiscontinue(orderId: String): Boolean {
        logger.i("ZDR DC for orderId=$orderId — soft-deleting (discontinued)")
        val rowsChanged = dispenseTxnDao.softDeleteByOrderId(orderId)
        if (rowsChanged == 0) {
            logger.w("ZDR DC: no active txns found for orderId=$orderId")
            return false
        }
        auditLogger.log(OrderDiscontinued(orderId = orderId, rowsChanged = rowsChanged))
        return true
    }

    private fun DispenseTxnEntity.isZdrFinished() =
        status == CountStatus.COMPLETED || status == CountStatus.FORCE_COMPLETED

    private suspend fun zdrResolveDrug(ndc: String, drugName: String?): DrugMasterEntity? {
        val name = drugName?.takeIf { it.isNotBlank() }
        return when (val lookup = drugResolver.resolve(ndc, expectedNdc = ndc, nameIfMissing = name, nameIfUnknown = name)) {
            is DrugLookup.Found -> lookup.drug
            is DrugLookup.Failed -> {
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(regionProvider.current.wording(R.string.hl7_notification_drug_not_found_api_failed), ndc),
                )
                null
            }
            DrugLookup.NoName, DrugLookup.NotFound -> {
                logger.w("ZDR: no drug found for NDC=$ndc")
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(regionProvider.current.wording(R.string.hl7_notification_drug_not_found_no_drug), ndc),
                )
                null
            }
        }
    }

    // ZNI / ZUI packets always insert a new order (txnId 0 upserts as an insert).
    private fun logPacketTxnCreated(txnId: Long, txn: DispenseTxnEntity, ndc: String) {
        auditLogger.log(
            DispenseTxnCreated(
                txnId = txnId,
                source = TxnSource.PMS,
                messageId = txn.hl7MessageControlId,
                ndc = ndc,
                targetQty = txn.targetCount,
                priority = txn.priority?.name
            )
        )
    }

    /**
     * Inserts ZIN|...|EXPECTED_ON_HAND|<count> detail rows into the transaction.
     * Uses the new [ZINSegment] typed segment from the HL7 library AAR.
     */
    internal suspend fun insertZinContainerDetails(txnId: Long, message: HL7Message) {
        val zinSegments = message.segments<ZINSegment>(ZINSegment.NAME)
            .filter { it.dispenseType == "EXPECTED_ON_HAND" }

        var inserted = false
        for (segment in zinSegments) {
            val pillCount = segment.quantity.toIntOrNull() ?: continue
            if (pillCount == 0) {
                logger.i("ZIN EXPECTED_ON_HAND: skipping detail insert for txnId=$txnId because pillCount=0")
                continue
            }
            dispenseCountDao.insert(
                DispenseCountEntity(
                    txnId = txnId,
                    pillCount = pillCount,
                    step = StepState.CONTAINER_INITIATE.name,
                    isManual = false
                )
            )
            logger.i("ZIN EXPECTED_ON_HAND: inserted detail txnId=$txnId pillCount=$pillCount")
            inserted = true
        }
        if (inserted) {
            dispenseTxnDao.updateWorkflowStep(txnId, StepState.TARGET_VERIFICATION.name)
            logger.i("ZIN: workflowStep updated to TARGET_VERIFICATION for txnId=$txnId")
        }
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
     * - [DispenseTxnEntity.drugId]      — resolved from the incoming NDC
     * - [DispenseTxnEntity.targetCount] — from RXE quantity
     * - [DispenseTxnEntity.isSynced]    — reset to false so the updated result is re-sent
     */
    internal suspend fun handleOrderEdit(message: HL7Message) {
        val orc = message.segment<ORCSegment>(ORCSegment.NAME) ?: return
        val rxNo = orc.fillerOrderNumber.takeIf { it.isNotBlank() }
            ?: orc.placerOrderNumber.takeIf { it.isNotBlank() } ?: return
        val rxe = message.segment<RXESegment>(RXESegment.NAME) ?: return

        val orderStatusRaw = orc.orderStatus?.uppercase().orEmpty()

        logger.i("Received ORC|XO for rxNo=$rxNo orderStatus=$orderStatusRaw — looking up existing transaction")

        // 1. Locate the active transaction; if soft-deleted, restore it so the edit can be applied
        var existingTxn = dispenseTxnDao.getActiveByRxNo(rxNo)
        var restored = false
        if (existingTxn == null) {
            val deletedTxn = dispenseTxnDao.getDeletedByRxNo(rxNo)
            if (deletedTxn != null) {
                logger.i("ORC|XO: restoring deleted txnId=${deletedTxn.txnId} for rxNo=$rxNo before applying status=$orderStatusRaw")
                dispenseTxnDao.restoreDeletedTxn(deletedTxn.txnId)
                existingTxn = deletedTxn.copy(isDeleted = false, status = CountStatus.PARTIAL)
                restored = true
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

        val resolvedDrug = when (val lookup = drugResolver.resolve(hl7Ndc, expectedNdc = hl7Ndc)) {
            is DrugLookup.Found -> lookup.drug
            else -> {
                logger.w("ORC|XO: drug not resolved for NDC=$hl7Ndc → $lookup — aborting edit")
                notifier.show(
                    title = context.getString(R.string.hl7_notification_edit_rx_title),
                    message = context.getString(
                        regionProvider.current.wording(R.string.hl7_notification_edit_rx_drug_not_found),
                        rxNo,
                        hl7Ndc
                    )
                )
                return
            }
        }

        val newDrugId = resolvedDrug.drugId

        // 4. Map order status and apply the edit
        val newStatus = mapHl7OrderStatus(orderStatusRaw)

        dispenseTxnDao.updateFromHl7Edit(
            txnId = existingTxn.txnId,
            drugId = newDrugId,
            targetCount = newTargetCount,
            priority = null,
            status = newStatus
        )

        logger.i(
            "ORC|XO applied: txnId=${existingTxn.txnId}, rxNo=$rxNo, " +
                "drugId=$newDrugId, targetCount=$newTargetCount, priority=null, status=$orderStatusRaw"
        )
        // Mirrors updateFromHl7Edit: a null status keeps the old one.
        val orderChanges = listOf(
            FieldChange("drug_id", existingTxn.drugId?.toString(), newDrugId.toString()),
            FieldChange("target_qty", existingTxn.targetCount?.toString(), newTargetCount?.toString()),
            FieldChange("priority", existingTxn.priority?.name, null),
            FieldChange("status", existingTxn.status.name, (newStatus ?: existingTxn.status).name)
        ).filter { it.old != it.new }
        // An identical XO resend changes nothing, so it isn't logged.
        if (orderChanges.isNotEmpty() || restored) {
            auditLogger.log(OrderEdited(txnId = existingTxn.txnId, changes = orderChanges, restored = restored))
        }

        // 5. If status is CA, soft-delete after applying the edit
        if (orderStatusRaw == "CA") {
            logger.i("ORC|XO with status=CA — soft-deleting txnId=${existingTxn.txnId} after update")
            val cancelledTxnIds = dispenseTxnDao.cancelActiveTxnsByRxNo(rxNo)
            if (cancelledTxnIds.isNotEmpty()) auditLogger.log(OrderCancelled(txnIds = cancelledTxnIds))
            return
        }

        // The tap opens the edited order, unless the edit left it held or finished.
        val statusAfterEdit = newStatus ?: existingTxn.status
        notifier.show(
            title = context.getString(R.string.hl7_notification_edit_rx_title),
            message = context.getString(
                R.string.hl7_notification_edit_rx_updated,
                rxNo,
                resolvedDrug.drugName ?: hl7DrugName,
                newTargetCount ?: 0
            ),
            dispenseTxnId = existingTxn.txnId.takeIf { statusAfterEdit == CountStatus.PARTIAL },
        )
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    /**
     * Maps an HL7 ORC-5 order status code to the app's [CountStatus].
     * IP = in-progress/resume → PARTIAL
     * CM = complete          → COMPLETED
     * HD = on-hold           → ON_HOLD
     * CA is handled via soft-delete in [handleOrderEdit]; returns null here.
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
            dispenseTxnDao.observePendingHl7Txn()
                .catch { e -> logger.e("Pending HL7 txn observer flow failed", e, event = LogEvent.HL7_RESEND_FAILED) }
                .collect { pendingTxn ->
                    logger.i("HL7 observer fired, pending=${pendingTxn.size}")
                    // Checked per emission, not once at start: HL7 can be turned on after a cold start.
                    if (preferenceHelper.isHl7Enabled()) {
                        resendPendingDispenses()
                    }
                }
        }
    }
}
