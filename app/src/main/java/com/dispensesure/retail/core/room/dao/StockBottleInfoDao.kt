package com.dispensesure.retail.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dispensesure.retail.core.room.models.StockBottleInfoEntity
import com.dispensesure.retail.core.room.models.dtos.StockBottleInfoDto
import kotlinx.coroutines.flow.Flow

/**
 * **Bottle Info Data Access Object**
 *
 * Manages [StockBottleInfoEntity] — the per-`(stockTxn, lot, expiry)` bottle/loose lines of the
 * normalized stock model. Join queries project to [StockBottleInfoDto] so the batch/inventory UI and
 * HL7 inventory builder keep the same shape they had when stock lived in `pill_count_txn`.
 */
@Dao
interface StockBottleInfoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bottle: StockBottleInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(bottles: List<StockBottleInfoEntity>): List<Long>

    @Update
    suspend fun update(bottle: StockBottleInfoEntity)

    @Query("SELECT * FROM stock_bottle_info WHERE bottleId = :bottleId LIMIT 1")
    suspend fun getById(bottleId: Long): StockBottleInfoEntity?

    /**
     * Existing sealed-bottle line for a `(stockTxn, lot, expiry)` tuple — null-tolerant matching so a
     * missing lot/expiry on the label still merges onto the same line. Loose lines (bottleQty 0) never match.
     */
    @Query(
        """
        SELECT * FROM stock_bottle_info
        WHERE stockTxnId = :stockTxnId
          AND bottleQty > 0
          AND (lotNo = :lotNo OR (lotNo IS NULL AND :lotNo IS NULL))
          AND (expNo = :expNo OR (expNo IS NULL AND :expNo IS NULL))
        LIMIT 1
        """
    )
    suspend fun findSealedLine(stockTxnId: Long, lotNo: String?, expNo: String?): StockBottleInfoEntity?

    @Query("DELETE FROM stock_bottle_info WHERE bottleId = :bottleId")
    suspend fun delete(bottleId: Long)

    /** @return Rows deleted, recorded on LOCAL_DATA_CLEARED. */
    @Query("DELETE FROM stock_bottle_info")
    suspend fun deleteAll(): Int

    /**
     * Observes all bottle lines in a batch, joined with drug/stock-header data, newest drug first.
     * Powers the Inventory/Batch screens.
     */
    @Query(
        """
        SELECT
            b.bottleId AS bottleId,
            s.drugId AS drugId,
            dm.drugName AS drugName,
            dm.ndc AS ndc,
            b.lotNo AS lotNo,
            b.serialNo AS serialNo,
            b.expNo AS expiry,
            b.bottleQty AS bottleQty,
            b.looseQty AS looseQty,
            dm.packageQty AS packageQty
        FROM stock_bottle_info AS b
        INNER JOIN stock_txn AS s ON b.stockTxnId = s.txnId
        LEFT JOIN drug_master AS dm ON s.drugId = dm.drugId
        WHERE b.batchId = :batchId AND s.isDeleted = 0
        ORDER BY dm.drugName ASC
        """
    )
    fun observeByBatchId(batchId: Long): Flow<List<StockBottleInfoDto>>

    /** One-shot equivalent of [observeByBatchId] — used for HL7 inventory responses. */
    @Query(
        """
        SELECT
            b.bottleId AS bottleId,
            s.drugId AS drugId,
            dm.drugName AS drugName,
            dm.ndc AS ndc,
            b.lotNo AS lotNo,
            b.serialNo AS serialNo,
            b.expNo AS expiry,
            b.bottleQty AS bottleQty,
            b.looseQty AS looseQty,
            dm.packageQty AS packageQty,
            b.controlledImagePaths AS imagePaths
        FROM stock_bottle_info AS b
        INNER JOIN stock_txn AS s ON b.stockTxnId = s.txnId
        LEFT JOIN drug_master AS dm ON s.drugId = dm.drugId
        WHERE b.batchId = :batchId AND s.isDeleted = 0
        ORDER BY dm.drugName ASC
        """
    )
    suspend fun getByBatchId(batchId: Long): List<StockBottleInfoDto>
}
