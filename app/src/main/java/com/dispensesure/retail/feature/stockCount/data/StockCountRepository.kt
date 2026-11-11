package com.dispensesure.retail.feature.stockCount.data

import androidx.room.withTransaction
import com.dispensesure.retail.core.analytics.AnalyticsService
import com.dispensesure.retail.core.analytics.collect.CountDurationTracker
import com.dispensesure.retail.core.events.DomainEvent
import com.dispensesure.retail.core.events.DomainEventBus
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.dao.insertNewInProgressBatch
import com.dispensesure.retail.core.room.models.StockBottleInfoEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.utils.common.deleteFilesQuietly
import com.dispensesure.retail.core.utils.logger.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Stock count writes that span several tables, and the photo files a count leaves behind. */
class StockCountRepository @Inject constructor(
    private val appDatabase: AppDatabase,
    private val stockBatchDao: StockBatchDao,
    private val stockTxnDao: StockTxnDao,
    private val stockBottleInfoDao: StockBottleInfoDao,
    private val operatorNameProvider: OperatorNameProvider,
    private val regionProvider: RegionProvider,
    private val domainEventBus: DomainEventBus,
    private val durationTracker: CountDurationTracker,
) {

    private val logger = AppLogger("StockCountRepository")

    /**
     * Saves one loose count as a new stock_bottle_info line in one transaction: the batch
     * ([batchId], or a new one in [bucketId] when 0), the drug's header in it (found or
     * created), the line, the batch totals and operator, then the header as COMPLETED.
     * Throws when a write fails; nothing is kept then.
     *
     * @return The batch id the count went into.
     */
    suspend fun commitLooseCount(
        batchId: Long,
        bucketId: String?,
        drugId: Long,
        lotNumber: String?,
        expirationDate: String?,
        serialNumber: String?,
        loosePills: Int,
        controlledImagePaths: List<String>?,
    ): Long {
        val saved = appDatabase.withTransaction {
            val savedBatchId = if (batchId != 0L) {
                batchId
            } else {
                stockBatchDao.insertNewInProgressBatch(bucketId = bucketId, region = regionProvider.current)
            }
            val existingTxnId = stockTxnDao.findByDrugInBatch(savedBatchId, drugId)?.txnId
            val stockTxnId = existingTxnId
                ?: stockTxnDao.upsertPreservingId(
                    StockTxnEntity(
                        drugId = drugId,
                        status = CountStatus.PARTIAL,
                        batchId = savedBatchId,
                        bucketId = bucketId,
                    )
                )
            // Each count is its own line holding only loose pills; bottleQty 0 keeps it from
            // counting as a sealed bottle.
            val bottleId = stockBottleInfoDao.insert(
                StockBottleInfoEntity(
                    stockTxnId = stockTxnId,
                    batchId = savedBatchId,
                    lotNo = lotNumber,
                    expNo = expirationDate,
                    serialNo = serialNumber,
                    bottleQty = 0,
                    looseQty = loosePills,
                    controlledImagePaths = controlledImagePaths,
                )
            )
            stockTxnDao.refreshBatchTotalNdcs(savedBatchId)
            stockTxnDao.updateBatchUserName(savedBatchId, operatorNameProvider().display())
            stockTxnDao.updateStatus(stockTxnId, CountStatus.COMPLETED)
            logger.i("Saved loose count. loosePills=$loosePills drugId=$drugId batchId=$savedBatchId stockTxnId=$stockTxnId controlledPaths=${controlledImagePaths?.size ?: 0}")
            LooseCountCommit(
                batchId = savedBatchId,
                stockTxnId = stockTxnId,
                bottleId = bottleId,
                isNewTxn = existingTxnId == null,
            )
        }

        // Announced only once the transaction has committed. A consumer reads the rows back from
        // another thread, so posting inside the transaction races it against the commit — it would
        // find nothing and drop the event, or count a line a rollback then discarded.
        if (AnalyticsService.isEnabled) {
            if (saved.isNewTxn) {
                durationTracker.start(CountDurationTracker.stockKey(saved.stockTxnId))
            }
            domainEventBus.tryPost(DomainEvent.StockBottleRecorded(saved.stockTxnId, saved.bottleId))
        }
        return saved.batchId
    }

    /** What [commitLooseCount]'s transaction produced, carried out so events can be posted after it. */
    private data class LooseCountCommit(
        val batchId: Long,
        val stockTxnId: Long,
        val bottleId: Long,
        val isNewTxn: Boolean,
    )

    /**
     * Keeps reset availability live. A count that has reached the pharmacy system can no
     * longer be thrown away locally, and that can land while the screen is still up.
     */
    fun observeCanReset(batchId: Long): Flow<Boolean> =
        // Stock count with no batch has nowhere to have synced to.
        if (batchId == 0L) {
            flowOf(true)
        } else {
            stockBatchDao.observeById(batchId).map { batch ->
                batch?.let { savedBatch -> !savedBatch.isSynced && savedBatch.lastAckedChunkIndex == 0 } ?: true
            }
        }

    /** Deletes a count's photo files; see [deleteFilesQuietly]. */
    fun deletePhotos(paths: List<String>) = deleteFilesQuietly(paths, logger)
}
