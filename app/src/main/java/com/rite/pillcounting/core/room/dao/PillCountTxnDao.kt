package com.rite.pillcounting.core.room.dao

import android.R
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.dtos.PillCountWithDrugAndTotal
import com.rite.pillcounting.core.room.models.dtos.StatusTypeCount
import com.rite.pillcounting.core.room.models.dtos.TxnWithDetails
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import kotlinx.coroutines.flow.Flow

/**
 * **Pill Count Transaction Data Access Object**
 *
 * Provides database operations for managing [PillCountTxnEntity] entries — the transactional
 * headers representing each pill counting event.
 *
 * ---
 * ### Key Design Principles
 * - **Txn ID Stability:** Transaction primary keys (`txnId`) must remain persistent
 *   to preserve foreign key relationships in related tables (e.g., `pill_count_txn_details`).
 * - **Safe Upserts:** Avoid destructive operations such as `REPLACE` which delete and recreate rows.
 * - **Reactive Observability:** Queries returning [Flow] provide live updates for dashboards or lists.
 * - **Soft Delete Policy:** Records are not physically deleted unless explicitly required.
 *
 * ---
 * ### Associated Tables
 * - `pill_count_txn` — Main transaction header table.
 * - `pill_count_txn_details` — Line-level pill count details.
 * - `drug_master` — Reference table for drug metadata.
 */
@Dao
interface PillCountTxnDao {

    // ────────────────────────────── Create / Update ──────────────────────────────

    /**
     * Inserts a new [PillCountTxnEntity] into the database.
     *
     * - Uses [OnConflictStrategy.IGNORE] to prevent overwriting existing transactions.
     * - If a transaction with the same [PillCountTxnEntity.txnId] already exists,
     *   the operation will be ignored and return `-1`.
     *
     * @param txn The transaction entity to insert.
     * @return The new row ID (txnId) if inserted successfully, or `-1` if a conflict occurred.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(txn: PillCountTxnEntity): Long

    /**
     * Updates an existing transaction entry matched by its [PillCountTxnEntity.txnId].
     *
     * @param txn The modified transaction entity.
     */
    @Update
    suspend fun update(txn: PillCountTxnEntity)

