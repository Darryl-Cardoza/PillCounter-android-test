package com.dispensesure.retail.core.tourguide

import androidx.room.withTransaction
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.dao.BatchDao
import com.dispensesure.retail.core.room.dao.BottleInfoDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.PillCountTxnDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.scanning.domain.model.BottleInfoJson
import com.dispensesure.retail.core.utils.logger.AppLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Removes every tutorial-created row (see [TutorialData]) and its image files. Idempotent. */
@Singleton
class TutorialDataCleaner @Inject constructor(
    private val db: AppDatabase,
    private val txnDao: PillCountTxnDao,
    private val batchDao: BatchDao,
    private val stockTxnDao: StockTxnDao,
    private val bottleInfoDao: BottleInfoDao,
    private val drugMasterDao: DrugMasterDao,
) {
    private val logger = AppLogger.create<TutorialDataCleaner>()

    /** [force]: purge between sections of a running tour (no step is running at the prompt). */
    suspend fun purge(force: Boolean = false) {
        if (!force && FtueController.state.isActive) return
        val files = mutableListOf<String>()
        try {
        db.withTransaction {
            txnDao.getTutorialTxnIds().forEach { id ->
                BottleInfoJson.decode(txnDao.getById(id)?.bottleInfoListJson)
                    .mapNotNull { it.barcodeImagePath }.let(files::addAll)
                files += txnDao.getTransactionDetailsImages(id)
                txnDao.deleteTransaction(id)
            }
            val batchIds = batchDao.getTutorialBatchIds()
            batchIds.forEach { id ->
                bottleInfoDao.getByBatchId(id).flatMap { it.imagePaths.orEmpty() }.let(files::addAll)
            }
            if (batchIds.isNotEmpty()) stockTxnDao.deleteByBatchIds(batchIds)
            batchIds.forEach { batchDao.deleteById(it) }
            stockTxnDao.deleteByDemoDrug()
            // Last: the txn/stock deletes above still match by this drug's NDC.
            drugMasterDao.deleteByNdc(TutorialData.DEMO_NDC)
            // Before the commit: if the process dies first the rows survive and the next purge
            // retries, instead of the rows going and the files being orphaned for good.
            files.forEach { path -> File(path).takeIf { it.exists() }?.delete() }
        }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash the app over cleanup; HL7 and the UI already ignore these rows.
            logger.e("Tutorial data purge failed", e)
        }
    }
}
