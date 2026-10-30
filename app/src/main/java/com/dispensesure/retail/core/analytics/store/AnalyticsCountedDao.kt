package com.dispensesure.retail.core.analytics.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnalyticsCountedDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: AnalyticsCountedEntity): Long

    @Query("SELECT COUNT(*) > 0 FROM analytics_counted WHERE kind = :kind AND entityId = :entityId")
    suspend fun exists(kind: String, entityId: Long): Boolean

    @Query("DELETE FROM analytics_counted WHERE businessDate < :today")
    suspend fun pruneOlderThan(today: String): Int
}