    /**
     * Performs a **safe upsert** (insert or update) while preserving the [txnId].
     *
     * This ensures transactional integrity — if the record exists, it is updated in place.
     * Otherwise, a new transaction row is inserted.
     *
     * @param txn The transaction to insert or update.
     * @return The stable [PillCountTxnEntity.txnId] of the inserted or updated record.
     * @throws IllegalStateException if the insert fails unexpectedly.
     */
    @Transaction
    suspend fun upsertPreservingId(txn: PillCountTxnEntity): Long {
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
            // Log the error and rethrow a more descriptive one or handle it
            throw IllegalArgumentException("Foreign key constraint failed: Ensure User, Drug, and Batch exist before creating a transaction. ${e.message}")
        }
    }

    // ──────────────────────────────── Reads ────────────────────────────────

    /**
     * Fetches a transaction entity by its primary key.
     *
     * @param id The unique transaction ID.
     * @return The matching [PillCountTxnEntity] if found, or `null` otherwise.
     */
    @Query("SELECT * FROM pill_count_txn WHERE txnId = :id LIMIT 1")
    suspend fun getById(id: Long): PillCountTxnEntity?

    /**
     * Observes all **partial transactions** for a specific [CountType],
     * along with their associated drug names and total pill counts.
     *
     * - Useful for showing "in-progress" transactions on a dashboard.
     * - Excludes deleted records.
     *
     * @param countType The count type (e.g., CYCLE_COUNT, SPOT_COUNT).
     * @param partialStatus Optional filter, defaults to [CountStatus.PARTIAL].
     * @return A [Flow] emitting a live list of [PillCountWithDrugAndTotal].
     */
    @Query(
        """
    SELECT txn.txnId,
           txn.createdAt,
           txn.targetCount,
           txn.barcodeImage,
           txn.isComingFromHL7,
           txn.isNdcVerified,
           txn.bucketId,
           txn.countType,
           txn.priority,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugName IS NOT NULL
                THEN subDrug.drugName ELSE drug.drugName END AS drugName,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
                THEN subDrug.ndc ELSE drug.ndc END AS ndc,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
                THEN subDrug.drugType ELSE drug.drugType END AS drugType,
           IFNULL(CASE WHEN txn.isSubstitute = 1 AND subDrug.isHazardous IS NOT NULL
                       THEN subDrug.isHazardous ELSE drug.isHazardous END, 0) AS isHazardous,
           IFNULL(SUM(details.pillCount), 0) AS totalPillCount
    FROM pill_count_txn AS txn
    LEFT JOIN drug_master AS drug
           ON txn.drugId = drug.drugId
    LEFT JOIN drug_master AS subDrug
           ON txn.substitutedDrugId = subDrug.drugId
    LEFT JOIN pill_count_txn_details AS details
           ON txn.txnId = details.txnId
          AND details.isDeleted = 0
          AND details.type = :type
    WHERE txn.isDeleted = 0
      AND txn.status = :partialStatus
      AND txn.countType = :countType
      AND txn.localId = :userLocalId
    GROUP BY txn.txnId
    ORDER BY CASE txn.priority
                 WHEN 'High'   THEN 1
                 WHEN 'Medium' THEN 2
                 WHEN 'Low'    THEN 3
                 ELSE 2
             END ASC, /* values correspond to TxnPriority enum names */
             txn.isComingFromHL7 DESC,
             txn.createdAt DESC
    """
    )

    fun observePartialByCountType(
        countType: CountType,
        partialStatus: CountStatus = CountStatus.PARTIAL,
        userLocalId: Long,
        type: StepState
    ): Flow<List<PillCountWithDrugAndTotal>>

    // ───────────────────────────── Field Updates ─────────────────────────────

    /**
     * Updates the **target count** value for a given transaction.
     *
     * @param txnId The transaction ID.
     * @param target The new target count, or `null` to clear.
     * @param now Optional timestamp; defaults to [System.currentTimeMillis].
     */
    @Query("UPDATE pill_count_txn SET targetCount = :target, updatedAt = :now WHERE txnId = :txnId")
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
    @Query("UPDATE pill_count_txn SET note = :note, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateNote(
        txnId: Long,
        note: String?,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Performs a soft delete by setting `isDeleted = 1`.
     * This preserves record history and maintains referential integrity.
     *
     * @param txnId The transaction ID.
     * @param now Optional timestamp; defaults to [System.currentTimeMillis].
     */
    @Query("UPDATE pill_count_txn SET isDeleted = 1, updatedAt = :now WHERE txnId = :txnId")
    suspend fun softDelete(
        txnId: Long,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE pill_count_txn SET isDeleted = 1, updatedAt = :now WHERE rxNo = :rxNo AND isDeleted = 0")
    suspend fun softDeleteByRxNo(
        rxNo: String,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        SELECT * FROM pill_count_txn
        WHERE rxNo = :rxNo
          AND isDeleted = 1
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getDeletedByRxNo(rxNo: String): PillCountTxnEntity?

    @Query(
        """
        UPDATE pill_count_txn
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
     * @return The matching [PillCountTxnEntity] if one exists, or `null`.
     */
    @Query(
        """
        SELECT * FROM pill_count_txn
        WHERE rxNo    = :rxNo
          AND isDeleted = 0
          AND status  IN ('PARTIAL', 'ON_HOLD')
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getActiveByRxNo(rxNo: String): PillCountTxnEntity?

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
        UPDATE pill_count_txn
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

    @Query("DELETE FROM pill_count_txn")
    suspend fun deleteAllTransactions()

    // ─────────────────────────────── Relations ───────────────────────────────

    /**
     * Observes a summary count of transactions grouped by [CountStatus] and [CountType].
     *
     * Used for real-time dashboard tiles showing how many transactions are
     * "Pending", "Partial", "Completed", etc., for each counting type.
     *
     * @return A [Flow] emitting lists of [StatusTypeCount] aggregates.
     */
    @Query(
        """
    SELECT status AS status,
           countType AS countType,
           COUNT(*) AS cnt
    FROM pill_count_txn
    WHERE isDeleted = 0
      AND localId = :userLocalId
    GROUP BY status, countType
    """
    )
    fun observeDashboardCountsGrouped(userLocalId: Long): Flow<List<StatusTypeCount>>

    /**
     * Retrieves a detailed transaction with its associated drug and total pill count.
     *
     * Joins data from:
     * - `drug_master` for drug name and NDC.
     * - `pill_count_txn_details` for individual pill counts.
     *
     * @param transactionId The ID of the transaction to fetch.
     * @return A [TxnWithDetails] DTO containing enriched transaction data, or `null` if not found.
     */
    @Transaction
    @Query(
        """
    SELECT
        pct.txnId,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.drugName IS NOT NULL
             THEN subDrug.drugName ELSE dm.drugName END AS drugName,
        IFNULL(CASE WHEN pct.isSubstitute = 1 AND subDrug.drugId IS NOT NULL
             THEN subDrug.drugId ELSE dm.drugId END, 0) AS drugId,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
             THEN subDrug.ndc ELSE dm.ndc END AS ndc,
        pct.targetCount,
        pct.expiry,
        pct.lotNo,
        pct.note,
        pct.createdAt,
        pct.barcodeImage,
        pct.isComingFromHL7,
        pct.countType,
        CASE WHEN pct.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
             THEN subDrug.drugType ELSE dm.drugType END AS drugType,
        IFNULL(SUM(pcd.pillCount), 0) AS totalPillCount,
        pct.isSubstitute,
        dm.drugName AS requestedDrugName,
        dm.ndc AS requestedNdc,
        pct.workflowStep
    FROM pill_count_txn AS pct
    LEFT JOIN drug_master AS dm
        ON pct.drugId = dm.drugId
    LEFT JOIN drug_master AS subDrug
        ON pct.substitutedDrugId = subDrug.drugId
    LEFT JOIN pill_count_txn_details AS pcd
        ON pct.txnId = pcd.txnId AND pcd.isDeleted = 0
    WHERE pct.txnId = :transactionId AND pct.isDeleted = 0
    GROUP BY pct.txnId
    """
    )
    suspend fun getTxnWithDetails(transactionId: Long): TxnWithDetails?

    /**
     * Adds [qty] to the `looseQty` of a transaction (treating NULL as 0).
     *
     * Called each time an ADD is confirmed during a REGULAR count so that the
     * transaction header always reflects the running loose-pill total.
     *
     * @param txnId The transaction ID.
     * @param qty   The pill count to add.
     * @param now   Timestamp; defaults to [System.currentTimeMillis].
     */
    @Query("UPDATE pill_count_txn SET looseQty = IFNULL(looseQty, 0) + :qty, updatedAt = :now WHERE txnId = :txnId")
    suspend fun incrementLooseQty(
        txnId: Long,
        qty: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE pill_count_txn SET workflowStep = :step, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateWorkflowStep(
        txnId: Long,
        step: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE pill_count_txn SET isGlovesPresent = :value, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateGlovesPresent(
        txnId: Long,
        value: Boolean,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE pill_count_txn SET hazardousTrayDetected = :detected, updatedAt = :now WHERE txnId = :txnId")
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
    @Query("UPDATE pill_count_txn SET status = :newStatus, updatedAt = :updatedAt WHERE txnId = :txnId")
    suspend fun updateTxnStatus(
        txnId: Long,
        newStatus: CountStatus,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * Returns all transactions within a given date range, joined with drug details and totals.
     *
     * Useful for generating daily reports or summaries.
     *
     * @param startOfDay Start timestamp (inclusive).
     * @param endOfDay End timestamp (exclusive).
     * @return A [Flow] emitting a list of [TxnWithDrugDto] for the date window.
     */
    @Query(
        """
    SELECT
        txn.txnId,
        txn.batchId,
        txn.countType,
        txn.status,
        COALESCE(SUM(details.pillCount), 0) AS pillCount,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.drugName IS NOT NULL
             THEN subDrug.drugName ELSE drug.drugName END AS drugName,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
             THEN subDrug.ndc ELSE drug.ndc END AS ndc,
        txn.barcodeImage,
        txn.createdAt,
        txn.targetCount,
        txn.note,
        txn.bucketId,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
             THEN subDrug.drugType ELSE drug.drugType END AS drugType
    FROM pill_count_txn AS txn
    LEFT JOIN pill_count_txn_details AS details
           ON txn.txnId = details.txnId AND details.isDeleted = 0
    LEFT JOIN drug_master AS drug
           ON txn.drugId = drug.drugId
    LEFT JOIN drug_master AS subDrug
           ON txn.substitutedDrugId = subDrug.drugId
    WHERE txn.createdAt >= :startOfDay
      AND txn.createdAt < :endOfDay
      AND txn.isDeleted = 0
      AND (:type IS NULL OR txn.countType = :type)
      AND (:status IS NULL OR txn.status = :status)
    GROUP BY txn.txnId
    ORDER BY txn.createdAt DESC
    """
    )
    fun getTransactionsWithDrugByDate(
        startOfDay: Long,
        endOfDay: Long,
        type: CountType?,
        status: CountStatus?
    ): Flow<List<TxnWithDrugDto>>

    @Query(
        """
    SELECT
        txn.txnId,
        txn.batchId,
        txn.countType,
        txn.status,
        COALESCE(SUM(details.pillCount), 0) AS pillCount,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.drugName IS NOT NULL
             THEN subDrug.drugName ELSE drug.drugName END AS drugName,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
             THEN subDrug.ndc ELSE drug.ndc END AS ndc,
        txn.barcodeImage,
        txn.createdAt,
        txn.targetCount,
        txn.note,
        txn.bucketId,
        CASE WHEN txn.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
             THEN subDrug.drugType ELSE drug.drugType END AS drugType
    FROM pill_count_txn AS txn
    LEFT JOIN pill_count_txn_details AS details
           ON txn.txnId = details.txnId
           AND details.isDeleted = 0
           AND (:stepType IS NULL OR details.type = :stepType)
    LEFT JOIN drug_master AS drug
           ON txn.drugId = drug.drugId
    LEFT JOIN drug_master AS subDrug
           ON txn.substitutedDrugId = subDrug.drugId
    WHERE txn.createdAt BETWEEN :startDate AND :endDate
      AND txn.isDeleted = 0
      AND txn.localId = :userLocalId
      AND (:type IS NULL OR txn.countType = :type)
      AND (:status IS NULL OR txn.status = :status)
    GROUP BY txn.txnId
    ORDER BY txn.createdAt DESC
    """
    )
    fun getTransactionsForDateRange(
        startDate: Long,
        endDate: Long,
        stepType: StepState?,
        type: CountType?,
        status: CountStatus?,
        userLocalId: Long
    ): Flow<List<TxnWithDrugDto>>


    // ─────────────────────────────── Deletes ───────────────────────────────

    /**
     * Permanently deletes all transactions created within a date range.
     *
     * @param start Start timestamp (inclusive).
     * @param end End timestamp (inclusive).
     */
    @Query(
        """
    DELETE FROM pill_count_txn
    WHERE createdAt >= :start
      AND createdAt < :end
      AND localId = :userLocalId
      AND (:type IS NULL OR countType = :type)
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
        type: CountType?,
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
     * @return A list of [PillCountTxnEntity].
     */
    @Query("SELECT * FROM pill_count_txn WHERE createdAt < :cutoff AND (status = 'COMPLETED' OR status = 'FORCE_COMPLETED')")
    suspend fun getTransactionsBefore(cutoff: Long): List<PillCountTxnEntity>

    /**
     * Retrieves the file paths of images linked to all transaction details under a given transaction.
     *
     * @param txnId The transaction ID.
     * @return A list of image file paths.
     */
    @Query("SELECT imagePath FROM pill_count_txn_details WHERE txnId = :txnId")
    suspend fun getTransactionDetailsImages(txnId: Long): List<String>

    /**
     * Permanently deletes a single transaction by its ID.
     *
     * ⚠️ **Note:** This action cannot be undone.
     *
     * @param txnId The transaction ID to delete.
     */
    @Query("DELETE FROM pill_count_txn WHERE txnId = :txnId")
    suspend fun deleteTransaction(txnId: Long)

    @Query("DELETE FROM pill_count_txn WHERE batchId IN (:batchIds)")
    suspend fun deleteTransactionsByBatchIds(batchIds: List<Long>)

    @Query("SELECT COUNT(DISTINCT drugId) FROM pill_count_txn WHERE batchId = :batchId AND isDeleted = 0 AND localId = :userLocalId")
    suspend fun getUniqueNdcCountForBatch(batchId: Long, userLocalId: Long): Int

    /**
     * Observes all transactions belonging to a batch, joined with drug name and NDC.
     *
     * Used to power the BatchScreen UI, grouping transactions by drug.
     *
     * @param batchId The batch ID to filter by.
     * @return A [Flow] emitting a live list of [BatchTxnDto].
     */
    @Query(
        """
    SELECT
        txn.txnId,
        txn.drugId,
        dm.drugName,
        dm.ndc,
        txn.lotNo,
        txn.expiry,
        txn.bottleQty,
        txn.looseQty,
        dm.packageQty
    FROM pill_count_txn AS txn
    LEFT JOIN drug_master AS dm ON txn.drugId = dm.drugId
    WHERE txn.batchId = :batchId AND txn.isDeleted = 0
    ORDER BY dm.drugName ASC
    """
    )
    fun observeByBatchId(batchId: Long): Flow<List<BatchTxnDto>>

    /**
     * One-shot fetch of all transactions for a batch, joined with drug data.
     * Used for building HL7 inventory response messages.
     */
    @Query(
        """
    SELECT
        txn.txnId,
        txn.drugId,
        dm.drugName,
        dm.ndc,
        txn.lotNo,
        txn.expiry,
        txn.bottleQty,
        txn.looseQty,
        dm.packageQty
    FROM pill_count_txn AS txn
    LEFT JOIN drug_master AS dm ON txn.drugId = dm.drugId
    WHERE txn.batchId = :batchId AND txn.isDeleted = 0
    ORDER BY dm.drugName ASC
    """
    )
    suspend fun getTxnsByBatchId(batchId: Long): List<BatchTxnDto>

    /**
     * Observes the count of distinct NDCs (drug groups) in a batch.
     * Mirrors the number of drug group cards shown in BatchScreen.
     */
    @Query("""
        SELECT COUNT(DISTINCT txn.drugId)
        FROM pill_count_txn AS txn
        WHERE txn.batchId = :batchId AND txn.isDeleted = 0
    """)
    fun observeTotalCountByBatchId(batchId: Long): Flow<Int>


    /**
     * Finds an existing STOCK_COUNT transaction under the same batch with matching drug,
     * lot number, and expiry — used to merge sealed container quantities.
     */
    @Query("""
        SELECT * FROM pill_count_txn
        WHERE batchId = :batchId
          AND drugId = :drugId
          AND (lotNo = :lotNo OR (lotNo IS NULL AND :lotNo IS NULL))
          AND (expiry = :expiry OR (expiry IS NULL AND :expiry IS NULL))
          AND isDeleted = 0
        LIMIT 1
    """)
    suspend fun findSealedTxnInBatch(
        batchId: Long,
        drugId: String,
        lotNo: String?,
        expiry: String?
    ): PillCountTxnEntity?

    /**
     * Latest sealed txn for a given NDC in a batch — used when the user taps a
     * recent-counts row to re-activate that NDC. Falls back to the most-recently
     * updated row when multiple (lot, expiry) variants exist for the same drug.
     */
    @Query("""
        SELECT txn.* FROM pill_count_txn AS txn
        LEFT JOIN drug_master AS dm ON txn.drugId = dm.drugId
        WHERE txn.batchId = :batchId
          AND dm.ndc = :ndc
          AND txn.isDeleted = 0
        ORDER BY txn.updatedAt DESC
        LIMIT 1
    """)
    suspend fun findLatestTxnByNdcInBatch(batchId: Long, ndc: String): PillCountTxnEntity?

    /**
     * PMS validation query: finds a pre-loaded PMS transaction in the batch for the given drug.
     *
     * Null-tolerant matching rules:
     * - If txn.lotNo IS NULL  → accept any scanned lot  (lot not specified by PMS)
     * - If txn.lotNo NOT NULL → scanned lot must match exactly
     * - Same rule applies to expiry.
     *
     * This lets PMS batches that have no lot/expiry data validate by NDC alone,
     * while still enforcing lot+expiry when PMS does provide them.
     */
    @Query("""
        SELECT * FROM pill_count_txn
        WHERE batchId = :batchId
          AND drugId = :drugId
          AND isDeleted = 0
        LIMIT 1
    """)
    suspend fun findPmsTxnInBatch(
        batchId: Long,
        drugId: String,
    ): PillCountTxnEntity?


    /**
     * Observe HL7 transactions that are completed but NOT synced with PMS.
     *
     * This Flow emits whenever:
     * - a new HL7 txn is completed
     * - isSynced changes
     * - txn status changes
     */
    @Query(
        """
    SELECT *
    FROM pill_count_txn
    WHERE isDeleted = 0
      AND isComingFromHL7 = 1
      AND status = :completedStatus
      AND (isSynced IS NULL OR isSynced = 0)
    ORDER BY updatedAt ASC
    """
    )
    fun observePendingHl7Txn(
        completedStatus: CountStatus = CountStatus.COMPLETED
    ): Flow<List<PillCountTxnEntity>>


    /**
     * One-shot fetch (non-reactive) for resend-on-connect logic.
     */
    @Query(
        """
        SELECT *
        FROM pill_count_txn
        WHERE isDeleted = 0
          AND isComingFromHL7 = 1
          AND status = :completedStatus
          AND (isSynced IS NULL OR isSynced = 0)
        ORDER BY updatedAt ASC
        """
    )
    suspend fun getPendingHl7TxnOnce(
        completedStatus: CountStatus = CountStatus.COMPLETED
    ): List<PillCountTxnEntity>


    /**
     * Mark transaction as synced after ACK is received.
     */
    @Query(
        """
        UPDATE pill_count_txn
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
    UPDATE pill_count_txn
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
           txn.barcodeImage,
           txn.isComingFromHL7,
           txn.isNdcVerified,
           txn.bucketId,
           txn.countType,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugName IS NOT NULL
                THEN subDrug.drugName ELSE drug.drugName END AS drugName,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.ndc IS NOT NULL
                THEN subDrug.ndc ELSE drug.ndc END AS ndc,
           CASE WHEN txn.isSubstitute = 1 AND subDrug.drugType IS NOT NULL
                THEN subDrug.drugType ELSE drug.drugType END AS drugType,
           IFNULL(CASE WHEN txn.isSubstitute = 1 AND subDrug.isHazardous IS NOT NULL
                       THEN subDrug.isHazardous ELSE drug.isHazardous END, 0) AS isHazardous,
           IFNULL(SUM(details.pillCount), 0) AS totalPillCount
    FROM pill_count_txn AS txn
    LEFT JOIN drug_master AS drug
           ON txn.drugId = drug.drugId
    LEFT JOIN drug_master AS subDrug
           ON txn.substitutedDrugId = subDrug.drugId
    LEFT JOIN pill_count_txn_details AS details
           ON txn.txnId = details.txnId
          AND details.isDeleted = 0
          AND details.type = :type
    WHERE txn.isDeleted = 0
      AND (txn.status = :completeStatus OR txn.status = :forceCompleteStatus)
      AND txn.countType = :countType
      AND txn.isSynced = 0
    GROUP BY txn.txnId
    ORDER BY txn.createdAt DESC
    """
    )
    fun observeUnsyncedByCountType(
        countType: CountType,
        completeStatus: CountStatus = CountStatus.COMPLETED,
        forceCompleteStatus: CountStatus = CountStatus.FORCE_COMPLETED,
        type: String
    ): Flow<List<PillCountWithDrugAndTotal>>

    @Query(
        """
    SELECT COUNT(*) 
    FROM pill_count_txn
    WHERE isDeleted = 0
      AND (status = :completeStatus OR status = :forceCompleteStatus)
      AND isSynced = 0
      AND isComingFromHL7 = 1
    """
    )
    fun getTotalCompletedTransactionCount(
        completeStatus: CountStatus = CountStatus.COMPLETED,
        forceCompleteStatus: CountStatus = CountStatus.FORCE_COMPLETED
    ): Flow<Int>

}
