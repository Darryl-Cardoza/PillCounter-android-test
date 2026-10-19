package com.dispensesure.retail.feature.dispense.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.models.DISPENSED_QUANTITY_STEP
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottleJson
import com.dispensesure.retail.core.scanning.domain.model.TxnDetail
import com.dispensesure.retail.core.scanning.engine.PhotoLabel
import com.dispensesure.retail.core.scanning.engine.SavedPhoto
import com.dispensesure.retail.core.security.ImageCrypto
import com.dispensesure.retail.core.tourguide.TutorialData
import com.dispensesure.retail.core.utils.common.HelperFunctions.saveBitmapToFile
import com.dispensesure.retail.core.utils.common.OverlayUtils
import com.dispensesure.retail.core.utils.common.deleteFilesQuietly
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.feature.hl7.data.repository.DispenseHl7Repository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Dispense count reads and writes: counts, bottles, the vial photo, reset and completion. */
class DispenseCountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val dispenseTxnDao: DispenseTxnDao,
    private val dispenseCountDao: DispenseCountDao,
    private val drugMasterDao: DrugMasterDao,
    private val operatorNameProvider: OperatorNameProvider,
    private val dispenseHl7Repository: DispenseHl7Repository,
) {

    private val logger = AppLogger("DispenseCountRepository")

    // ─────────────────────────── Txn and drug ───────────────────────────

    suspend fun loadTxn(txnId: Long): DispenseTxnDetail? = dispenseTxnDao.getDispenseTxnDetail(txnId)

    suspend fun loadDrug(drugId: Long?): DrugMasterEntity? = drugMasterDao.getDrugById(drugId)

    /** The local drug a scanned code belongs to: by GTIN, then by NDC. */
    suspend fun findLocalDrug(code: String): DrugMasterEntity? =
        drugMasterDao.getDrugByGtin(code) ?: drugMasterDao.getDrugByNdc(code)

    suspend fun saveTargetCount(txnId: Long, targetCount: Int) = dispenseTxnDao.updateTargetCount(txnId, targetCount)

    suspend fun saveNote(txnId: Long, note: String) = dispenseTxnDao.updateNote(txnId, note)

    suspend fun saveStep(txnId: Long, step: StepState) = dispenseTxnDao.updateWorkflowStep(txnId, step.name)

    suspend fun saveGlovesPresent(txnId: Long) = dispenseTxnDao.updateGlovesPresent(txnId, true)

    suspend fun saveHazardousTrayDetected(txnId: Long, detected: Boolean) =
        dispenseTxnDao.updateHazardousTrayDetected(txnId, detected)

    /** Tutorial txns are marked by the demo Rx prefix or the demo drug, the same markers the purge uses. */
    suspend fun isDemoTxn(txnId: Long): Boolean {
        val txn = dispenseTxnDao.getById(txnId) ?: return false
        return txn.rxNo?.startsWith(TutorialData.RX_PREFIX) == true ||
            drugMasterDao.getDrugById(txn.drugId)?.ndc == TutorialData.DEMO_NDC
    }

    // ─────────────────────────── Counts ───────────────────────────

    /** One step's counts, newest first, as the count panel and View All show them. */
    fun observeCounts(txnId: Long, step: StepState): Flow<List<TxnDetail>> =
        dispenseCountDao.observeAllForTxn(txnId, step).map { entities ->
            entities.map {
                TxnDetail(
                    txnDetailId = it.countId,
                    count = it.pillCount ?: 0,
                    image = it.imagePath,
                    createdAt = it.createdAt,
                    type = step
                )
            }
        }

    suspend fun latestCountStep(txnId: Long): StepState? = dispenseCountDao.getLatestStep(txnId)

    suspend fun totalPillCount(txnId: Long): Int = dispenseCountDao.getTotalPillCountForTxn(txnId)

    suspend fun stepPillTotal(txnId: Long, step: StepState): Int =
        dispenseCountDao.getPillCountForStep(txnId, step.name)

    /** Drug, Rx and active-bottle text for an ADD photo on [step]. */
    suspend fun countPhotoLabel(txnId: Long, step: StepState): PhotoLabel {
        val dispenseTxn = dispenseTxnDao.getById(txnId)
        val orderedDrug = drugMasterDao.getDrugById(dispenseTxn?.drugId)

        // Resolve the currently active bottle (last one scanned), to watermark this
        // photo with its lot/exp/serial.
        val activeBottle = DispenseBottleJson.decode(dispenseTxn?.bottlesJson).lastOrNull()

        // Name, image and dispensed NDC follow the substitute when there is one.
        val dispensedDrug = dispenseTxn?.takeIf { txn -> txn.isSubstitute }?.substitutedDrugId
            ?.let { substituteDrugId -> drugMasterDao.getDrugById(substituteDrugId) } ?: orderedDrug

        return PhotoLabel(
            drugName = dispensedDrug?.drugName,
            drugImagePath = dispensedDrug?.drugImagePath,
            isStockCount = false,
            requestedNdc = orderedDrug?.ndc,
            dispensedNdc = dispensedDrug?.ndc,
            lotNumber = activeBottle?.lotNumber,
            serialNumber = activeBottle?.serialNumber,
            expirationDate = activeBottle?.expirationDate,
            rxRefill = OverlayUtils.rxRefill(dispenseTxn?.rxNo, dispenseTxn?.refillNo),
            stepLabel = step.name,
        )
    }

    /** Saves one ADD and links it to the active bottle. Returns the new count's id. */
    suspend fun addCount(txnId: Long, step: StepState, pillCount: Int, savedPhoto: SavedPhoto): Long {
        val countRow = DispenseCountEntity(
            txnId = txnId,
            pillCount = pillCount,
            imagePath = savedPhoto.imagePath,
            rawImagePath = savedPhoto.rawImagePath,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            step = step.toString(),
        )
        // One transaction so a View All delete can't overwrite the bottle list mid-link.
        return appDatabase.withTransaction {
            val newCountId = dispenseCountDao.insert(countRow)
            linkCountToActiveBottle(txnId, newCountId)
            newCountId
        }
    }

    /** Tour: saves a typed-in count with no photo and no bottle link. Returns the new count's id. */
    suspend fun addManualCount(txnId: Long, step: StepState, pillCount: Int): Long =
        dispenseCountDao.insert(
            DispenseCountEntity(
                txnId = txnId,
                pillCount = pillCount,
                imagePath = null,
                step = step.toString(),
                isManual = true,
            )
        )

    /** Deletes one count: its photo files, its row, and its id on its bottle. */
    suspend fun deleteCount(txnId: Long, countId: Long) {
        // Files first: once the row is gone its paths are unrecoverable.
        val photoPaths = dispenseCountDao.getImagePathsForCount(countId)
        withContext(Dispatchers.IO) { deleteFilesQuietly(photoPaths, logger) }
        // One transaction so an Add can't overwrite the bottle list mid-unlink.
        appDatabase.withTransaction {
            dispenseCountDao.hardDelete(countId)
            unlinkCountsFromBottles(txnId, setOf(countId))
        }
        logger.i("Transaction detail deleted. Id=$countId")
    }

    /**
     * Appends [newCountId] to the currently active (last) bottle's [DispenseBottle.countIds]
     * and persists it. No-op if the txn has no bottle entries yet (no bottle scanned before
     * this pill was counted — legacy/no-GS1-scan txns). Re-reads and re-writes fresh each
     * call so concurrent appends don't clobber each other.
     */
    private suspend fun linkCountToActiveBottle(txnId: Long, newCountId: Long) {
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
    private suspend fun unlinkCountsFromBottles(txnId: Long, deletedIds: Set<Long>) {
        val txn = dispenseTxnDao.getById(txnId) ?: return
        val bottles = DispenseBottleJson.decode(txn.bottlesJson)
        if (bottles.none { bottle -> bottle.countIds.any { it in deletedIds } }) return
        val cleaned = bottles.map { it.copy(countIds = it.countIds - deletedIds) }
        dispenseTxnDao.updateBottles(txnId, DispenseBottleJson.encode(cleaned))
    }

    // ─────────────────────────── Vial photo ───────────────────────────

    /**
     * Saves the vial still as the txn's one VIAL row. A recycled or unwritable still leaves the row without a file.
     * [saveFile] false writes the row only (the tour's placeholder still).
     */
    suspend fun saveVialPhoto(txnId: Long, vialStill: Bitmap, grayscale: Boolean, saveFile: Boolean = true) {
        val filePath = if (!saveFile) null else withContext(Dispatchers.IO) {
            try {
                if (!vialStill.isRecycled) {
                    saveBitmapToFile(
                        context,
                        vialStill,
                        "txn_detail_${System.currentTimeMillis()}.jpg",
                        "transaction_details",
                        grayscale = grayscale,
                    )
                } else null
            } catch (e: Exception) {
                logger.e("Failed saving bitmap", e, event = LogEvent.FILE_WRITE_ERROR)
                null
            }
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
    }

    /** The txn's saved vial still, decrypted when needed. Null when there is none or it can't be read. */
    suspend fun loadVialPhoto(txnId: Long): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val vialDetail = dispenseCountDao.observeAllForTxn(txnId, StepState.VIAL).first().firstOrNull()
            val imagePath = vialDetail?.imagePath ?: return@withContext null
            val file = File(imagePath)
            if (!file.exists()) return@withContext null
            val rawBytes = file.readBytes()
            val jpegBytes = if (ImageCrypto.isEncrypted(rawBytes)) {
                ImageCrypto.decrypt(rawBytes)
            } else {
                rawBytes
            }
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return@withContext null
            logger.i("Loaded existing vial photo from $imagePath")
            bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Failed to load existing vial photo", e, event = LogEvent.VIAL_SCAN_FAILED)
            null
        }
    }

    // ─────────────────────────── Bottles ───────────────────────────

    /** The txn's scanned bottles, oldest first; null when the txn is gone. */
    suspend fun loadBottles(txnId: Long): List<DispenseBottle>? =
        dispenseTxnDao.getById(txnId)?.let { txn -> DispenseBottleJson.decode(txn.bottlesJson) }

    /** Adds [bottle] as the new active bottle. */
    suspend fun addBottle(txnId: Long, bottle: DispenseBottle) {
        // One transaction so an Add can't link a count to the list mid-rewrite and lose it.
        appDatabase.withTransaction {
            val bottles = loadBottles(txnId)?.toMutableList() ?: return@withTransaction
            bottles.add(bottle)
            dispenseTxnDao.updateBottles(txnId, DispenseBottleJson.encode(bottles))
            logger.i("BOTTLE_SCAN added new bottle for txnId=$txnId, total bottles=${bottles.size}")
        }
    }

    /** Puts [bottle] in place of the active bottle, or adds it when there is none. */
    suspend fun replaceLastBottle(txnId: Long, bottle: DispenseBottle) {
        // One transaction, as in addBottle.
        appDatabase.withTransaction {
            val bottles = loadBottles(txnId)?.toMutableList() ?: return@withTransaction
            if (bottles.isEmpty()) {
                bottles.add(bottle)
            } else {
                bottles[bottles.lastIndex] = bottle
            }
            dispenseTxnDao.updateBottles(txnId, DispenseBottleJson.encode(bottles))
            logger.i("BOTTLE_SCAN replaced last bottle for txnId=$txnId")
        }
    }

    // ─────────────────────────── Reset and completion ───────────────────────────

    /**
     * Keeps reset availability live. A count that has reached the pharmacy system can no
     * longer be thrown away locally, and that can land while the screen is still up.
     */
    fun observeCanReset(txnId: Long): Flow<Boolean> =
        // No txn yet: nothing has reached the pharmacy system.
        if (txnId == 0L) {
            flowOf(true)
        } else {
            dispenseTxnDao.observeById(txnId).map { txn -> txn?.isSynced != true }
        }

    /** Hard-deletes every count and its files and clears the txn's count fields; the order itself stays. */
    suspend fun reset(txnId: Long) {
        withContext(Dispatchers.IO) {
            // Files first: once the rows are gone their paths are unrecoverable.
            deleteFilesQuietly(dispenseCountDao.getImagePathsForTxn(txnId), logger)
            dispenseCountDao.deleteAllForTxn(txnId)
            dispenseTxnDao.resetForRecount(txnId)
        }
    }

    /**
     * Completes the dispense: operator, then status, then the HL7 send. Returns the status
     * written, or null when the txn is gone or nothing was counted.
     */
    suspend fun complete(txnId: Long): CountStatus? {
        val total = dispenseCountDao.getTotalPillCountForTxn(txnId)
        val txn = dispenseTxnDao.getById(txnId) ?: return null
        if (total == 0) {
            return null
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
        dispenseHl7Repository.sendDispenseNow(txnId)
        return status
    }
}
