package com.rite.pillcounting.feature.history.data

import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.feature.history.domain.model.BatchSummary
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class HistoryRepository @Inject constructor(
    private val dao: PillCountTxnDao,
    private val batchDao: BatchDao
) {

    private fun LocalDate.toEpochRange(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = atStartOfDay(zone).toInstant().toEpochMilli()
        val end = plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    //not using currently, keeping code if its required for future
    fun getTransactionsForDate(date: LocalDate,type: CountType?, status: CountStatus?): Flow<List<TxnWithDrugDto>> {
        val (start, end) = date.toEpochRange()
        return dao.getTransactionsWithDrugByDate(start, end, type, status)
    }

    suspend fun deleteTransactionsForDate(
        startDate: LocalDate,
        endDate: LocalDate,
        type: CountType?,
        isCompleted: Boolean?,
        userLocalId: Long
    ) {
        val (startStartDate, _) = startDate.toEpochRange()
        val (_, endEndDate) = endDate.toEpochRange()
        dao.deleteTransactionsByDate(startStartDate, endEndDate, type, isCompleted, userLocalId)
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

        val batchIds = batchDao.getBatchIdsByDate(startMillis, endMillis, isCompleted)
        if (batchIds.isNotEmpty()) {
            batchDao.softDeleteBatchesByDate(startMillis, endMillis, isCompleted)
            dao.deleteTransactionsByBatchIds(batchIds)
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

        return batchDao.getBatchSummaries(startMillis, endMillis, userLocalId).map { dtos ->
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
        type: CountType?,
        status: CountStatus?,
        userLocalId: Long
    ): Flow<List<TxnWithDrugDto>> {

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

        return dao.getTransactionsForDateRange(
            startDate = startMillis,
            endDate = endMillis,
            stepType = StepState.TARGET_VERIFICATION,
            type = type,
            status = status,
            userLocalId = userLocalId
        )
    }


}

