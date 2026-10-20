package com.dispensesure.retail.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnWithDrug
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.tourguide.TutorialData
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.feature.history.domain.model.DispenseTxnSummary
import kotlinx.coroutines.flow.Flow

private val logger = AppLogger("DispenseTxnDao")

/**
 * **Dispense Transaction Data Access Object**
 *
 * Provides database operations for managing [DispenseTxnEntity] entries — the transactional
 * headers representing each pill counting event.
 *
 * ---
 * ### Key Design Principles
 * - **Txn ID Stability:** Transaction primary keys (`txnId`) must remain persistent
 *   to preserve foreign key relationships in related tables (e.g., `dispense_count`).
 * - **Safe Upserts:** Avoid destructive operations such as `REPLACE` which delete and recreate rows.
 * - **Reactive Observability:** Queries returning [Flow] provide live updates for dashboards or lists.
 * - **Soft Delete Policy:** Records are not physically deleted unless explicitly required.
 *
 * ---
 * ### Associated Tables
 * - `dispense_txn` — Main transaction header table.
 * - `dispense_count` — Line-level pill count details.
 * - `drug_master` — Reference table for drug metadata.
 */
@Dao
interface DispenseTxnDao {

    // ────────────────────────────── Create / Update ──────────────────────────────

    /**
     * Inserts a new [DispenseTxnEntity] into the database.
     *
     * - Uses [OnConflictStrategy.IGNORE] to prevent overwriting existing transactions.
     * - If a transaction with the same [DispenseTxnEntity.txnId] already exists,
     *   the operation will be ignored and return `-1`.
     *
     * @param txn The transaction entity to insert.
     * @return The new row ID (txnId) if inserted successfully, or `-1` if a conflict occurred.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(txn: DispenseTxnEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(txns: List<DispenseTxnEntity>): List<Long>

    /**
     * Updates an existing transaction entry matched by its [DispenseTxnEntity.txnId].
     *
     * @param txn The modified transaction entity.
     */
    @Update
    suspend fun update(txn: DispenseTxnEntity)

    /**
     * Performs a **safe upsert** (insert or update) while preserving the [txnId].
     *
     * This ensures transactional integrity — if the record exists, it is updated in place.
     * Otherwise, a new transaction row is inserted.
     *
     * @param txn The transaction to insert or update.
     * @return The stable [DispenseTxnEntity.txnId] of the inserted or updated record.
     * @throws IllegalStateException if the insert fails unexpectedly.
     */
    @Transaction
    suspend fun upsertPreservingId(txn: DispenseTxnEntity): Long {
        return try {
            if (txn.txnId != 0L) {
                val existing = getById(txn.txnId)
                if (existing != null) {
                    update(txn.copy(txnId = existing.txnId))
                    existing.txnId
                } else {
                    val newId = insertIgnore(txn)
                    if (newId == -1L) {
                        getById(txn.txnId)?.txnId
                            ?: throw IllegalStateException("Txn insert failed unexpectedly")
                    } else newId
                }
            } else {
                val newId = insertIgnore(txn)
                if (newId == -1L) {
                    throw IllegalStateException("Insert failed: transaction already exists")
                }
                newId
            }
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            logger.e("Foreign key constraint failed during upsertPreservingId(txnId=${txn.txnId})", e, event = LogEvent.DATABASE_ERROR)
            throw IllegalArgumentException("Foreign key constraint failed: Ensure User, Drug, and Batch exist before creating a transaction. ${e.message}")
        }
    }

    // ──────────────────────────────── Reads ────────────────────────────────

    /**
     * Fetches a transaction entity by its primary key.
     *
     * @param id The unique transaction ID.
     * @return The matching [DispenseTxnEntity] if found, or `null` otherwise.
     */
    @Query("SELECT * FROM dispense_txn WHERE txnId = :id LIMIT 1")
    suspend fun getById(id: Long): DispenseTxnEntity?

    /**
     * Emits the transaction whenever its row changes. Used to keep reset availability
     * live — a count can sync while the counting screen is still up.
     */
    @Query("SELECT * FROM dispense_txn WHERE txnId = :id LIMIT 1")
    fun observeById(id: Long): Flow<DispenseTxnEntity?>

