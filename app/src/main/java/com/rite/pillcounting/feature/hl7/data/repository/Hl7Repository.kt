package com.rite.pillcounting.feature.hl7.data.repository

import android.annotation.SuppressLint
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
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.barcodeScan.data.DrugRepository
import com.rite.pillcounting.feature.barcodeScan.domain.model.GetNdcRequestModel
import com.rite.pillcounting.feature.hl7.core.Hl7MessageSender
import com.rite.pillcounting.feature.hl7.domain.model.MessageType
import com.rite.pillcounting.feature.hl7.util.HL7MessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.rite.hl7.hl7.domain.model.CompleteHL7Message
import java.sql.Types.NULL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Hl7Repository @Inject constructor(
    private val drugMasterDao: DrugMasterDao,
    private val locationProvider: LocationProvider,
    private val txnDao: PillCountTxnDao,
    private val txnDetailsDao: PillCountTxnDetailsDao,
    private val preferenceHelper: PreferenceHelper,
    private val pillCountTxnDao: PillCountTxnDao,
    private val userDao: UserDao,
    private val batchDao: BatchDao,
    private val hl7MessageSender: Hl7MessageSender,
    private val drugRepository: DrugRepository
) {

    private val logger = AppLogger.create<Hl7Repository>()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    init {
        scope.launch {
            if (preferenceHelper.isHl7Enabled()) {
                observePendingHl7Transactions()
            }
        }
    }


    fun handleReceivedMessage(message: CompleteHL7Message) {
        val inboundType = classifyInboundMessage(message) ?: return
        scope.launch {
            when (inboundType) {
                MessageType.DISPENSE_REQUEST ->
                    handleRdeDispenseRequest(message)

                MessageType.INVENTORY_REQUEST ->
                    handleInrInventoryRequest(message)
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
        val totalCount = txnDetails.sumOf { it.pillCount ?: 0 }
        if (totalCount == 0) {
            return
        }
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
                        if (txn.targetCount != null) {
                            buildAndSendSuccessfulDispense(txnId = txn.txnId)
                        }
                    }
                    CountType.REGULAR -> {
                        val batchId = txn.batchId ?: continue
                        buildAndSendInventoryResponse(batchId = batchId)
                    }
                }
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
                null
            }

            val resolvedNdc = drugInfo?.ndc?.takeIf { it.isNotBlank() } ?: hl7Ndc
            val resolvedDrugName =
                drugInfo?.genericName?.takeIf { it.isNotBlank() } ?: hl7DrugName
            val resolvedDrugType = drugInfo?.drugType
            val resolvedEquivalence = drugInfo?.is_ndc_equivalent?.toString()

            DrugMasterEntity(
                ndc = resolvedNdc,
                drugName = resolvedDrugName,
                drugType = resolvedDrugType,
                equivalence = resolvedEquivalence
            )
        }

        val drugId = drugMasterDao.upsertPreservingId(finalDrug)

        val txn = PillCountTxnEntity(
            localId = preferenceHelper.getLocalId(),
            drugId = drugId,
            countType = CountType.FIXED,
            targetCount = targetCount,
            status = CountStatus.PARTIAL,
            isComingFromHL7 = true,
            isSynced = false,
            isNdcVerified = false,
            rxNo = rxNo
        )

        val txnId = pillCountTxnDao.upsertPreservingId(txn)
        preferenceHelper.saveTxnId(txnId)
    }

