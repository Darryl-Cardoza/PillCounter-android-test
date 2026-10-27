package com.dispensesure.retail.feature.history.data

import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.feature.history.domain.model.BatchSummary
import com.dispensesure.retail.feature.history.domain.model.DispenseTxnSummary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class HistoryRepository @Inject constructor(
    private val dispenseTxnDao: DispenseTxnDao,
    private val stockBatchDao: StockBatchDao,
    private val stockTxnDao: StockTxnDao
) {

    private fun LocalDate.toEpochRange(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = atStartOfDay(zone).toInstant().toEpochMilli()
        val end = plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    suspend fun deleteTransactionsForDate(
        startDate: LocalDate,
        endDate: LocalDate,
        isCompleted: Boolean?,
        userLocalId: Long
    ) {
        val (startStartDate, _) = startDate.toEpochRange()
        val (_, endEndDate) = endDate.toEpochRange()
        dispenseTxnDao.deleteTransactionsByDate(startStartDate, endEndDate, isCompleted, userLocalId)
    }

    suspend fun deleteBatchesForDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        isCompleted: Boolean?,
        userLocalId: Long
    ) {
        val zoneId = ZoneId.systemDefault()
        val startMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val batchIds = stockBatchDao.getBatchIdsByDate(startMillis, endMillis, isCompleted)
        if (batchIds.isNotEmpty()) {
            stockBatchDao.softDeleteBatchesByDate(startMillis, endMillis, isCompleted)
            stockTxnDao.deleteByBatchIds(batchIds)
        }
    }

    fun getBatchSummaries(
        startDate: LocalDate,
        endDate: LocalDate,
        userLocalId: Long
    ): Flow<List<BatchSummary>> {
        val zoneId = ZoneId.systemDefault()
        val startMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        return stockBatchDao.getBatchSummaries(startMillis, endMillis).map { dtos ->
            dtos.map { dto ->
                BatchSummary(
                    batchId = dto.batchId,
                    createdAt = dto.createdAt,
                    uniqueNdcCount = dto.uniqueNdcCount,
                    status = BatchStatus.valueOf(dto.status),
                    bucketId = dto.bucketId,
                    requestIdFromPMS = dto.requestIdFromPMS
                )
            }
        }
    }

    fun getTransactionsForDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        userLocalId: Long
    ): Flow<List<DispenseTxnSummary>> {

        val zoneId = ZoneId.systemDefault()

        val startMillis = startDate
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val endMillis = endDate
            .plusDays(1) // include full end day
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        return dispenseTxnDao.getTransactionsForDateRange(
            startDate = startMillis,
            endDate = endMillis,
            stepType = StepState.TARGET_VERIFICATION,
            status = null,
            userLocalId = userLocalId
        )
    }


}