    /**
     * Observes all **partial dispense transactions**,
     * along with their associated drug names and total pill counts.
     *
     * - Useful for showing "in-progress" transactions on a dashboard.
     * - Excludes deleted records.
     *
     * @param partialStatus Optional filter, defaults to [CountStatus.PARTIAL].
     * @return A [Flow] emitting a live list of [DispenseTxnWithDrug].
     */
    @Query(
        """
    SELECT txn.txnId,
           txn.createdAt,
           txn.targetCount,
           txn.bottlesJson,
           txn.isComingFromHL7,
           txn.isNdcVerified,
           txn.bucketId,
           txn.rxNo,
           txn.refillNo,
           txn.priority,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugId IS NOT NULL
                THEN subDrug.drugName ELSE drug.drugName END AS drugName,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
                THEN subDrug.ndc ELSE drug.ndc END AS ndc,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
                THEN subDrug.drugType ELSE drug.drugType END AS drugType,
           IFNULL(CASE WHEN txn.isSubstitute = 1 AND subDrug.isHazardous IS NOT NULL
                       THEN subDrug.isHazardous ELSE drug.isHazardous END, 0) AS isHazardous,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.strength IS NOT NULL
                THEN subDrug.strength ELSE drug.strength END AS strength,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.dosageForm IS NOT NULL
                THEN subDrug.dosageForm ELSE drug.dosageForm END AS dosageForm,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugImagePath IS NOT NULL
                THEN subDrug.drugImagePath ELSE drug.drugImagePath END AS drugImagePath,
           IFNULL(SUM(details.pillCount), 0) AS totalPillCount
    FROM dispense_txn AS txn
    LEFT JOIN drug_master AS drug
           ON txn.drugId = drug.drugId
    LEFT JOIN drug_master AS subDrug
           ON txn.substitutedDrugId = subDrug.drugId
    LEFT JOIN dispense_count AS details
           ON txn.txnId = details.txnId
          AND details.step = :step
    WHERE txn.isDeleted = 0
      AND txn.status = :partialStatus
      AND txn.userLocalId = :userLocalId
      AND (txn.rxNo IS NULL OR txn.rxNo NOT LIKE '${TutorialData.RX_PREFIX}%')
      AND (drug.ndc IS NULL OR drug.ndc != '${TutorialData.DEMO_NDC}')
    GROUP BY txn.txnId
    ORDER BY CASE txn.priority
                 WHEN 'High'   THEN 1
                 WHEN 'Medium' THEN 2
                 WHEN 'Low'    THEN 3
                 ELSE 2
             END ASC, /* values correspond to TxnPriority enum names */
             txn.createdAt ASC
    """
    )

    fun observePartialDispenses(
        partialStatus: CountStatus = CountStatus.PARTIAL,
        userLocalId: Long,
        step: StepState
    ): Flow<List<DispenseTxnWithDrug>>

    // ───────────────────────────── Field Updates ─────────────────────────────

