package com.dispensesure.retail.feature.history.data

import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.BatchDeleted
import com.dispensesure.retail.core.audit.model.HistoryBulkDeleted
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.dtos.unsyncedIds
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
    private val stockTxnDao: StockTxnDao,
    private val regionProvider: RegionProvider,
    private val auditLogger: AuditLogger,
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
        // Read with the delete's own filter, so the audit event names exactly the rows removed.
        val deletedRows =
            dispenseTxnDao.deleteTransactionsByDateReturningSyncStates(startStartDate, endEndDate, isCompleted, userLocalId)
        if (deletedRows.isNotEmpty()) {
            auditLogger.log(
                HistoryBulkDeleted(
                    rangeStart = startStartDate,
                    rangeEnd = endEndDate,
                    statusFilter = statusFilterName(isCompleted),
                    count = deletedRows.size,
                    txnIds = deletedRows.map { it.id },
                    unsyncedIds = deletedRows.unsyncedIds()
                )
            )
        }
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

        val batchIds = stockBatchDao.getBatchIdsByDate(startMillis, endMillis, isCompleted, regionProvider.current)
        if (batchIds.isNotEmpty()) {
            val rowsToDelete = stockBatchDao.getSyncStatesByIds(batchIds)
            stockBatchDao.softDeleteBatchesByDate(startMillis, endMillis, isCompleted, regionProvider.current)
            stockTxnDao.deleteByBatchIds(batchIds)
            auditLogger.log(
                BatchDeleted(
                    batchIds = batchIds,
                    rangeStart = startMillis,
                    rangeEnd = endMillis,
                    unsyncedIds = rowsToDelete.unsyncedIds()
                )
            )
        }
    }

    // ALL / COMPLETED / PENDING, matching the History delete filter.
    private fun statusFilterName(isCompleted: Boolean?): String = when (isCompleted) {
        null -> "ALL"
        true -> "COMPLETED"
        false -> "PENDING"
    }

    fun getBatchSummaries(
        startDate: LocalDate,
        endDate: LocalDate,
        userLocalId: Long
    ): Flow<List<BatchSummary>> {
        val zoneId = ZoneId.systemDefault()
        val startMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        return stockBatchDao.getBatchSummaries(startMillis, endMillis, regionProvider.current).map { dtos ->
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

