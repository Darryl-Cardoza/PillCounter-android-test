package com.dispensesure.retail.core.analytics.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnalyticsCountedDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: AnalyticsCountedEntity): Long

    /** Overwrites the ledger row, so the counted quantities move forward. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AnalyticsCountedEntity)

    @Query("SELECT COUNT(*) > 0 FROM analytics_counted WHERE kind = :kind AND entityId = :entityId")
    suspend fun exists(kind: String, entityId: Long): Boolean

    /** The ledger row, or null when this entity has never been counted. */
    @Query("SELECT * FROM analytics_counted WHERE kind = :kind AND entityId = :entityId")
    suspend fun find(kind: String, entityId: Long): AnalyticsCountedEntity?

    /**
     * Drops ledger rows for work that can no longer grow.
     *
     * A dispense is counted once and never revised, so an older row is always safe to drop.
     *
     * Inventory is not: a batch left open overnight is resumed the next day, and its transactions
     * keep accumulating bottles. Dropping those rows on the date rollover would make every
     * transaction in the batch look new — re-adding its full sealed quantity, its transaction
     * count and its manual-transaction increment into the new day's row, for work done the day
     * before. So a stock row survives while its batch is still INPROGRESS, however old it is, and
     * goes once the batch is completed or deleted.
     *
     * Retention is therefore bounded by open batches rather than by time, and an orphaned row —
     * one whose transaction or batch no longer exists — is dropped by the same `NOT EXISTS`.
     */
    @Query(
        """
        DELETE FROM analytics_counted
        WHERE businessDate < :today
          AND (
            kind != :stockKind
            OR NOT EXISTS (
              SELECT 1 FROM stock_txn t
              JOIN stock_batch b ON b.batchId = t.batchId
              WHERE t.txnId = analytics_counted.entityId
                AND t.isDeleted = 0
                AND b.isDeleted = 0
                AND b.status = 'INPROGRESS'
            )
          )
        """
    )
    suspend fun pruneOlderThan(
        today: String,
        stockKind: String = AnalyticsCountedEntity.KIND_STOCK_TXN,
    ): Int
}
