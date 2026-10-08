package com.dispensesure.retail.feature.hl7.presentation

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.PillCountTxnDao
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.PillCountTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.data.DrugImageDownloader
import com.dispensesure.retail.core.scanning.data.DrugRepository
import com.dispensesure.retail.core.scanning.domain.model.GetNdcRequestModel
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.parsing.model.Hl7OrderAction
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.rite.hl7.model.HL7Message
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Hl7OrderProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pillCountTxnDao: PillCountTxnDao,
    private val drugMasterDao: DrugMasterDao,
    private val preferenceHelper: PreferenceHelper,
    private val drugRepository: DrugRepository,
    private val drugImageDownloader: DrugImageDownloader,
    private val notifier: Hl7Notifier,
    private val hl7Repository: Hl7Repository,
) {

    private val logger = AppLogger("Hl7OrderProcessor")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun process(action: Hl7OrderAction): Job {
        return scope.launch {
            when (action) {
                is Hl7OrderAction.NewOrder -> handleNewOrder(action.order, action.message)
                is Hl7OrderAction.Refill -> handleRefill(action.order, action.message)
                is Hl7OrderAction.Cancel -> handleCancel(action.rxNo)
                is Hl7OrderAction.Hold -> handleHold(action.orderId)
                is Hl7OrderAction.Release -> handleRelease(action.orderId)
                is Hl7OrderAction.Discontinue -> handleDiscontinue(action.orderId)
                is Hl7OrderAction.ReplaceTodo -> {
                    logger.w("RP (Replace) received — not yet implemented, ignoring")
                }
            }
        }
    }

    private suspend fun handleNewOrder(order: OrderGroup, message: HL7Message) {
        val hl7Ndc = order.giveCode?.trim().orEmpty()
        val hl7DrugName = order.giveName.orEmpty()
        val targetCount = order.dispenseAmount
        val rxNo = order.fillerOrderNumber.takeIf { it.isNotBlank() }
            ?: order.placerOrderNumber.takeIf { it.isNotBlank() }
        val orderId = order.placerOrderNumber.takeIf { it.isNotBlank() }

        if (hl7Ndc.isBlank()) {
            logger.w("handleNewOrder: NDC missing (RXE-2) for rxNo=$rxNo — ignoring")
            notifier.show(
                title = context.getString(R.string.hl7_notification_drug_not_found_title),
                message = context.getString(R.string.hl7_notification_new_rx_ndc_missing, rxNo.orEmpty()),
            )
            return
        }
        if (targetCount == null) {
            logger.w("handleNewOrder: dispense amount missing (RXE-10/RXE-3) for rxNo=$rxNo — ignoring")
            notifier.show(
                title = context.getString(R.string.hl7_notification_new_rx_title),
                message = context.getString(R.string.hl7_notification_new_rx_qty_missing, rxNo.orEmpty()),
            )
            return
        }
        if (targetCount <= 0) {
            logger.w("handleNewOrder: invalid dispense amount $targetCount for rxNo=$rxNo — ignoring")
            notifier.show(
                title = context.getString(R.string.hl7_notification_new_rx_title),
                message = context.getString(R.string.hl7_notification_new_rx_qty_invalid, rxNo.orEmpty(), targetCount),
            )
            return
        }

        val refillNo = order.refillNumber.toString()

        // Upsert: if same Rx + same refill already exists and is still open → update, else create new
        val matched = if (rxNo != null) pillCountTxnDao.getByRxNoAndFillNo(rxNo, refillNo) else null
        if (matched?.isFinished() == true && findResend(rxNo, order.messageControlId)?.isFinished() == true) {
            logger.w("handleNewOrder: resend of msgId=${order.messageControlId} for rxNo=$rxNo already finished — ignoring")
            return
        }
        val existing = matched?.takeUnless { it.isFinished() }
        val finalDrug = resolveDrug(hl7Ndc, hl7DrugName) ?: return
        val drugId = drugMasterDao.upsertPreservingId(finalDrug)

        val txnId: Long
        if (existing != null) {
            pillCountTxnDao.updateFromHl7Edit(
                txnId = existing.txnId,
                drugId = drugId,
                targetCount = targetCount,
                priority = order.priority,
                status = null,
            )
            txnId = existing.txnId
        } else {
            val txn = PillCountTxnEntity(
                localId = preferenceHelper.getLocalId(),
                drugId = drugId,
                isDispense = true,
                targetCount = targetCount,
                status = CountStatus.PARTIAL,
                isComingFromHL7 = true,
                isSynced = false,
                isNdcVerified = false,
                rxNo = rxNo,
                transactionOrderId = orderId,
                refillNo = refillNo,
                priority = order.priority,
                hl7MessageControlId = order.messageControlId.takeIf { it.isNotBlank() },
                hl7SequenceNumber = message.header?.sequenceNumber?.takeIf { it.isNotBlank() },
            )
            txnId = pillCountTxnDao.upsertPreservingId(txn)
        }

        preferenceHelper.saveTxnId(txnId)
        hl7Repository.insertZinContainerDetails(txnId, message)

        notifier.show(
            title = context.getString(R.string.hl7_notification_new_rx_title),
            message = context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), hl7DrugName, targetCount),
        )
    }

    private suspend fun handleRefill(order: OrderGroup, message: HL7Message) {
        val rxNo = order.fillerOrderNumber.takeIf { it.isNotBlank() }
            ?: order.placerOrderNumber.takeIf { it.isNotBlank() }

        // Check exhausted refills: pendingRefills is RXE-16; 0 = no more refills allowed
        val pending = order.pendingRefills
        if (pending != null && pending <= 0) {
            logger.w("handleRefill: refills exhausted for rxNo=$rxNo (RXE-16=$pending) — notifying, not creating txn")
            notifier.show(
                title = context.getString(R.string.hl7_notification_new_rx_title),
                message = context.getString(R.string.hl7_notification_refill_exhausted, rxNo.orEmpty(), pending),
            )
            return
        }

        // refillNumber = RXE-12 − RXE-16; if RXE-16 absent, derive from existing txn count
        val refillNo = if (order.refillNumber > 0) {
            order.refillNumber.toString()
        } else {
            // A PMS resend of this message (same Rx + MSH-10) reuses its refill instead of adding another.
            val resent = findResend(rxNo, order.messageControlId)
            if (resent?.isFinished() == true) {
                logger.w("handleRefill: resend of msgId=${order.messageControlId} for rxNo=$rxNo already finished — ignoring")
                return
            }
            resent?.refillNo ?: run {
                // RXE-16 not sent by PMS — derive from most recent txn for this Rx
                val mostRecent = if (rxNo != null) pillCountTxnDao.getMostRecentByRxNo(rxNo) else null
                val prevRefill = mostRecent?.refillNo?.toIntOrNull() ?: 0
                (prevRefill + 1).toString()
            }
        }

        // Same Rx + same refillNo still open → update existing; otherwise → new txn
        val matched = if (rxNo != null) pillCountTxnDao.getByRxNoAndFillNo(rxNo, refillNo) else null
        if (matched?.isFinished() == true && findResend(rxNo, order.messageControlId)?.isFinished() == true) {
            logger.w("handleRefill: resend of msgId=${order.messageControlId} for rxNo=$rxNo already finished — ignoring")
            return
        }
        val existing = matched?.takeUnless { it.isFinished() }

        val hl7Ndc = order.giveCode?.trim().orEmpty()
        val hl7DrugName = order.giveName.orEmpty()
        val targetCount = order.dispenseAmount

        if (hl7Ndc.isBlank() || targetCount == null || targetCount <= 0) {
            logger.w("handleRefill: missing NDC or qty for rxNo=$rxNo refillNo=$refillNo — ignoring")
            notifier.show(
                title = context.getString(R.string.hl7_notification_new_rx_title),
                message = context.getString(R.string.hl7_notification_refill_ndc_or_qty_missing, rxNo.orEmpty()),
            )
            return
        }

        val finalDrug = resolveDrug(hl7Ndc, hl7DrugName) ?: return
        val drugId = drugMasterDao.upsertPreservingId(finalDrug)

        val txnId: Long
        if (existing != null) {
            pillCountTxnDao.updateFromHl7Edit(
                txnId = existing.txnId,
                drugId = drugId,
                targetCount = targetCount,
                priority = order.priority,
                status = null,
            )
            txnId = existing.txnId
        } else {
            val txn = PillCountTxnEntity(
                localId = preferenceHelper.getLocalId(),
                drugId = drugId,
                isDispense = true,
                targetCount = targetCount,
                status = CountStatus.PARTIAL,
                isComingFromHL7 = true,
                isSynced = false,
                isNdcVerified = false,
                rxNo = rxNo,
                transactionOrderId = order.placerOrderNumber.takeIf { it.isNotBlank() },
                refillNo = refillNo,
                priority = order.priority,
                hl7MessageControlId = order.messageControlId.takeIf { it.isNotBlank() },
                hl7SequenceNumber = message.header?.sequenceNumber?.takeIf { it.isNotBlank() },
            )
            txnId = pillCountTxnDao.upsertPreservingId(txn)
        }

        preferenceHelper.saveTxnId(txnId)
        hl7Repository.insertZinContainerDetails(txnId, message)

        notifier.show(
            title = context.getString(R.string.hl7_notification_new_rx_title),
            message = context.getString(R.string.hl7_notification_new_rx_single, rxNo.orEmpty(), hl7DrugName, targetCount),
        )
    }

    private suspend fun handleCancel(rxNo: String) {
        logger.i("ORC|CA for rxNo=$rxNo — soft-deleting transaction")
        pillCountTxnDao.softDeleteByRxNo(rxNo)
    }

    private suspend fun handleHold(orderId: String) {
        logger.i("ORC|HD for orderId=$orderId — marking ON_HOLD")
        pillCountTxnDao.updateStatusByOrderIdIfPartial(orderId, CountStatus.ON_HOLD)
    }

    private suspend fun handleRelease(orderId: String) {
        logger.i("ORC|RL for orderId=$orderId — restoring to PARTIAL")
        pillCountTxnDao.updateStatusByOrderIdIfPartialOrOnHold(orderId, CountStatus.PARTIAL)
    }

    private suspend fun handleDiscontinue(orderId: String) {
        logger.i("ORC|DC for orderId=$orderId — soft-deleting (discontinued)")
        pillCountTxnDao.softDeleteByOrderId(orderId)
    }

    private suspend fun resolveDrug(ndc: String, fallbackName: String): DrugMasterEntity? {
        val local = drugMasterDao.getDrugByNdc(ndc)
        if (local != null) return local

        val request = GetNdcRequestModel(target_drug = ndc, scanned_drug = ndc)
        return try {
            val drugInfo = drugRepository.getDrugInfoByNdc(request)
            val resolvedName = drugInfo?.genericName?.takeIf { it.isNotBlank() }
                ?: fallbackName.takeIf { it.isNotBlank() }
            if (resolvedName.isNullOrBlank()) {
                notifier.show(
                    title = context.getString(R.string.hl7_notification_drug_not_found_title),
                    message = context.getString(R.string.hl7_notification_drug_not_found_no_drug, ndc),
                )
                return null
            }
            val imagePath = drugImageDownloader.downloadAndSave(
                url = drugInfo?.imageUrl,
                drugName = resolvedName,
            )
            DrugMasterEntity(
                ndc = drugInfo?.ndc?.takeIf { it.isNotBlank() } ?: ndc,
                drugName = resolvedName,
                drugType = drugInfo?.drugType,
                isHazardous = drugInfo?.isHazardous ?: false,
                strength = drugInfo?.strength,
                dosageForm = drugInfo?.dosageForm,
                drugImagePath = imagePath,
            )
        } catch (e: Exception) {
            logger.e("Failed to fetch drug info from API for NDC=$ndc", e)
            notifier.show(
                title = context.getString(R.string.hl7_notification_drug_not_found_title),
                message = context.getString(R.string.hl7_notification_drug_not_found_api_failed, ndc),
            )
            null
        }
    }

    // A finished count is never overwritten by a later NW/RF for the same fill.
    private fun PillCountTxnEntity.isFinished() =
        status == CountStatus.COMPLETED || status == CountStatus.FORCE_COMPLETED

    // Saved row for this Rx from the same HL7 message (MSH-10), i.e. a PMS resend.
    private suspend fun findResend(rxNo: String?, messageControlId: String): PillCountTxnEntity? =
        if (rxNo != null && messageControlId.isNotBlank()) {
            pillCountTxnDao.getByRxNoAndMessageControlId(rxNo, messageControlId)
        } else null
}
