package com.dispensesure.retail.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto
import com.dispensesure.retail.core.room.models.dtos.InventoryKpiCountsDto
import com.dispensesure.retail.core.room.models.dtos.RecordSyncState
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.tourguide.TutorialData
import kotlinx.coroutines.flow.Flow

/**
 * **Batch Data Access Object**
 *
 * Provides database operations for managing [StockBatchEntity] entries — representing
 * batches that group multiple pill count transactions together.
 *
 * ### Key Design Principles
 * - **Batch ID Stability:** Batch primary keys (`batchId`) must remain persistent
 *   to preserve relationships with other tables.
 * - **Soft Delete Policy:** Records are marked as deleted (isDeleted = true) rather
 *   than physically removed from the database.
 * - **Reactive Observability:** Queries returning [Flow] provide live updates.
 *
 * ### Associated Tables
 * - `stock_batch` — Main batch table.
 */
@Dao
interface StockBatchDao {

    // ────────────────────────────── Create / Insert ──────────────────────────────

    /**
     * Inserts a new [StockBatchEntity] into the database.
     *
     * - Uses [OnConflictStrategy.IGNORE] to prevent overwriting existing batches.
     * - If a batch with the same [StockBatchEntity.batchId] already exists,
     *   the operation will be ignored and return `-1`.
     *
     * @param batch The [StockBatchEntity] to insert.
     * @return The row ID of the newly inserted batch, or `-1` if conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(batch: StockBatchEntity): Long

    /**
     * Inserts multiple [StockBatchEntity] records.
     *
     * @param batches List of batches to insert.
     * @return List of row IDs for inserted batches.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(batches: List<StockBatchEntity>): List<Long>

    // ────────────────────────────── Read / Query ──────────────────────────────

    /**
     * Retrieves a batch by its ID.
     *
     * @param batchId The primary key of the batch.
     * @return The [StockBatchEntity] with the given ID, or null if not found.
     */
    @Query("SELECT * FROM stock_batch WHERE batchId = :batchId")
    suspend fun getById(batchId: Long): StockBatchEntity?

    @Query("SELECT * FROM stock_batch WHERE batchId = :batchId LIMIT 1")
    fun observeById(batchId: Long): Flow<StockBatchEntity?>

    /**
     * Retrieves all non-deleted batches ordered by creation date (newest first).
     *
     * @return A [Flow] emitting list of all active batches.
     */
    @Query("SELECT * FROM stock_batch WHERE isDeleted = 0 ORDER BY startDateTime DESC")
    fun getAllActive(): Flow<List<StockBatchEntity>>

    /**
     * Retrieves all non-deleted batches filtered by [status], ordered newest first.
     *
     * @param status The [BatchStatus] to filter by.
     * @return A [Flow] emitting the filtered list of batches.
     */
    @Query("""
        SELECT * FROM stock_batch
        WHERE isDeleted = 0
        AND status = :status
        ORDER BY startDateTime DESC
    """)
    fun getAllByStatus(status: BatchStatus): Flow<List<StockBatchEntity>>

    /** Convenience wrapper — returns only [BatchStatus.INPROGRESS] batches. */
    fun getAllInProgress() = getAllByStatus(BatchStatus.INPROGRESS)

    /**
     * Retrieves the most recently created batch with the given [status].
     *
     * @param status The [BatchStatus] to filter by.
     * @param region Region of the logged-in user.
     * @return The latest matching [StockBatchEntity], or null if none exist.
     */
    @Query("SELECT * FROM stock_batch WHERE isDeleted = 0 AND status = :status AND region = :region ORDER BY startDateTime DESC LIMIT 1")
    suspend fun getLatestByStatus(status: BatchStatus, region: Region): StockBatchEntity?

    /** Latest in-progress batch of [region]. */
    suspend fun getLatest(region: Region) = getLatestByStatus(BatchStatus.INPROGRESS, region)

    // ────────────────────────────── Delete ──────────────────────────────

    /**
     * Soft-deletes a batch by setting isDeleted to true.
     *
     * @param batchId The ID of the batch to soft-delete.
     * @return The number of rows affected.
     */
    @Query("UPDATE stock_batch SET isDeleted = 1 WHERE batchId = :batchId")
    suspend fun softDelete(batchId: Long): Int

