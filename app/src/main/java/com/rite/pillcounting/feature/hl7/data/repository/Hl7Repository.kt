package com.rite.pillcounting.feature.hl7.data.repository

import android.annotation.SuppressLint
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
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

        hl7MessageSender.send(message)
    }

    /**
     * Build and send inventory response
     */
    @SuppressLint("SimpleDateFormat")
    suspend fun buildAndSendInventoryResponse(
        txnId: Long
    ) {
        val txn = txnDao.getById(txnId)
            ?: return
        val txnDetails = txnDetailsDao.getAllForTxn(txnId.toString())
        val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }
            ?: return
        val message = HL7MessageBuilder.buildInventoryMessage(
            txn = txn,
            txnDetails = txnDetails,
            drugCode = drug.ndc,
            drugName = drug.drugName ?: ""
        )
        hl7MessageSender.send(message)
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
                val result = when (txn.countType) {
                    CountType.FIXED -> {
                        if (txn.targetCount != null) {
                            buildAndSendSuccessfulDispense(
                                txnId = txn.txnId
                            )
                        } else Result.success(Unit)
                    }

                    CountType.REGULAR -> {
                        buildAndSendInventoryResponse(
                            txnId = txn.txnId
                        )
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
            isNdcVerified = false
        )

        val txnId = pillCountTxnDao.upsertPreservingId(txn)
        preferenceHelper.saveTxnId(txnId)
    }

    private suspend fun handleInrInventoryRequest(
        message: CompleteHL7Message
    ) {
        val inv = message.inventoryItems.firstOrNull() ?: return

        val ndc = inv.substanceCode ?: return
        val drugName = inv.substanceDescription ?: "Unknown Drug"

        val drugId = drugMasterDao.upsertPreservingId(
            DrugMasterEntity(
                ndc = ndc,
                drugName = drugName
            )
        )
        logger.i("Received message $ndc $drugName")


        val txn = PillCountTxnEntity(
            localId = preferenceHelper.getLocalId(),
            drugId = drugId,
            countType = CountType.REGULAR,
            targetCount = null,
            status = CountStatus.PARTIAL,
            isComingFromHL7 = true,
            isSynced = false,
            isNdcVerified = false
        )

        logger.i("Received message $txn")

        val txnId = pillCountTxnDao.upsertPreservingId(txn)
        preferenceHelper.saveTxnId(txnId)
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
                    message.triggerEvent == "U06" &&
                    message.inventoryItems.isNotEmpty() ->
                MessageType.INVENTORY_REQUEST

            else -> null
        }
    }

    private fun observePendingHl7Transactions() {
        scope.launch {
            pillCountTxnDao.observePendingHl7Txn()
                .collect { pendingTxn ->

                    logger.i("HL7 observer fired, pending=${pendingTxn.size}")

                    val hasPendingNow = pendingTxn.isNotEmpty()
                    if (hasPendingNow) {
                        logger.i("Pending HL7 txn detected, initiating connection")
                        hl7MessageSender.connect()
                    }
                }
        }
    }
}