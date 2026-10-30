package com.dispensesure.retail.core.analytics.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface DailyAnalyticsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: DailyAnalyticsEntity): Long

    @Query(
        """
        SELECT * FROM daily_analytics
        WHERE pharmacyUserId = :pharmacyUserId
          AND terminalName   = :terminalName
          AND operatorId     = :operatorId
          AND businessDate   = :businessDate
          AND region         = :region
        """
    )
    suspend fun findByKey(
        pharmacyUserId: String,
        terminalName: String,
        operatorId: String,
        businessDate: String,
        region: String,
    ): DailyAnalyticsEntity?

    @Query("SELECT * FROM daily_analytics WHERE id = :id")
    suspend fun findById(id: Long): DailyAnalyticsEntity?

    @Update
    suspend fun update(row: DailyAnalyticsEntity)

    @Query(
        """
        SELECT * FROM daily_analytics
        WHERE businessDate < :todayBusinessDate
          AND uploadAttempts < :maxAttempts
          AND nextAttemptAt <= :nowMillis
        ORDER BY businessDate DESC
        LIMIT :limit
        """
    )
    suspend fun rowsReadyForUpload(
        todayBusinessDate: String,
        nowMillis: Long,
        maxAttempts: Int = 10,
        limit: Int = 50,
    ): List<DailyAnalyticsEntity>

    @Query("DELETE FROM daily_analytics WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM daily_analytics WHERE businessDate < :cutoff")
    suspend fun pruneOlderThan(cutoff: String): Int

    @Query("SELECT * FROM daily_analytics")
    suspend fun getAll(): List<DailyAnalyticsEntity>

    /** DEBUG ONLY — resets backoff so all rows are immediately eligible for upload. */
    @Query("UPDATE daily_analytics SET nextAttemptAt = 0")
    suspend fun debugClearAllBackoff()
}