    /**
     * Hard-deletes a batch row — tutorial-created batches only. `stock_txn`/`stock_bottle_info` use
     * `onDelete = SET_NULL`, not `CASCADE`, so callers must delete those first or they orphan.
     */
    @Query("DELETE FROM stock_batch WHERE batchId = :batchId")
    suspend fun deleteById(batchId: Long)

    /**
     * Marks a batch as completed by setting status to COMPLETED and recording endDateTime.
     *
     * @param batchId The ID of the batch to complete.
     * @return The number of rows affected.
     */
    @Query("UPDATE stock_batch SET status = 'COMPLETED', endDateTime = :endDateTime WHERE batchId = :batchId")
    suspend fun markAsCompleted(batchId: Long, endDateTime: Long = System.currentTimeMillis()): Int

    @Query("UPDATE stock_batch SET note = :note WHERE batchId = :batchId")
    suspend fun updateNote(batchId: Long, note: String?): Int

    // ────────────────────────────── Utility ──────────────────────────────

    /**
     * Gets the count of non-deleted batches.
     *
     * @return The total number of active batches.
     */
    @Query("SELECT COUNT(*) FROM stock_batch WHERE isDeleted = 0")
    suspend fun getActiveCount(): Long

    /** @return Rows deleted, recorded on LOCAL_DATA_CLEARED. */
    @Query("DELETE FROM stock_batch")
    suspend fun deleteAll(): Int

    /** Sync state of the given batches, read before a delete so the audit event can list them. */
    @Query("SELECT batchId AS id, isSynced, (status = :completedStatus) AS isCompleted FROM stock_batch WHERE batchId IN (:batchIds)")
    suspend fun getSyncStatesByIds(
        batchIds: List<Long>,
        completedStatus: BatchStatus = BatchStatus.COMPLETED
    ): List<RecordSyncState>

    /** Every batch's sync state, read before "Clear all local data". */
    @Query("SELECT batchId AS id, isSynced, (status = :completedStatus) AS isCompleted FROM stock_batch")
    suspend fun getAllSyncStates(completedStatus: BatchStatus = BatchStatus.COMPLETED): List<RecordSyncState>

    @Query("""
        SELECT batchId FROM stock_batch
        WHERE isDeleted = 0
          AND startDateTime BETWEEN :start AND :end
          AND region = :region
          AND (
            :isCompleted IS NULL
            OR (:isCompleted = 1 AND status = :completedStatus)
            OR (:isCompleted = 0 AND status = :inProgressStatus)
          )
    """)
    suspend fun getBatchIdsByDate(
        start: Long,
        end: Long,
        isCompleted: Boolean?,
        region: Region,
        completedStatus: BatchStatus = BatchStatus.COMPLETED,
        inProgressStatus: BatchStatus = BatchStatus.INPROGRESS
    ): List<Long>

    @Query("""
        UPDATE stock_batch
        SET isDeleted = 1
        WHERE isDeleted = 0
          AND startDateTime BETWEEN :start AND :end
          AND region = :region
          AND (
            :isCompleted IS NULL
            OR (:isCompleted = 1 AND status = :completedStatus)
            OR (:isCompleted = 0 AND status = :inProgressStatus)
          )
    """)
    suspend fun softDeleteBatchesByDate(
        start: Long,
        end: Long,
        isCompleted: Boolean?,
        region: Region,
        completedStatus: BatchStatus = BatchStatus.COMPLETED,
        inProgressStatus: BatchStatus = BatchStatus.INPROGRESS
    )

