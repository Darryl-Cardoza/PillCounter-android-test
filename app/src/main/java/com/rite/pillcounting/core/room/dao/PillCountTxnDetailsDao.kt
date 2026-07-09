package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import kotlinx.coroutines.flow.Flow

/**
 * **Pill Count Transaction Details Data Access Object**
 *
 * Handles persistence and retrieval of [PillCountTxnDetailsEntity] records — the
 * individual detail lines belonging to a pill count transaction.
 *
 * ---
 * ### Core Responsibilities
 * - Manage creation and modification of pill count line items.
 * - Support soft deletion (marking as deleted without physical removal).
 * - Provide reactive queries using [Flow] for real-time UI updates.
 * - Compute summary data (e.g., total pill count per transaction).
 *
 * ---
 * ### Design Notes
 * - Uses [OnConflictStrategy.REPLACE] for inserts to support upserts of line items.
 * - Physically deleted rows are avoided in favor of logical deletion (`isDeleted = 1`).
 * - Optimized for live data observation and background synchronization.
 */
@Dao
interface PillCountTxnDetailsDao {

    // ─────────────────────────────── Create / Update ───────────────────────────────

    /**
     * Inserts a new transaction detail or replaces an existing one
     * with the same primary key ([PillCountTxnDetailsEntity.txnDetailsId]).
     *
     * - Useful when details are edited or rescanned within the same transaction.
     *
     * @param detail The detail entity to insert or replace.
     * @return The newly inserted row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(detail: PillCountTxnDetailsEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(details: List<PillCountTxnDetailsEntity>): List<Long>

    // ──────────────────────────────── Reads ────────────────────────────────

    /**
     * Observes all **non-deleted** detail records associated with a given transaction.
     *
     * - Automatically emits updates whenever records are inserted, updated, or soft-deleted.
     * - Results are ordered by [PillCountTxnDetailsEntity.createdAt] descending
     *   (newest first).
     *
     * @param txnId The parent transaction ID.
     * @return A [Flow] emitting the current list of [PillCountTxnDetailsEntity] items.
     */
    @Query(
        """
        SELECT * FROM pill_count_txn_details
        WHERE txnId = :txnId AND type = :type AND isDeleted = 0
        ORDER BY createdAt DESC
        """
    )
    fun observeAllForTxn(txnId: Long, type: StepState): Flow<List<PillCountTxnDetailsEntity>>

    // ─────────────────────────────── Soft Delete ───────────────────────────────

    /**
     * Performs a **soft delete** of a transaction detail record.
     *
     * Instead of removing the record from the database, it marks
     * the record as deleted (`isDeleted = 1`) while preserving historical data.
     *
     * @param id The unique ID of the detail record.
     * @param now Optional update timestamp (epoch milliseconds). Defaults to the current time.
     */
    @Query(
        "UPDATE pill_count_txn_details SET isDeleted = 1, updatedAt = :now WHERE txnDetailsId = :id"
    )
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query(
        """
    UPDATE pill_count_txn_details
    SET isDeleted = 1, updatedAt = :now
    WHERE txnId = :id AND type = :type
    """
    )
    suspend fun softDeleteAllTransaction(id: Long, now: Long = System.currentTimeMillis(),type: StepState)

    // Delete method to remove existing VIAL records for a specific txnId
    @Query("DELETE FROM pill_count_txn_details WHERE txnId = :txnId AND type = :type")
    suspend fun deleteVialByTxnId(txnId: Long, type: StepState)

    // ─────────────────────────────── Aggregations ───────────────────────────────

    /**
     * Computes the **total pill count** for a given transaction.
     *
     * - Ignores soft-deleted detail lines.
     * - Returns `0` if no details exist.
     *
     * @param txnId The parent transaction ID.
     * @return The total pill count (sum of `pillCount` across all valid details).
     */
    @Query(
        """
        SELECT COALESCE(SUM(pillCount), 0) 
        FROM pill_count_txn_details 
        WHERE txnId = :txnId AND isDeleted = 0
        """
    )
    suspend fun getTotalPillCountForTxn(txnId: Long): Int



    @Query(
        """
        SELECT * FROM pill_count_txn_details
        WHERE txnId = :txnId AND isDeleted = 0
        ORDER BY createdAt DESC
        """
    )
    suspend fun getAllForTxn(txnId: String): List<PillCountTxnDetailsEntity>

    @Query(
        """
    SELECT type
    FROM pill_count_txn_details
    WHERE txnId = :txnId AND isDeleted = 0
    ORDER BY createdAt DESC
    LIMIT 1
    """
    )
    suspend fun getLatestType(txnId: Long): StepState?
}
