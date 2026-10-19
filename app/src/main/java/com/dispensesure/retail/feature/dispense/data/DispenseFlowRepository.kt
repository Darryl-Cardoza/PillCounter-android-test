package com.dispensesure.retail.feature.dispense.data

import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.models.isControlledDrugType
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.tourguide.TutorialData
import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem
import com.dispensesure.retail.feature.dispense.domain.model.StandaloneRxDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Every Room call of the dispense flow: entry loads, the Rx label lookups, the standalone txn, the container check, the queue. */
class DispenseFlowRepository @Inject constructor(
    private val dispenseTxnDao: DispenseTxnDao,
    private val drugMasterDao: DrugMasterDao,
) {

    suspend fun loadTxn(txnId: Long): DispenseTxnEntity? = dispenseTxnDao.getById(txnId)

    // Null when there is no drug id to look up.
    suspend fun loadDrug(drugId: Long?): DrugMasterEntity? = drugId?.let { drugMasterDao.getDrugById(it) }

    /** The drug the txn's order names; null when the txn or its drug is missing. */
    suspend fun loadOrderedDrug(txnId: Long): DrugMasterEntity? = loadDrug(loadTxn(txnId)?.drugId)

    // Rx label lookups: one fill of the Rx, the Rx's latest txn, its PARTIAL / ON_HOLD order.
    suspend fun loadTxnByRxNoAndFill(rxNo: String, refillNo: String): DispenseTxnEntity? =
        dispenseTxnDao.getByRxNoAndFillNo(rxNo, refillNo)

    suspend fun loadMostRecentTxnByRxNo(rxNo: String): DispenseTxnEntity? = dispenseTxnDao.getMostRecentByRxNo(rxNo)

    suspend fun loadActiveTxnByRxNo(rxNo: String): DispenseTxnEntity? = dispenseTxnDao.getActiveByRxNo(rxNo)

    // A count picked up again starts with gloves unconfirmed.
    suspend fun clearGlovesPresent(txnId: Long) {
        dispenseTxnDao.updateGlovesPresent(txnId, false)
    }

    /** Tour: saves the demo drug under [name] and returns it; null if it can't be read back. */
    suspend fun upsertDemoDrug(name: String): DrugMasterEntity? {
        TutorialData.upsertDemoDrug(drugMasterDao, name)
        return drugMasterDao.getDrugByNdc(TutorialData.DEMO_NDC)
    }

    /**
     * Inserts the PARTIAL dispense txn for a staged standalone Rx label.
     * Called from the flow's Proceed only, never from the scan itself.
     *
     * @return the new txn's id.
     */
    suspend fun createStandaloneTxn(draft: StandaloneRxDraft, userLocalId: Long): Long {
        val newTxn = DispenseTxnEntity(
            userLocalId = userLocalId,
            drugId = draft.drugId,
            targetCount = draft.targetCount,
            status = CountStatus.PARTIAL,
            isComingFromHL7 = false,
            isSynced = false,
            isNdcVerified = false,
            rxNo = draft.rxNo,
            refillNo = draft.refillNo,
            // Same RxNo-RefillNo composite DispenseMessageBuilder sends as the order identifier
            // (Vivid ZUI-4 rxNumber / EyeCon ZUI-11 transactionOrderId) — PMS pulls dispense
            // images via getByTransactionOrderId keyed on that value. Without it, a
            // locally-scanned dispense's images 404 on that pull and, with local storage
            // off, are deleted after ACK with no way to re-fetch them.
            transactionOrderId = draft.refillNo?.takeIf { it.isNotBlank() }?.let { "${draft.rxNo}-$it" } ?: draft.rxNo,
            bucketId = draft.bucket,
        )
        return dispenseTxnDao.upsertPreservingId(newTxn)
    }

    /**
     * Marks the container verified. Saves [substituteDrug] and links it when there is one,
     * and [firstBottle] when the txn has no bottle yet. False when the txn is gone.
     */
    suspend fun markContainerVerified(
        txnId: Long,
        isSubstitute: Boolean,
        substituteDrug: DrugMasterEntity?,
        firstBottle: DispenseBottle?,
    ): Boolean {
        val txn = dispenseTxnDao.getById(txnId) ?: return false
        val substitutedDrugId = substituteDrug?.let { drug ->
            // The image was already downloaded during the NDC scan step (the container scan).
            // Carry that path forward so the upsert doesn't overwrite it with null.
            val existingImagePath = drugMasterDao.getDrugByNdc(drug.ndc)?.drugImagePath
            drugMasterDao.upsertPreservingId(drug.copy(drugImagePath = existingImagePath))
        }
        // Stamp the first bottle scanned against this txn — only when the txn has no
        // bottle entries yet, so resuming an already-in-progress dispense (e.g. after
        // process death) doesn't clobber bottles already tracked via a rescan mid-count.
        val existingBottles = DispenseBottleJson.decode(txn.bottlesJson)
        val bottlesJson = if (existingBottles.isEmpty() && firstBottle != null) {
            DispenseBottleJson.encode(listOf(firstBottle.copy(txnId = txnId)))
        } else txn.bottlesJson
        dispenseTxnDao.update(
            txn.copy(
                isNdcVerified = true,
                isSubstitute = isSubstitute,
                substitutedDrugId = substitutedDrugId,
                bottlesJson = bottlesJson,
            )
        )
        return true
    }

    suspend fun hasPendingDispenses(userLocalId: Long): Boolean =
        dispenseTxnDao.countPartialDispenses(
            partialStatus = CountStatus.PARTIAL,
            userLocalId = userLocalId,
        ) > 0

    /** The user's PARTIAL dispenses as queue rows. */
    fun observeQueue(userLocalId: Long): Flow<List<QueueItem.Dispense>> =
        dispenseTxnDao.observePartialDispenses(
            partialStatus = CountStatus.PARTIAL,
            userLocalId = userLocalId,
            step = StepState.TARGET_VERIFICATION,
        ).map { txns ->
            txns.map { txn ->
                QueueItem.Dispense(
                    txn = txn,
                    isHazardous = txn.isHazardous,
                    isHighPriority = txn.priority == TxnPriority.High,
                    isControlled = isControlledDrugType(txn.drugType),
                )
            }
        }
}