    /**
     * Returns all non-deleted batches whose [StockBatchEntity.startDateTime] falls within the given
     * range, LEFT JOINed with their transactions so that empty batches (no txns yet) still appear
     * with [BatchSummaryDto.uniqueNdcCount] = 0.
     *
     * Only [region]'s batches are returned.
     */
    @Query(
        """
    SELECT
        b.batchId,
        b.startDateTime AS createdAt,
        b.status AS status,
        b.bucketId AS bucketId,
        b.requestIdFromPMS AS requestIdFromPMS,
        COUNT(DISTINCT txn.drugId) AS uniqueNdcCount
    FROM stock_batch b
    LEFT JOIN stock_txn txn
        ON b.batchId = txn.batchId
        AND txn.isDeleted = 0
    WHERE b.isDeleted = 0
      AND b.startDateTime BETWEEN :startDate AND :endDate
      AND b.bucketId IS NOT '${TutorialData.BUCKET}'
      AND b.region = :region
    GROUP BY b.batchId
    ORDER BY b.startDateTime DESC
    """
    )
    fun getBatchSummaries(
        startDate: Long,
        endDate: Long,
        region: Region,
    ): Flow<List<BatchSummaryDto>>

    @Query(
        """
    SELECT
        b.batchId,
        b.startDateTime AS createdAt,
        b.status AS status,
        b.bucketId AS bucketId,
        b.requestIdFromPMS AS requestIdFromPMS,
        COUNT(DISTINCT txn.drugId) AS uniqueNdcCount
    FROM stock_batch b
    LEFT JOIN stock_txn txn
        ON b.batchId = txn.batchId
        AND txn.isDeleted = 0
    WHERE b.isDeleted = 0
      AND b.status = :completedStatus
      AND b.bucketId IS NOT '${TutorialData.BUCKET}'
      AND b.isSynced = 0
      AND b.region = :region
    GROUP BY b.batchId
    ORDER BY b.startDateTime DESC
    """
    )
    fun observeUnsyncedCompletedBatches(
        region: Region,
        completedStatus: BatchStatus = BatchStatus.COMPLETED
    ): Flow<List<BatchSummaryDto>>

    /**
     * In-progress batches with their distinct-NDC count, for Today's Queue
     * cards on the dashboard. LEFT JOIN keeps freshly-created (no-txn) batches
     * visible with uniqueNdcCount = 0; soft-deleted txns are excluded from the
     * count.
     */
    @Query(
        """
    SELECT
        b.batchId,
        b.startDateTime AS createdAt,
        b.status AS status,
        b.bucketId AS bucketId,
        b.requestIdFromPMS AS requestIdFromPMS,
        COUNT(DISTINCT txn.drugId) AS uniqueNdcCount
    FROM stock_batch b
    LEFT JOIN stock_txn txn
        ON b.batchId = txn.batchId
        AND txn.isDeleted = 0
    WHERE b.isDeleted = 0
      AND b.status = :inProgressStatus
      AND b.bucketId IS NOT '${TutorialData.BUCKET}'
      AND b.region = :region
    GROUP BY b.batchId
    ORDER BY b.startDateTime DESC
    """
    )
    fun observeInProgressBatchSummaries(
        region: Region,
        inProgressStatus: BatchStatus = BatchStatus.INPROGRESS
    ): Flow<List<BatchSummaryDto>>

    // Cycle Count = PMS-requested inventory counts. A batch carries a PMS
    // request id (requestIdFromPMS) only when it was created from an INR^U04
    // inventory request (StockHl7Repository.handleInrInventoryRequest); manually
    // started batches have it null. This is the same discriminator the
    // history / unsynced lists use to tag a batch as PMS-sourced.
    // Pending Batch = manually started (non-PMS) inventory batches.
    @Query(
        """
    SELECT
        COUNT(CASE WHEN requestIdFromPMS IS NOT NULL AND TRIM(requestIdFromPMS) <> '' THEN 1 END) AS cycleCount,
        COUNT(CASE WHEN requestIdFromPMS IS NULL OR TRIM(requestIdFromPMS) = '' THEN 1 END) AS pendingBatchCount
    FROM stock_batch
    WHERE isDeleted = 0
      AND status = :inProgressStatus
      AND bucketId IS NOT '${TutorialData.BUCKET}'
      AND region = :region
    """
    )
    fun observeInProgressBatchKpiCounts(
        region: Region,
        inProgressStatus: BatchStatus = BatchStatus.INPROGRESS
    ): Flow<InventoryKpiCountsDto>