//    private suspend fun handleInrInventoryRequest(
//        message: CompleteHL7Message
//    ) {
//        val inv = message.inventoryItems.firstOrNull() ?: return
//
//        val ndc = inv.substanceCode ?: return
//        val drugName = inv.substanceDescription ?: "Unknown Drug"
//        val lotNo = inv.lotNumber ?: ""
//        val expiry = inv.expirationDateTime ?: ""
//
//        val localDrug = drugMasterDao.getDrugByNdc(ndc)
//
//        val finalDrug = if (localDrug != null) {
//            logger.i("Drug found in local DB for NDC: $ndc")
//            localDrug
//        } else {
//            logger.i("Drug not found locally for NDC: $ndc, calling API")
//
//            val request = GetNdcRequestModel(
//                target_ndc = ndc,
//                scanned_ndc = ndc
//            )
//
//            val drugInfo = try {
//                drugRepository.getDrugInfoByNdc(request)
//            } catch (e: Exception) {
//                logger.e("Failed to fetch drug info from API for NDC: $ndc", e)
//                null
//            }
//
//            val resolvedNdc = drugInfo?.ndc?.takeIf { it.isNotBlank() } ?: ndc
//            val resolvedDrugName =
//                drugInfo?.genericName?.takeIf { it.isNotBlank() } ?: drugName
//            val resolvedDrugType = drugInfo?.drugType
//            val resolvedEquivalence = drugInfo?.is_ndc_equivalent?.toString()
//
//            DrugMasterEntity(
//                ndc = resolvedNdc,
//                drugName = resolvedDrugName,
//                drugType = resolvedDrugType,
//                equivalence = resolvedEquivalence
//            )
//        }
//        val drugId = drugMasterDao.upsertPreservingId(finalDrug)
//
//        val batch = BatchEntity(
//            batchId = System.currentTimeMillis(),
//            startDateTime = System.currentTimeMillis(),
//            endDateTime = null, // Will be set when batch is completed
//            status = BatchStatus.INPROGRESS,
//            isDeleted = false,
//            note = null, // Can be set later by user
//            bucketId = "", // Can be set later
//            createdAt = System.currentTimeMillis(),
//            updatedAt = System.currentTimeMillis()
//        )
//        val batchId = batchDao.insert(batch)
//
//
//        val txn = PillCountTxnEntity(
//            localId = preferenceHelper.getLocalId(),
//            drugId = drugId,
//            countType = CountType.REGULAR,
//            targetCount = null,
//            status = CountStatus.PARTIAL,
//            isComingFromHL7 = true,
//            isSynced = false,
//            isNdcVerified = false,
//            batchId = batchId
//        )
//
//        logger.i("Received message $txn")
    //        val txnId = pillCountTxnDao.upsertPreservingId(txn)
//        preferenceHelper.saveTxnId(txnId)
//    }

