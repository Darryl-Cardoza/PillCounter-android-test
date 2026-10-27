package com.dispensesure.retail.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import kotlinx.coroutines.flow.Flow

/**
 * **Dispense Count Data Access Object**
 *
 * Handles persistence and retrieval of [DispenseCountEntity] records — the
 * individual detail lines belonging to a pill count transaction.
 *
 * ---
 * ### Core Responsibilities
 * - Manage creation and modification of pill count line items.
 * - Hard-delete counts with their image files (callers remove files first).
 * - Provide reactive queries using [Flow] for real-time UI updates.
 * - Compute summary data (e.g., total pill count per transaction).
 *
 * ---
 * ### Design Notes
 * - Uses [OnConflictStrategy.REPLACE] for inserts to support upserts of line items.
 * - Rows are physically deleted (callers remove the image files first).
 * - Optimized for live data observation and background synchronization.
 */
@Dao
interface DispenseCountDao {

    // ─────────────────────────────── Create / Update ───────────────────────────────

    /**
     * Inserts a new transaction detail or replaces an existing one
     * with the same primary key ([DispenseCountEntity.countId]).
     *
     * - Useful when details are edited or rescanned within the same transaction.
     *
     * @param detail The detail entity to insert or replace.
     * @return The newly inserted row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(detail: DispenseCountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(details: List<DispenseCountEntity>): List<Long>

    // ──────────────────────────────── Reads ────────────────────────────────

    /**
     * Observes all detail records associated with a given transaction.
     *
     * - Automatically emits updates whenever records are inserted, updated, or deleted.
     * - Results are ordered by [DispenseCountEntity.createdAt] descending
     *   (newest first).
     *
     * @param txnId The parent transaction ID.
     * @return A [Flow] emitting the current list of [DispenseCountEntity] items.
     */
    @Query(
        """
        SELECT * FROM dispense_count
        WHERE txnId = :txnId AND step = :step
        ORDER BY createdAt DESC
        """
    )
    fun observeAllForTxn(txnId: Long, step: StepState): Flow<List<DispenseCountEntity>>

    // ─────────────────────────────── Hard Delete ───────────────────────────────

    /** Processed and raw image paths of one detail, so its files go before the row. */
    @Query(
        """
        SELECT imagePath FROM dispense_count WHERE countId = :countId AND imagePath IS NOT NULL
        UNION ALL
        SELECT rawImagePath FROM dispense_count WHERE countId = :countId AND rawImagePath IS NOT NULL
        """
    )
    suspend fun getImagePathsForCount(countId: Long): List<String>

    /** Processed and raw image paths of one step's details, so the files go before the rows. */
    @Query(
        """
        SELECT imagePath FROM dispense_count
        WHERE txnId = :txnId AND step = :step AND imagePath IS NOT NULL
        UNION ALL
        SELECT rawImagePath FROM dispense_count
        WHERE txnId = :txnId AND step = :step AND rawImagePath IS NOT NULL
        """
    )
    suspend fun getImagePathsForStep(txnId: Long, step: StepState): List<String>

    /** Ids of one step's details, so they can be unlinked from bottles after the delete. */
    @Query("SELECT countId FROM dispense_count WHERE txnId = :txnId AND step = :step")
    suspend fun getIdsForStep(txnId: Long, step: StepState): List<Long>

    /** Physical delete of one detail (View All delete). */
    @Query("DELETE FROM dispense_count WHERE countId = :countId")
    suspend fun hardDelete(countId: Long)

    /** Physical delete of one step's details (View All delete all). */
    @Query("DELETE FROM dispense_count WHERE txnId = :txnId AND step = :step")
    suspend fun hardDeleteAllForStep(txnId: Long, step: StepState)

    // Delete method to remove existing VIAL records for a specific txnId
    @Query("DELETE FROM dispense_count WHERE txnId = :txnId AND step = :step")
    suspend fun deleteVialByTxnId(txnId: Long, step: StepState)

    /** Image paths for a transaction, so the files can be removed before the rows go. */
    @Query(
        """
        SELECT imagePath FROM dispense_count WHERE txnId = :txnId AND imagePath IS NOT NULL
        UNION ALL
        SELECT rawImagePath FROM dispense_count WHERE txnId = :txnId AND rawImagePath IS NOT NULL
        """
    )
    suspend fun getImagePathsForTxn(txnId: Long): List<String>

    /** Physical delete of every detail for a transaction. Used by reset, which keeps nothing. */
    @Query("DELETE FROM dispense_count WHERE txnId = :txnId")
    suspend fun deleteAllForTxn(txnId: Long)

    // ─────────────────────────────── Aggregations ───────────────────────────────

    /**
     * Computes the **total pill count** for a given transaction.
     *
     * - Returns `0` if no details exist.
     *
     * @param txnId The parent transaction ID.
     * @return The total pill count (sum of `pillCount` across all valid details).
     */
    @Query(
        """
        SELECT COALESCE(SUM(pillCount), 0)
        FROM dispense_count
        WHERE txnId = :txnId
        """
    )
    suspend fun getTotalPillCountForTxn(txnId: Long): Int

    /**
     * Pill total for one workflow step only. A dispense's reported quantity is the
     * prescribed-count step alone — [getTotalPillCountForTxn] spans every step, which
     * measures several different things.
     *
     * @param txnId The parent transaction ID.
     * @param step The [com.dispensesure.retail.core.models.StepState] name to sum.
     * @return The step's pill total, or 0 if it has no rows.
     */
    @Query(
        """
        SELECT COALESCE(SUM(pillCount), 0)
        FROM dispense_count
        WHERE txnId = :txnId AND step = :step
        """
    )
    suspend fun getPillCountForStep(txnId: Long, step: String): Int

    /**
     * Live pill total for a specific bottle's detail rows, by id. A bottle's true count is
     * always this — never a stored/snapshotted number — so deleting or redoing a count is
     * automatically reflected. See [com.dispensesure.retail.core.scanning.domain.model.DispenseBottle.countIds].
     *
     * @return 0 if [countIds] is empty or no row matches.
     */
    @Query(
        """
        SELECT COALESCE(SUM(pillCount), 0)
        FROM dispense_count
        WHERE countId IN (:countIds)
        """
    )
    suspend fun getPillCountForCountIds(countIds: List<Long>): Int

    @Query(
        """
        SELECT * FROM dispense_count
        WHERE txnId = :txnId
        ORDER BY createdAt DESC
        """
    )
    suspend fun getAllForTxn(txnId: String): List<DispenseCountEntity>

    @Query(
        """
    SELECT step
    FROM dispense_count
    WHERE txnId = :txnId
    ORDER BY createdAt DESC
    LIMIT 1
    """
    )
    suspend fun getLatestStep(txnId: Long): StepState?
}
