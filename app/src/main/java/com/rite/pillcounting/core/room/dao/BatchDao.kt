package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import kotlinx.coroutines.flow.Flow

/**
 * **Batch Data Access Object**
 *
 * Provides database operations for managing [BatchEntity] entries — representing
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
 * - `batch` — Main batch table.
 */
@Dao
interface BatchDao {

    // ────────────────────────────── Create / Insert ──────────────────────────────

    /**
     * Inserts a new [BatchEntity] into the database.
     *
     * - Uses [OnConflictStrategy.IGNORE] to prevent overwriting existing batches.
     * - If a batch with the same [BatchEntity.batchId] already exists,
     *   the operation will be ignored and return `-1`.
     *
     * @param batch The [BatchEntity] to insert.
     * @return The row ID of the newly inserted batch, or `-1` if conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(batch: BatchEntity): Long

    /**
     * Inserts multiple [BatchEntity] records.
     *
     * @param batches List of batches to insert.
     * @return List of row IDs for inserted batches.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(batches: List<BatchEntity>): List<Long>

    // ────────────────────────────── Read / Query ──────────────────────────────

    /**
     * Retrieves a batch by its ID.
     *
     * @param batchId The primary key of the batch.
     * @return The [BatchEntity] with the given ID, or null if not found.
     */
    @Query("SELECT * FROM batch WHERE batchId = :batchId")
    suspend fun getById(batchId: Long): BatchEntity?

    @Query("SELECT * FROM batch WHERE batchId = :batchId LIMIT 1")
    fun observeById(batchId: Long): Flow<BatchEntity?>

    /**
     * Retrieves all non-deleted batches ordered by creation date (newest first).
     *
     * @return A [Flow] emitting list of all active batches.
     */
    @Query("SELECT * FROM batch WHERE isDeleted = 0 ORDER BY startDateTime DESC")
    fun getAllActive(): Flow<List<BatchEntity>>

    /**
     * Retrieves all non-deleted batches filtered by [status], ordered newest first.
     *
     * @param status The [BatchStatus] to filter by.
     * @return A [Flow] emitting the filtered list of batches.
     */
    @Query("""
        SELECT * FROM batch
        WHERE isDeleted = 0
        AND status = :status
        ORDER BY startDateTime DESC
    """)
    fun getAllByStatus(status: BatchStatus): Flow<List<BatchEntity>>

    /** Convenience wrapper — returns only [BatchStatus.INPROGRESS] batches. */
    fun getAllInProgress() = getAllByStatus(BatchStatus.INPROGRESS)

    /**
     * Retrieves the most recently created batch with the given [status].
     *
     * @param status The [BatchStatus] to filter by.
     * @return The latest matching [BatchEntity], or null if none exist.
     */
    @Query("SELECT * FROM batch WHERE isDeleted = 0 AND status = :status ORDER BY startDateTime DESC LIMIT 1")
    suspend fun getLatestByStatus(status: BatchStatus): BatchEntity?

    /** Convenience wrapper — returns the latest [BatchStatus.INPROGRESS] batch. */
    suspend fun getLatest() = getLatestByStatus(BatchStatus.INPROGRESS)

    // ────────────────────────────── Delete ──────────────────────────────

    /**
     * Soft-deletes a batch by setting isDeleted to true.
     *
     * @param batchId The ID of the batch to soft-delete.
     * @return The number of rows affected.
     */
    @Query("UPDATE batch SET isDeleted = 1 WHERE batchId = :batchId")
    suspend fun softDelete(batchId: Long): Int

    /**
     * Marks a batch as completed by setting status to COMPLETED and recording endDateTime.
     *
     * @param batchId The ID of the batch to complete.
     * @return The number of rows affected.
     */
    @Query("UPDATE batch SET status = 'COMPLETED', endDateTime = :endDateTime WHERE batchId = :batchId")
    suspend fun markAsCompleted(batchId: Long, endDateTime: Long = System.currentTimeMillis()): Int

    @Query("UPDATE batch SET note = :note WHERE batchId = :batchId")
    suspend fun updateNote(batchId: Long, note: String?): Int

    // ────────────────────────────── Utility ──────────────────────────────

    /**
     * Gets the count of non-deleted batches.
     *
     * @return The total number of active batches.
     */
    @Query("SELECT COUNT(*) FROM batch WHERE isDeleted = 0")
    suspend fun getActiveCount(): Long

    /**
     * Observes the count of non-deleted batches with the given [status].
     *
     * @param status The [BatchStatus] to count.
     * @return A [Flow] emitting the live count.
     */
    @Query("SELECT COUNT(*) FROM batch WHERE isDeleted = 0 AND status = :status")
    fun observeCountByStatus(status: BatchStatus): Flow<Int>