//    private data class ResolvedInventoryItem(
//        val inventoryItem: InventoryItemData,
//        val drug: DrugMasterEntity
//    )
//
//
//    private suspend fun handleInrInventoryRequest(
//        message: CompleteHL7Message
//    ) {
//        val inventoryItems = message.inventoryItems
//        if (inventoryItems.isEmpty()) {
//            logger.w("No inventory items found in HL7 message")
//            return
//        }
//
//        val resolvedItems = mutableListOf<ResolvedInventoryItem>()
//        val resolvedDrugCache = mutableMapOf<String, DrugMasterEntity>()
//
//        inventoryItems.forEach { inv ->
//            val ndc = inv.substanceStatusCode?.trim()
//            if (ndc.isNullOrBlank()) {
//                logger.w("Skipping inventory item because NDC is missing: $inv")
//                return@forEach
//            }
//
//            val cachedDrug = resolvedDrugCache[ndc]
//            if (cachedDrug != null) {
//                resolvedItems.add(
//                    ResolvedInventoryItem(
//                        inventoryItem = inv,
//                        drug = cachedDrug
//                    )
//                )
//                return@forEach
//            }
//
//            val fallbackDrugName = inv.substanceDescription?.trim().takeUnless { it.isNullOrBlank() }
//                ?: "Unknown Drug"
//
//            val localDrug = drugMasterDao.getDrugByNdc(ndc)
//
//            val finalDrug = if (localDrug != null) {
//                logger.i("Drug found in local DB for NDC: $ndc")
//                localDrug
//            } else {
//                logger.i("Drug not found locally for NDC: $ndc, calling API")
//
//                val request = GetNdcRequestModel(
//                    target_ndc = ndc,
//                    scanned_ndc = ndc
//                )
//
//                val drugInfo = try {
//                    drugRepository.getDrugInfoByNdc(request)
//                } catch (e: Exception) {
//                    logger.e("Failed to fetch drug info from API for NDC: $ndc", e)
//                    null
//                }
//
//                val isValidDrugInfo = drugInfo != null &&
//                        (
//                                !drugInfo.ndc.isNullOrBlank() ||
//                                        !drugInfo.genericName.isNullOrBlank()
//                                )
//
//                if (!isValidDrugInfo) {
//                    logger.w("Skipping inventory item because drug was not found in local DB or API for NDC: $ndc")
//                    null
//                } else {
//                    DrugMasterEntity(
//                        ndc = drugInfo?.ndc?.takeIf { it.isNotBlank() } ?: ndc,
//                        drugName = drugInfo?.genericName?.takeIf { it.isNotBlank() } ?: fallbackDrugName,
//                        drugType = drugInfo?.drugType,
//                        equivalence = drugInfo?.is_ndc_equivalent?.toString(),
//                        packageQty = drugInfo?.qty
//                    )
//                }
//            }
//
//            if (finalDrug != null) {
//                resolvedDrugCache[ndc] = finalDrug
//                resolvedItems.add(
//                    ResolvedInventoryItem(
//                        inventoryItem = inv,
//                        drug = finalDrug
//                    )
//                )
//            }
//        }
//
//        if (resolvedItems.isEmpty()) {
//            logger.w("No valid drugs resolved from local DB or API. Batch will not be created.")
//            return
//        }
//
//        val now = System.currentTimeMillis()
//        val batch = BatchEntity(
//            batchId = now,
//            startDateTime = now,
//            endDateTime = null,
//            status = BatchStatus.INPROGRESS,
//            isDeleted = false,
//            note = null,
//            bucketId = "",
//            createdAt = now,
//            updatedAt = now,
//            isComingFromPms = true
//        )
//        val batchId = batchDao.insert(batch)
//
//        resolvedItems.forEach { resolvedItem ->
//            val inv = resolvedItem.inventoryItem
//            val ndc = inv.substanceCode.orEmpty().trim()
//            val lotNo = inv.lotNumber?.trim()?.takeIf { it.isNotEmpty() }
//            val expiry = inv.expirationDateTime?.trim()?.takeIf { it.isNotEmpty() }
//
//            val drugId = drugMasterDao.upsertPreservingId(resolvedItem.drug)
//
//            val targetCount = inv.currentQuantity?.toIntOrNull()
//                ?: inv.availableQuantity?.toIntOrNull()
//                ?: inv.initialQuantity?.toIntOrNull()
//
//            val txn = PillCountTxnEntity(
//                localId = preferenceHelper.getLocalId(),
//                drugId = drugId,
//                countType = CountType.REGULAR,
//                targetCount = targetCount,
//                status = CountStatus.PARTIAL,
//                isComingFromHL7 = true,
//                isSynced = false,
//                isNdcVerified = false,
//                batchId = batchId,
//                lotNo = lotNo,
//                expiry = expiry
//            )
//
//            val txnId = pillCountTxnDao.upsertPreservingId(txn)
//
//            logger.i(
//                "Inserted transaction for NDC: $ndc, drugId: $drugId, txnId: $txnId, lotNo: $lotNo, expiry: $expiry, targetCount: $targetCount"
//            )
//        }
//
//        logger.i("Processed ${resolvedItems.size} inventory items for batchId: $batchId")
//    }

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
        val medicationItems = message.medications
        if (medicationItems.isEmpty()) {
            logger.w("No inventory items found in HL7 message")
            return
        }

        val resolvedItems = mutableListOf<ResolvedInventoryItem>()

        for (inv in medicationItems) {
            val ndc = inv.drugCode.trim()
            val lot = NULL.toString()
            val expiry = NULL.toString()
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

                if (resolvedDrugName.isNullOrBlank()) {
                    logger.w("Skipping inventory item because API returned no usable drug name for NDC: $ndc")
                    continue
                }

                val drugEntity = DrugMasterEntity(
                    ndc = drugInfo?.ndc?.takeIf { it.isNotBlank() } ?: ndc,
                    drugName = resolvedDrugName,
                    drugType = drugInfo.drugType,
                    equivalence = drugInfo.is_ndc_equivalent?.toString(),
                    packageQty = drugInfo.qty
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
    }

    private fun classifyInboundMessage(
        message: CompleteHL7Message
    ): MessageType? {
        return when {
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
}