    @Query(
        """
    SELECT
        b.batchId,
        b.startDateTime AS createdAt,
        b.status AS status,
        b.bucketId AS bucketId,
        b.requestIdFromPMS AS requestIdFromPMS,
        COUNT(DISTINCT txn.drugId) AS uniqueNdcCount
    FROM stock_batch b
    LEFT JOIN stock_txn txn
        ON b.batchId = txn.batchId
        AND txn.isDeleted = 0
    WHERE b.isDeleted = 0
      AND b.status = :completedStatus
      AND b.bucketId IS NOT '${TutorialData.BUCKET}'
      AND b.isSynced = 0
    GROUP BY b.batchId
    ORDER BY b.startDateTime DESC
    """
    )
    suspend fun getUnsyncedCompletedBatchesOnce(
        completedStatus: BatchStatus = BatchStatus.COMPLETED
    ): List<BatchSummaryDto>

    @Query(
        """
        SELECT batchId FROM stock_batch WHERE bucketId = '${TutorialData.BUCKET}'
        OR (batchId IN (SELECT batchId FROM stock_txn WHERE drugId IN (SELECT drugId FROM drug_master WHERE ndc = '${TutorialData.DEMO_NDC}'))
            AND batchId NOT IN (SELECT batchId FROM stock_txn
                                WHERE batchId IS NOT NULL AND (drugId IS NULL OR drugId NOT IN (SELECT drugId FROM drug_master WHERE ndc = '${TutorialData.DEMO_NDC}'))))
        """
    )
    suspend fun getTutorialBatchIds(): List<Long>

    @Query("UPDATE stock_batch SET isSynced = 1 WHERE batchId = :batchId")
    suspend fun markBatchSynced(batchId: Long)

    /** Records the total chunk count once a chunked inventory sync starts (idempotent across resumes). */
    @Query("UPDATE stock_batch SET totalChunks = :totalChunks WHERE batchId = :batchId")
    suspend fun setTotalChunks(batchId: Long, totalChunks: Int)

    /** Advances progress after a chunk's ACK succeeds — persisted immediately so a resume never resends it. */
    @Query("UPDATE stock_batch SET lastAckedChunkIndex = :chunkIndex WHERE batchId = :batchId")
    suspend fun markChunkAcked(batchId: Long, chunkIndex: Int)

    @Query("""
        SELECT COUNT(*)
        FROM stock_batch
        WHERE isDeleted = 0
          AND status = :completedStatus
          AND isSynced = 0
          AND bucketId IS NOT '${TutorialData.BUCKET}'
          AND region = :region
    """)
    fun getUnsyncedCompletedBatchCount(
        region: Region,
        completedStatus: BatchStatus = BatchStatus.COMPLETED
    ): Flow<Int>
}

/**
 * Inserts a fresh in-progress batch with `batchId`/`startDateTime` stamped to now.
 *
 * Description:
 * Single source of truth for the "mint a new batch" write. Used by every path that
 * lazily creates a batch on the first successful scan (Inventory quick action's
 * [com.dispensesure.retail.feature.stockCount.sealed.presentation.viewmodel.StockCountViewModel.ensureBatchCreated]
 * and the loose-count save in
 * [com.dispensesure.retail.feature.stockCount.data.StockCountRepository.commitLooseCount]).
 *
 * What it does:
 * - Builds a [StockBatchEntity] with `batchId = now`, `startDateTime = now`,
 *   `endDateTime = null`, `status = INPROGRESS`, `isDeleted = false`, `note = null`.
 * - Delegates to [StockBatchDao.insert]. Propagates any DAO exception — callers wrap
 *   in try/catch (ensure-variant) or rely on `withTransaction` to roll back.
 *
 * @param bucketId Optional bucket association for the new batch.
 * @param region Region of the logged-in user.
 * @return The new batchId returned by [StockBatchDao.insert].
 *
 * Example Usage:
 * val batchId = stockBatchDao.insertNewInProgressBatch(bucketId = "B1", region = Region.US)
 */
suspend fun StockBatchDao.insertNewInProgressBatch(bucketId: String?, region: Region): Long {
    val now = System.currentTimeMillis()
    return insert(
        StockBatchEntity(
            batchId = now,
            startDateTime = now,
            endDateTime = null,
            status = BatchStatus.INPROGRESS,
            isDeleted = false,
            note = null,
            bucketId = bucketId,
            region = region,
        )
    )
}