    /**
     * Updates the **target count** value for a given transaction.
     *
     * @param txnId The transaction ID.
     * @param target The new target count, or `null` to clear.
     * @param now Optional timestamp; defaults to [System.currentTimeMillis].
     */
    @Query("UPDATE dispense_txn SET targetCount = :target, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateTargetCount(
        txnId: Long,
        target: Int?,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Updates the note associated with a transaction.
     *
     * @param txnId The transaction ID.
     * @param note The note text (nullable).
     * @param now Optional timestamp; defaults to [System.currentTimeMillis].
     */
    @Query("UPDATE dispense_txn SET note = :note, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateNote(
        txnId: Long,
        note: String?,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Persists the JSON-encoded per-bottle info list for a transaction.
     *
     * @param txnId The transaction ID.
     * @param bottlesJson JSON-encoded `List<DispenseBottle>` (see `DispenseBottleJson.encode`).
     * @param now Optional timestamp; defaults to [System.currentTimeMillis].
     */
    @Query("UPDATE dispense_txn SET bottlesJson = :bottlesJson, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateBottles(
        txnId: Long,
        bottlesJson: String,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Performs a soft delete by setting `isDeleted = 1`.
     * This preserves record history and maintains referential integrity.
     *
     * @param txnId The transaction ID.
     * @param now Optional timestamp; defaults to [System.currentTimeMillis].
     */
    @Query("UPDATE dispense_txn SET isDeleted = 1, updatedAt = :now WHERE txnId = :txnId")
    suspend fun softDelete(
        txnId: Long,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE dispense_txn SET isDeleted = 1, updatedAt = :now WHERE rxNo = :rxNo AND isDeleted = 0")
    suspend fun softDeleteByRxNo(
        rxNo: String,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        SELECT * FROM dispense_txn
        WHERE rxNo = :rxNo
          AND isDeleted = 1
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getDeletedByRxNo(rxNo: String): DispenseTxnEntity?

    @Query(
        """
        UPDATE dispense_txn
        SET isDeleted   = 0,
            status      = 'PARTIAL',
            updatedAt   = :now
        WHERE txnId = :txnId
        """
    )
    suspend fun restoreDeletedTxn(
        txnId: Long,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Finds the most-recent non-deleted, in-progress (PARTIAL) transaction for a given Rx number.
     *
     * Used when handling ORC|XO (change-order) messages from PMS so we can update
     * the existing transaction rather than creating a duplicate.
     *
     * @param rxNo The prescription number from the incoming HL7 message.
     * @return The matching [DispenseTxnEntity] if one exists, or `null`.
     */
    @Query(
        """
        SELECT * FROM dispense_txn
        WHERE rxNo    = :rxNo
          AND isDeleted = 0
          AND status  IN ('PARTIAL', 'ON_HOLD')
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getActiveByRxNo(rxNo: String): DispenseTxnEntity?

    // ─────────────────────────── IMAGE SERVER LOOKUPS ───────────────────────────
    // Backing lookups for ImageNanoServer's getby* endpoints — same underlying
    // transaction/image data, just keyed differently per the caller (Vivid/Eyecon).

    @Query("SELECT * FROM dispense_txn WHERE hl7MessageControlId = :messageControlId AND isDeleted = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByMessageControlId(messageControlId: String): DispenseTxnEntity?

    @Query("SELECT * FROM dispense_txn WHERE hl7SequenceNumber = :sequenceNumber AND isDeleted = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getBySequenceNumber(sequenceNumber: String): DispenseTxnEntity?

    @Query("SELECT * FROM dispense_txn WHERE transactionOrderId = :transactionOrderId AND isDeleted = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByTransactionOrderId(transactionOrderId: String): DispenseTxnEntity?

    @Query("SELECT * FROM dispense_txn WHERE rxNo = :rxNo AND refillNo = :fillNo AND isDeleted = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByRxNoAndFillNo(rxNo: String, fillNo: String): DispenseTxnEntity?

    // Same Rx from the same HL7 message (MSH-10) — detects a PMS resend.
    @Query("SELECT * FROM dispense_txn WHERE rxNo = :rxNo AND hl7MessageControlId = :messageControlId AND isDeleted = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByRxNoAndMessageControlId(rxNo: String, messageControlId: String): DispenseTxnEntity?

    /** Most recent transaction for [rxNo], regardless of fill number — used when no fill number is supplied. */
    @Query("SELECT * FROM dispense_txn WHERE rxNo = :rxNo AND isDeleted = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getMostRecentByRxNo(rxNo: String): DispenseTxnEntity?

    /**
     * Applies an HL7 change-order (ORC|XO) edit to an existing transaction:
     * updates drug, target count, and priority, and resets the sync flag so the
     * updated result is re-sent to PMS after counting completes.
     *
     * @param txnId       The transaction to update.
     * @param drugId      New drug FK (nullable — kept as-is when null is not intended).
     * @param targetCount New requested quantity.
     * @param priority    New priority from ZPR segment.
     * @param now         Timestamp; defaults to [System.currentTimeMillis].
     */
    @Query(
        """
        UPDATE dispense_txn
        SET drugId      = :drugId,
            targetCount = :targetCount,
            priority    = :priority,
            status      = CASE WHEN :status IS NULL THEN status ELSE :status END,
            isSynced    = 0,
            updatedAt   = :now
        WHERE txnId = :txnId
        """
    )
    suspend fun updateFromHl7Edit(
        txnId: Long,
        drugId: Long?,
        targetCount: Int?,
        priority: TxnPriority?,
        status: CountStatus?,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE dispense_txn
        SET status    = :status,
            updatedAt = :now
        WHERE transactionOrderId = :orderId
          AND isDeleted = 0
          AND status IN ('PARTIAL', 'ON_HOLD')
        """
    )
    suspend fun updateStatusByOrderIdIfPartialOrOnHold(
        orderId: String,
        status: CountStatus,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE dispense_txn
        SET status    = :status,
            updatedAt = :now
        WHERE transactionOrderId = :orderId
          AND isDeleted = 0
          AND status = 'PARTIAL'
        """
    )
    suspend fun updateStatusByOrderIdIfPartial(
        orderId: String,
        status: CountStatus,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE dispense_txn SET isDeleted = 1, updatedAt = :now WHERE transactionOrderId = :orderId AND isDeleted = 0")
    suspend fun softDeleteByOrderId(
        orderId: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM dispense_txn")
    suspend fun deleteAllTransactions()

    // ─────────────────────────────── Relations ───────────────────────────────

    /**
     * Retrieves a detailed transaction with its associated drug and total pill count.
     *
     * Joins data from:
     * - `drug_master` for drug name and NDC.
     * - `dispense_count` for individual pill counts.
     *
     * @param transactionId The ID of the transaction to fetch.
     * @return A [DispenseTxnDetail] DTO containing enriched transaction data, or `null` if not found.
     */
    @Transaction
    @Query(
        """
    SELECT
        pct.txnId,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.drugId IS NOT NULL
             THEN subDrug.drugName ELSE dm.drugName END AS drugName,
        IFNULL(CASE WHEN pct.isSubstitute = 1 AND subDrug.drugId IS NOT NULL
             THEN subDrug.drugId ELSE dm.drugId END, 0) AS drugId,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
             THEN subDrug.ndc ELSE dm.ndc END AS ndc,
        pct.targetCount,
        pct.note,
        pct.createdAt,
        pct.bottlesJson,
        pct.isComingFromHL7,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
             THEN subDrug.drugType ELSE dm.drugType END AS drugType,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.strength IS NOT NULL
             THEN subDrug.strength ELSE dm.strength END AS strength,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.dosageForm IS NOT NULL
             THEN subDrug.dosageForm ELSE dm.dosageForm END AS dosageForm,
        pct.bucketId,
        IFNULL(SUM(pcd.pillCount), 0) AS totalPillCount,
        pct.isSubstitute,
        dm.drugName AS requestedDrugName,
        dm.ndc AS requestedNdc,
        pct.workflowStep,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.drugImagePath IS NOT NULL
             THEN subDrug.drugImagePath ELSE dm.drugImagePath END AS drugImage
    FROM dispense_txn AS pct
    LEFT JOIN drug_master AS dm
        ON pct.drugId = dm.drugId
    LEFT JOIN drug_master AS subDrug
        ON pct.substitutedDrugId = subDrug.drugId
    LEFT JOIN dispense_count AS pcd
        ON pct.txnId = pcd.txnId
    WHERE pct.txnId = :transactionId AND pct.isDeleted = 0
    GROUP BY pct.txnId
    """
    )
    suspend fun getDispenseTxnDetail(transactionId: Long): DispenseTxnDetail?

    @Query("UPDATE dispense_txn SET workflowStep = :step, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateWorkflowStep(
        txnId: Long,
        step: String,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Clears everything a reset discards, leaving the order itself (Rx, target, bucket,
     * HL7 ids) intact so the same transaction restarts at the container scan.
     */
    @Query(
        """
    UPDATE dispense_txn SET
        isNdcVerified = 0,
        workflowStep = NULL,
        bottlesJson = NULL,
        isSubstitute = 0,
        substitutedDrugId = NULL,
        hazardousTrayDetected = NULL,
        isGlovesPresent = 0,
        updatedAt = :now
    WHERE txnId = :txnId
    """
    )
    suspend fun resetForRecount(txnId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE dispense_txn SET hl7MessageControlId = :messageControlId, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateHl7MessageControlId(
        txnId: Long,
        messageControlId: String,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE dispense_txn
        SET operatorFirstName = :firstName, operatorLastName = :lastName, updatedAt = :now
        WHERE txnId = :txnId
        """
    )
    suspend fun updateOperatorName(
        txnId: Long,
        firstName: String?,
        lastName: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE dispense_txn SET isGlovesPresent = :value, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateGlovesPresent(
        txnId: Long,
        value: Boolean,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE dispense_txn SET hazardousTrayDetected = :detected, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateHazardousTrayDetected(
        txnId: Long,
        detected: Boolean,
        now: Long = System.currentTimeMillis()
    )


    /**
     * Updates the [CountStatus] of a specific transaction.
     *
     * @param txnId The transaction ID.
     * @param newStatus The new status value.
     * @param updatedAt Optional timestamp; defaults to [System.currentTimeMillis].
     */
    @Query("UPDATE dispense_txn SET status = :newStatus, updatedAt = :updatedAt WHERE txnId = :txnId")
    suspend fun updateTxnStatus(
        txnId: Long,
        newStatus: CountStatus,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
    SELECT
        txn.txnId,
        txn.status,
        COALESCE(SUM(details.pillCount), 0) AS pillCount,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.drugId IS NOT NULL
             THEN subDrug.drugName ELSE drug.drugName END AS drugName,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
             THEN subDrug.ndc ELSE drug.ndc END AS ndc,
        txn.bottlesJson,
        txn.createdAt,
        txn.targetCount,
        txn.note,
        txn.bucketId,
        txn.rxNo,
        txn.refillNo,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
             THEN subDrug.drugType ELSE drug.drugType END AS drugType,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.strength IS NOT NULL
             THEN subDrug.strength ELSE drug.strength END AS strength,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.dosageForm IS NOT NULL
             THEN subDrug.dosageForm ELSE drug.dosageForm END AS dosageForm,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.drugImagePath IS NOT NULL
             THEN subDrug.drugImagePath ELSE drug.drugImagePath END AS drugImagePath
    FROM dispense_txn AS txn
    LEFT JOIN dispense_count AS details
           ON txn.txnId = details.txnId
           AND (:stepType IS NULL OR details.step = :stepType)
    LEFT JOIN drug_master AS drug
           ON txn.drugId = drug.drugId
    LEFT JOIN drug_master AS subDrug
           ON txn.substitutedDrugId = subDrug.drugId
    WHERE txn.createdAt BETWEEN :startDate AND :endDate
      AND txn.isDeleted = 0
      AND txn.userLocalId = :userLocalId
      AND (:status IS NULL OR txn.status = :status)
      AND (drug.ndc IS NULL OR drug.ndc != :excludeNdc)
      AND (txn.rxNo IS NULL OR txn.rxNo NOT LIKE '${TutorialData.RX_PREFIX}%')
    GROUP BY txn.txnId
    ORDER BY txn.createdAt DESC
    """
    )
    fun getTransactionsForDateRange(
        startDate: Long,
        endDate: Long,
        stepType: StepState?,
        status: CountStatus?,
        userLocalId: Long,
        excludeNdc: String = TutorialData.DEMO_NDC
    ): Flow<List<DispenseTxnSummary>>


    // ─────────────────────────────── Deletes ───────────────────────────────

    /**
     * Permanently deletes all transactions created within a date range.
     *
     * @param start Start timestamp (inclusive).
     * @param end End timestamp (inclusive).
     */
    @Query(
        """
    DELETE FROM dispense_txn
    WHERE createdAt >= :start
      AND createdAt < :end
      AND userLocalId = :userLocalId
      AND (
        :isCompleted IS NULL
        OR (:isCompleted = 1 AND (status = :completedStatus OR status = :forceCompletedStatus))
        OR (:isCompleted = 0 AND status != :completedStatus AND status != :forceCompletedStatus)
      )
    """
    )
    suspend fun deleteTransactionsByDate(
        start: Long,
        end: Long,
        isCompleted: Boolean?,
        userLocalId: Long,
        completedStatus: CountStatus = CountStatus.COMPLETED,
        forceCompletedStatus: CountStatus = CountStatus.FORCE_COMPLETED
    )

    /**
     * Retrieves all completed transactions created before a specific cutoff date.
     * Only returns COMPLETED or FORCE_COMPLETED transactions; partial/in-progress
     * transactions are excluded from retention-based cleanup.
     *
     * @param cutoff Timestamp before which records will be selected.
     * @return A list of [DispenseTxnEntity].
     */
    @Query("SELECT * FROM dispense_txn WHERE createdAt < :cutoff AND (status = 'COMPLETED' OR status = 'FORCE_COMPLETED')")
    suspend fun getTransactionsBefore(cutoff: Long): List<DispenseTxnEntity>

    /**
     * Retrieves all dispense transactions that have already been synced with the PMS.
     * Used by the launch-time cleanup pass to remove synced transactions from local storage
     * when the server's `allow_local_storage` flag is false.
     *
     * @return A list of synced [DispenseTxnEntity].
     */
    @Query("SELECT * FROM dispense_txn WHERE isSynced = 1")
    suspend fun getSyncedDispenses(): List<DispenseTxnEntity>

    /**
     * Retrieves the file paths of images linked to all transaction details under a given transaction.
     *
     * @param txnId The transaction ID.
     * @return A list of image file paths.
     */
    @Query(
        """
        SELECT imagePath FROM dispense_count WHERE txnId = :txnId AND imagePath IS NOT NULL
        UNION ALL
        SELECT rawImagePath FROM dispense_count WHERE txnId = :txnId AND rawImagePath IS NOT NULL
        """
    )
    suspend fun getTransactionDetailsImages(txnId: Long): List<String>

    /**
     * Permanently deletes a single transaction by its ID.
     *
     * ⚠️ **Note:** This action cannot be undone.
     *
     * @param txnId The transaction ID to delete.
     */
    @Query("DELETE FROM dispense_txn WHERE txnId = :txnId")
    suspend fun deleteTransaction(txnId: Long)

    /** Tutorial txns by durable marker: demo drug, or Rx prefix (survives the drug row being gone). */
    @Query(
        """
        SELECT txnId FROM dispense_txn
        WHERE rxNo LIKE '${TutorialData.RX_PREFIX}%'
           OR drugId IN (SELECT drugId FROM drug_master WHERE ndc = '${TutorialData.DEMO_NDC}')
        """
    )
    suspend fun getTutorialTxnIds(): List<Long>


    /**
     * Observe dispense transactions that are completed but NOT synced with PMS — exists solely
     * to trigger [DispenseHl7Repository.resendPendingDispenses][com.dispensesure.retail.feature.hl7.data.repository.DispenseHl7Repository.resendPendingDispenses]
     * on emission, so its filter must match that query's [getPendingHl7TxnOnce] exactly.
     * Only US dispenses: HL7 is US-only.
     *
     * Doesn't filter on isComingFromHL7, for the same reason [getPendingHl7TxnOnce]
     * doesn't: gating on isComingFromHL7 = 1 made this Flow blind to a locally-scanned dispense
     * (isComingFromHL7 = 0) whose send failed — nothing would ever wake the
     * resend sweep for it, and it stayed unsynced until an unrelated HL7-originated row
     * happened to change and wake the flow instead.
     *
     * This Flow emits whenever:
     * - a new dispense txn is completed
     * - isSynced changes
     * - txn status changes
     */
    @Query(
        """
    SELECT *
    FROM dispense_txn
    WHERE isDeleted = 0
      AND status = :completedStatus
      AND (isSynced IS NULL OR isSynced = 0)
      AND (drugId IS NULL OR drugId IN (SELECT drugId FROM drug_master WHERE region = 'US'))
      AND (rxNo IS NULL OR rxNo NOT LIKE '${TutorialData.RX_PREFIX}%')
    ORDER BY updatedAt ASC
    """
    )
    fun observePendingHl7Txn(
        completedStatus: CountStatus = CountStatus.COMPLETED
    ): Flow<List<DispenseTxnEntity>>


    /**
     * One-shot fetch (non-reactive) for resend-on-connect logic.
     *
     * Doesn't filter on isComingFromHL7: every row here is a dispense, and requiring
     * isComingFromHL7 = 1 silently excluded dispenses started by
     * scanning an Rx label on the device — those are 0, so they stayed unsynced forever and
     * the PMS never received them.
     * Only US dispenses: HL7 is US-only.
     */
    @Query(
        """
        SELECT *
        FROM dispense_txn
        WHERE isDeleted = 0
          AND status = :completedStatus
          AND (isSynced IS NULL OR isSynced = 0)
          AND (drugId IS NULL OR drugId IN (SELECT drugId FROM drug_master WHERE region = 'US'))
          AND (drugId IS NULL OR drugId NOT IN (SELECT drugId FROM drug_master WHERE ndc = :excludeNdc))
          AND (rxNo IS NULL OR rxNo NOT LIKE '${TutorialData.RX_PREFIX}%')
        ORDER BY updatedAt ASC
        """
    )
    suspend fun getPendingHl7TxnOnce(
        completedStatus: CountStatus = CountStatus.COMPLETED,
        excludeNdc: String = TutorialData.DEMO_NDC
    ): List<DispenseTxnEntity>


    /**
     * Mark transaction as synced after ACK is received.
     */
    @Query(
        """
        UPDATE dispense_txn
        SET isSynced = 1,
            updatedAt = :now
        WHERE txnId = :txnId
        """
    )
    suspend fun markTxnSynced(
        txnId: Long,
        now: Long = System.currentTimeMillis()
    )


    @Query(
        """
    UPDATE dispense_txn
    SET
        status = :status,
        isSynced = 0,
        updatedAt = :now
    WHERE txnId = :txnId
    """
    )
    suspend fun markCompletedAndUnsynced(
        txnId: Long,
        status: CountStatus,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
    SELECT txn.txnId,
           txn.createdAt,
           txn.targetCount,
           txn.bottlesJson,
           txn.isComingFromHL7,
           txn.isNdcVerified,
           txn.bucketId,
           txn.rxNo,
           txn.refillNo,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugId IS NOT NULL
                THEN subDrug.drugName ELSE drug.drugName END AS drugName,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
                THEN subDrug.ndc ELSE drug.ndc END AS ndc,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
                THEN subDrug.drugType ELSE drug.drugType END AS drugType,
           IFNULL(CASE WHEN txn.isSubstitute = 1 AND subDrug.isHazardous IS NOT NULL
                       THEN subDrug.isHazardous ELSE drug.isHazardous END, 0) AS isHazardous,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.strength IS NOT NULL
                THEN subDrug.strength ELSE drug.strength END AS strength,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.dosageForm IS NOT NULL
                THEN subDrug.dosageForm ELSE drug.dosageForm END AS dosageForm,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugImagePath IS NOT NULL
                THEN subDrug.drugImagePath ELSE drug.drugImagePath END AS drugImagePath,
           IFNULL(SUM(details.pillCount), 0) AS totalPillCount
    FROM dispense_txn AS txn
    LEFT JOIN drug_master AS drug
           ON txn.drugId = drug.drugId
    LEFT JOIN drug_master AS subDrug
           ON txn.substitutedDrugId = subDrug.drugId
    LEFT JOIN dispense_count AS details
           ON txn.txnId = details.txnId
          AND details.step = :step
    WHERE txn.isDeleted = 0
      AND (txn.status = :completeStatus OR txn.status = :forceCompleteStatus)
      AND txn.isSynced = 0
      AND (txn.rxNo IS NULL OR txn.rxNo NOT LIKE '${TutorialData.RX_PREFIX}%')
      AND IFNULL(drug.region, 'US') = :region
    GROUP BY txn.txnId
    ORDER BY txn.createdAt DESC
    """
    )
    fun observeUnsyncedDispenses(
        region: Region,
        completeStatus: CountStatus = CountStatus.COMPLETED,
        forceCompleteStatus: CountStatus = CountStatus.FORCE_COMPLETED,
        step: String
    ): Flow<List<DispenseTxnWithDrug>>

    @Query(
        """
    SELECT COUNT(*)
    FROM dispense_txn AS txn
    LEFT JOIN drug_master AS drug
           ON txn.drugId = drug.drugId
    WHERE txn.isDeleted = 0
      AND (txn.status = :completeStatus OR txn.status = :forceCompleteStatus)
      AND txn.isSynced = 0
      AND txn.isComingFromHL7 = 1
      AND (txn.rxNo IS NULL OR txn.rxNo NOT LIKE '${TutorialData.RX_PREFIX}%')
      AND IFNULL(drug.region, 'US') = :region
    """
    )
    fun getTotalCompletedTransactionCount(
        region: Region,
        completeStatus: CountStatus = CountStatus.COMPLETED,
        forceCompleteStatus: CountStatus = CountStatus.FORCE_COMPLETED
    ): Flow<Int>

    @Query(
        """
    SELECT COUNT(*) FROM dispense_txn
    WHERE isDeleted = 0
      AND status = :partialStatus
      AND userLocalId = :userLocalId
      AND (rxNo IS NULL OR rxNo NOT LIKE '${TutorialData.RX_PREFIX}%')
      AND (drugId IS NULL OR drugId NOT IN (SELECT drugId FROM drug_master WHERE ndc = '${TutorialData.DEMO_NDC}'))
    """
    )
    suspend fun countPartialDispenses(
        partialStatus: CountStatus = CountStatus.PARTIAL,
        userLocalId: Long,
    ): Int

}