    /** Convenience wrapper — observes count of [BatchStatus.INPROGRESS] batches. */
    fun observeActiveInProgressCount() = observeCountByStatus(BatchStatus.INPROGRESS)

    /** Convenience wrapper — observes count of [BatchStatus.COMPLETED] batches. */
    fun observeCompletedBatchCount() = observeCountByStatus(BatchStatus.COMPLETED)

    @Query("DELETE FROM batch")
    suspend fun deleteAll()

    /** Hard-delete a single batch by id. Used to clean up lazily-created stock batches that
     *  were abandoned before any pill was counted. */
    @Query("DELETE FROM batch WHERE batchId = :batchId")
    suspend fun deleteById(batchId: Long)

    @Query("""
        SELECT batchId FROM batch
        WHERE isDeleted = 0
          AND startDateTime BETWEEN :start AND :end
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
        completedStatus: BatchStatus = BatchStatus.COMPLETED,
        inProgressStatus: BatchStatus = BatchStatus.INPROGRESS
    ): List<Long>

    @Query("""
        UPDATE batch
        SET isDeleted = 1
        WHERE isDeleted = 0
          AND startDateTime BETWEEN :start AND :end
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
        completedStatus: BatchStatus = BatchStatus.COMPLETED,
        inProgressStatus: BatchStatus = BatchStatus.INPROGRESS
    )

    /**
     * Returns all non-deleted batches whose [BatchEntity.startDateTime] falls within the given
     * range, LEFT JOINed with their transactions so that empty batches (no txns yet) still appear
     * with [BatchSummaryDto.uniqueNdcCount] = 0.
     *
     * The join filters transactions by [userLocalId] so that NDC counts are user-scoped, while
     * the batch rows themselves are always included regardless of whether they have transactions.
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
    FROM batch b
    LEFT JOIN stock_txn txn
        ON b.batchId = txn.batchId
        AND txn.isDeleted = 0
    WHERE b.isDeleted = 0
      AND b.startDateTime BETWEEN :startDate AND :endDate
    GROUP BY b.batchId
    ORDER BY b.startDateTime DESC
    """
    )
    fun getBatchSummaries(
        startDate: Long,
        endDate: Long
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
    FROM batch b
    LEFT JOIN stock_txn txn
        ON b.batchId = txn.batchId
        AND txn.isDeleted = 0
    WHERE b.isDeleted = 0
      AND b.status = :completedStatus
      AND b.isSynced = 0
    GROUP BY b.batchId
    ORDER BY b.startDateTime DESC
    """
    )
    fun observeUnsyncedCompletedBatches(
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
    FROM batch b
    LEFT JOIN stock_txn txn
        ON b.batchId = txn.batchId
        AND txn.isDeleted = 0
    WHERE b.isDeleted = 0
      AND b.status = :inProgressStatus
    GROUP BY b.batchId
    ORDER BY b.startDateTime DESC
    """
    )
    fun observeInProgressBatchSummaries(
        inProgressStatus: BatchStatus = BatchStatus.INPROGRESS
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
    FROM batch b
    LEFT JOIN stock_txn txn
        ON b.batchId = txn.batchId
        AND txn.isDeleted = 0
    WHERE b.isDeleted = 0
      AND b.status = :completedStatus
      AND b.isSynced = 0
    GROUP BY b.batchId
    ORDER BY b.startDateTime DESC
    """
    )
    suspend fun getUnsyncedCompletedBatchesOnce(
        completedStatus: BatchStatus = BatchStatus.COMPLETED
    ): List<BatchSummaryDto>

    @Query("UPDATE batch SET isSynced = 1 WHERE batchId = :batchId")
    suspend fun markBatchSynced(batchId: Long)

    /** Records the total chunk count once a chunked inventory sync starts (idempotent across resumes). */
    @Query("UPDATE batch SET totalChunks = :totalChunks WHERE batchId = :batchId")
    suspend fun setTotalChunks(batchId: Long, totalChunks: Int)

    /** Advances progress after a chunk's ACK succeeds — persisted immediately so a resume never resends it. */
    @Query("UPDATE batch SET lastAckedChunkIndex = :chunkIndex WHERE batchId = :batchId")
    suspend fun markChunkAcked(batchId: Long, chunkIndex: Int)

    @Query("""
        SELECT COUNT(*)
        FROM batch
        WHERE isDeleted = 0
          AND status = :completedStatus
          AND isSynced = 0
    """)
    fun getUnsyncedCompletedBatchCount(
        completedStatus: BatchStatus = BatchStatus.COMPLETED
    ): Flow<Int>
}