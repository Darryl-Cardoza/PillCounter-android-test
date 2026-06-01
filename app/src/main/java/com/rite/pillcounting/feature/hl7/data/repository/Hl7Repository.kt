package com.rite.pillcounting.feature.hl7.data.repository

import android.annotation.SuppressLint
import android.content.Context
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dispenseFlow.data.DrugRepository
import com.rite.pillcounting.feature.dispenseFlow.domain.model.GetNdcRequestModel
import com.rite.pillcounting.feature.hl7.core.Hl7MessageSender
import com.rite.pillcounting.feature.hl7.domain.model.MessageType
import com.rite.pillcounting.feature.hl7.notification.Hl7Notifier
import com.rite.pillcounting.feature.hl7.util.HL7MessageBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.rite.hl7.domain.model.CompleteHL7Message
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
    private val userDao: UserDao,
    private val batchDao: BatchDao,
    private val hl7MessageSender: Hl7MessageSender,
    private val drugRepository: DrugRepository,
    private val notifier: Hl7Notifier
) {

    private val logger = AppLogger.create<Hl7Repository>()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    init {
        scope.launch {
            if (preferenceHelper.isHl7Enabled()) {
                observePendingHl7Transactions()
                observePendingHl7BatchTransactions()
            }
        }
    }


    fun handleReceivedMessage(message: CompleteHL7Message) {
        val inboundType = classifyInboundMessage(message) ?: return
        scope.launch {
            when (inboundType) {
                MessageType.DISPENSE_REQUEST ->
                    handleRdeDispenseRequest(message)

                MessageType.EDIT_DISPENSE_REQUEST ->
                    handleOrderEdit(message)

                MessageType.INVENTORY_REQUEST ->
                    handleInrInventoryRequest(message)

                MessageType.CANCEL_ORDER ->
                    handleOrderCancellation(message)
            }
        }
    }


    @SuppressLint("SimpleDateFormat")
    suspend fun buildAndSendSuccessfulDispense(
        txnId: Long
    ) {
        val txn = txnDao.getById(txnId)
            ?: return
        val txnDetails = txnDetailsDao.getAllForTxn(txnId.toString())
        //Change this condition because we have transaction status that we are handling from pms
//        val totalCount = txnDetails.sumOf { it.pillCount ?: 0 }
//        if (totalCount == 0) {
//            return
//        }
        val user = userDao.getByUserId(txn.localId?.toString().orEmpty())
        val location = locationProvider.getCurrentLocationAsString()

        val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }
            ?: return

        val message = HL7MessageBuilder.buildDispenseMessage(
            txn = txn,
            txnDetails = txnDetails,
            drugCode = drug.ndc,
            drugName = drug.drugName ?: "",
            pharmacistId = user?.userId,
            pharmacistName = listOfNotNull(user?.fName, user?.lName)
                .joinToString(" "),
            location = location
        )
        logger.i("HL7dispence message tooooooo" + message)
        hl7MessageSender.send(message)
    }

    /**
     * Build and send inventory response
     */
    suspend fun buildAndSendInventoryResponse(batchId: Long) {
        try {
            logger.i("buildAndSendInventoryResponse started, batchId=$batchId")

            val batch = batchDao.getById(batchId)
            if (batch == null) {
                logger.w("No batch found for batchId=$batchId")
                return
            }

            val txns = txnDao.getTxnsByBatchId(batchId)
            logger.i("txns count = ${txns.size}, txns = $txns")

            val message = HL7MessageBuilder.buildInventoryMessage(batch = batch, txns = txns)
            logger.i("HL7 inventory message built:\n$message")

            val result = hl7MessageSender.sendRaw(message)
            if (result.isSuccess) {
                logger.i("Inventory HL7 message sent successfully for batchId=$batchId")
                batchDao.markBatchSynced(batchId)
            } else {
                logger.e("Failed to send inventory HL7 message for batchId=$batchId: ${result.exceptionOrNull()?.message}")
            }

        } catch (e: Exception) {
            logger.e("buildAndSendInventoryResponse failed for batchId=$batchId", e)
        }
    }

    fun resendPendingHl7Transactions() {
        scope.launch {
            val pendingTxn = pillCountTxnDao.getPendingHl7TxnOnce()
            if (pendingTxn.isEmpty()) {
                logger.i("No pending HL7 transactions to sync")
                return@launch
            }
            logger.i("Resending ${pendingTxn.size} pending HL7 transactions")
            for (txn in pendingTxn) {
                preferenceHelper.saveSentMessageTxnId(txn.txnId)
                when (txn.countType) {
                    CountType.FIXED -> {
                        //Change this condition because we have transaction status that we are handling from pms
//                        if (txn.targetCount != null) {
                            buildAndSendSuccessfulDispense(txnId = txn.txnId)
//                        }
                    }
                    CountType.REGULAR -> {
                        val batchId = txn.batchId ?: continue
                        buildAndSendInventoryResponse(batchId = batchId)
                    }
                }
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

    fun markTransactionSynced() {
        scope.launch {
            val txnId = preferenceHelper.getSentMessageTxnId()
            pillCountTxnDao.markTxnSynced(txnId)
        }
    }

    private suspend fun handleRdeDispenseRequest(
        message: CompleteHL7Message
    ) {
        val medication = message.medications.first()

        val hl7Ndc = medication.drugCode.trim()
        val hl7DrugName = medication.drugName
        val targetCount = medication.requestedQty?.toIntOrNull()
        val rxNo = message.order?.placerOrderId


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
                )
            }
        }

        val drugId = finalDrug?.let { drugMasterDao.upsertPreservingId(it) }

        val priority = TxnPriority.fromString(
            message.customSegments
                .firstOrNull { it.segmentType == "ZPR" && it.field2 == "PRIORITY" }
                ?.field3
        )

        val txnStatus = mapHl7OrderStatus(message.order?.orderStatus) ?: CountStatus.PARTIAL

        val txn = PillCountTxnEntity(
            localId = preferenceHelper.getLocalId(),
            drugId = drugId,
            countType = CountType.FIXED,
            targetCount = targetCount,
            status = txnStatus,
            isComingFromHL7 = true,
            isSynced = false,
            isNdcVerified = false,
            rxNo = rxNo,
            priority = priority
        )

        val txnId = pillCountTxnDao.upsertPreservingId(txn)
        preferenceHelper.saveTxnId(txnId)

        val meds = message.medications
        val notifBody = if (meds.size == 1) {
            val med = meds[0]
            val qty = med.requestedQty?.toIntOrNull() ?: 0
            context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), med.drugName ?: "", qty)
        } else {
            context.getString(R.string.hl7_notification_new_rx_multiple, rxNo.orEmpty(), meds.size)
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

    private suspend fun handleInrInventoryRequest(
        message: CompleteHL7Message
    ) {
        logger.i("Handling INR Inventory Request with message: $message")
        val inventoryItems = message.medications
        if (inventoryItems.isEmpty()) {
            logger.w("No inventory items found in HL7 message")
            return
        }

        val resolvedItems = mutableListOf<ResolvedInventoryItem>()

        for (inv in inventoryItems) {
            val ndc = inv.drugCode?.trim().orEmpty()
            val lot = ""
            val expiry = ""
            val targetCount = 0

            if (ndc.isBlank()) {
                logger.w("Skipping inventory item because NDC is missing: $inv")
                continue
            }

            // Local DB check
            val existingDrug = drugMasterDao.getDrugByNdc(ndc)
            if (existingDrug != null) {
                val localName = existingDrug.drugName
                if (!localName.isNullOrBlank()) {
                    resolvedItems.add(
                        ResolvedInventoryItem(
                            ndc = ndc,
                            drugId = existingDrug.drugId,
                            resolvedName = localName,
                            lot = lot,
                            expiry = expiry,
                            targetCount = targetCount
                        )
                    )
                    logger.i("Drug found in local DB for NDC: $ndc")
                } else {
                    logger.w("Drug found in local DB but drugName is empty for NDC: $ndc")
                }
                continue
            }

            // API fallback
            val request = GetNdcRequestModel(
                target_ndc = ndc,
                scanned_ndc = ndc
            )

            try {
                logger.i("Drug not found locally for NDC: $ndc, calling API")

                val drugInfo = drugRepository.getDrugInfoByNdc(request)

                val resolvedDrugName = drugInfo?.genericName?.takeIf { it.isNotBlank() }
                    ?: inv.drugName?.takeIf { it.isNotBlank() }

                if (resolvedDrugName.isNullOrBlank()) {
                    logger.w("Skipping inventory item because API returned no usable drug name for NDC: $ndc")
                    continue
                }

                val drugEntity = DrugMasterEntity(
                    ndc = drugInfo?.ndc?.takeIf { it.isNotBlank() } ?: ndc,
                    drugName = resolvedDrugName,
                    drugType = drugInfo?.drugType,
                    packageQty = drugInfo?.qty,
                    isHazardous = drugInfo?.isHazardous ?: false,
                )

                val newDrugId = drugMasterDao.upsertPreservingId(drugEntity)

                resolvedItems.add(
                    ResolvedInventoryItem(
                        ndc = ndc,
                        drugId = newDrugId,
                        resolvedName = resolvedDrugName,
                        lot = lot,
                        expiry = expiry,
                        targetCount = targetCount
                    )
                )

                logger.i("Drug resolved from API and saved locally for NDC: $ndc")
            } catch (e: Exception) {
                logger.e("Failed to fetch drug info from API for NDC: $ndc", e)
                continue
            }
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
            requestIdFromPMS = message.header.messageControlId
        )
        val batchId = batchDao.insert(batch)

        for (item in resolvedItems) {
            val txn = PillCountTxnEntity(
                localId = preferenceHelper.getLocalId(),
                drugId = item.drugId,
                countType = CountType.REGULAR,
                targetCount = item.targetCount,
                status = CountStatus.PARTIAL,
                isComingFromHL7 = true,
                isSynced = false,
                isNdcVerified = false,
                batchId = batchId
            )

            val txnId = pillCountTxnDao.upsertPreservingId(txn)

            logger.i(
                "Inserted transaction for NDC: ${item.ndc}, drugId: ${item.drugId}, txnId: $txnId, lotNo: ${item.lot}, expiry: ${item.expiry}, targetCount: ${item.targetCount}"
            )
        }

        logger.i("Processed ${resolvedItems.size} inventory items for batchId: $batchId")

        notifier.show(
            title = context.getString(R.string.hl7_notification_inventory_title),
            message = context.getString(R.string.hl7_notification_inventory_items_count, resolvedItems.size)
        )
    }

    private fun classifyInboundMessage(
        message: CompleteHL7Message
    ): MessageType? {
        return when {
            // ORC|CA  — cancel an existing order
            message.order?.orderControl == "CA" &&
                    !message.order?.placerOrderId.isNullOrBlank() ->
                MessageType.CANCEL_ORDER

            // ORC|XO  — change/edit an existing dispense order
            message.messageType == "RDE" &&
                    message.triggerEvent == "O11" &&
                    message.order?.orderControl == "XO" &&
                    !message.order?.placerOrderId.isNullOrBlank() &&
                    message.medications.isNotEmpty() ->
                MessageType.EDIT_DISPENSE_REQUEST

            // ORC|NW (or any other control) — new dispense request
            message.messageType == "RDE" &&
                    message.triggerEvent == "O11" &&
                    message.medications.isNotEmpty() ->
                MessageType.DISPENSE_REQUEST

            message.messageType == "INR" &&
                    message.triggerEvent == "U04" &&
                    message.medications.isNotEmpty() ->
                MessageType.INVENTORY_REQUEST

            else -> null
        }
    }

    private suspend fun handleOrderCancellation(message: CompleteHL7Message) {
        val rxNo = message.order?.placerOrderId ?: return
        logger.i("Received ORC|CA for rxNo=$rxNo — soft-deleting transaction")
        pillCountTxnDao.softDeleteByRxNo(rxNo)
        logger.i("Transaction with rxNo=$rxNo marked as deleted")
    }

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
    private suspend fun handleOrderEdit(message: CompleteHL7Message) {
        val rxNo = message.order?.placerOrderId ?: return
        val medication = message.medications.firstOrNull() ?: return

        val orderStatusRaw = message.order?.orderStatus?.uppercase()

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
        val hl7Ndc = medication.drugCode.trim()
        val hl7DrugName = medication.drugName
        val newTargetCount = medication.requestedQty?.toIntOrNull()

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
                DrugMasterEntity(
                    ndc = drugInfo?.ndc?.takeIf { it.isNotBlank() } ?: hl7Ndc,
                    drugName = resolvedName,
                    drugType = drugInfo?.drugType,
                    isHazardous = drugInfo?.isHazardous ?: false,
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
            message.customSegments
                .firstOrNull { it.segmentType == "ZPR" && it.field2 == "PRIORITY" }
                ?.field3
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
                resolvedDrug?.drugName ?: hl7DrugName ?: hl7Ndc,
                newTargetCount ?: 0
            )
        )
    }

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

//                    val hasPendingNow = pendingTxn.isNotEmpty()
//                    if (hasPendingNow) {
//                        logger.i("Pending HL7 txn detected, initiating connection")
//                        hl7MessageSender.connect()
                    resendPendingHl7Transactions()
//                    }
                }
        }
    }

    private fun observePendingHl7BatchTransactions() {
        scope.launch {
            batchDao.observeUnsyncedCompletedBatches()
                .collect { pendingBatches ->
                    logger.i("HL7 batch observer fired, pending=${pendingBatches.size}")
                    resendPendingHl7BatchTransactions()
                }
        }
    }
}