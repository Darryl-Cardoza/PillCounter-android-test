package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rite.pillcounting.core.room.models.BatchEntity
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
}