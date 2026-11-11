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

    @Query("DELETE FROM analytics_counted WHERE businessDate < :today")
    suspend fun pruneOlderThan(today: String): Int
}
