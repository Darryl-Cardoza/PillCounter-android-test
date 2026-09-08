package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rite.pillcounting.core.room.models.BottleInfoEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import kotlinx.coroutines.flow.Flow

/**
 * **Bottle Info Data Access Object**
 *
 * Manages [BottleInfoEntity] — the per-`(stockTxn, lot, expiry)` bottle/loose lines of the
 * normalized stock model. Join queries project to [BatchTxnDto] so the batch/inventory UI and
 * HL7 inventory builder keep the same shape they had when stock lived in `pill_count_txn`.
 */
@Dao
interface BottleInfoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bottle: BottleInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(bottles: List<BottleInfoEntity>): List<Long>

    @Update
    suspend fun update(bottle: BottleInfoEntity)

    @Query("SELECT * FROM bottle_info WHERE bottleId = :bottleId LIMIT 1")
    suspend fun getById(bottleId: Long): BottleInfoEntity?

    /**
     * Existing bottle line for a `(stockTxn, lot, expiry)` tuple — null-tolerant matching so a
     * missing lot/expiry on the label still merges onto the same line.
     */
    @Query(
        """
        SELECT * FROM bottle_info
        WHERE stockTxnId = :stockTxnId
          AND (lotNo = :lotNo OR (lotNo IS NULL AND :lotNo IS NULL))
          AND (expNo = :expNo OR (expNo IS NULL AND :expNo IS NULL))
        LIMIT 1
        """
    )
    suspend fun findLine(stockTxnId: Long, lotNo: String?, expNo: String?): BottleInfoEntity?

    /** Internal — increments looseQty AND overwrites controlledImagePaths in one SQL statement. */
    @Query(
        """
        UPDATE bottle_info
        SET looseQty = IFNULL(looseQty, 0) + :qty,
            controlledImagePaths = :paths,
            updatedAt = :now
        WHERE bottleId = :bottleId
        """
    )
    suspend fun _incrementLooseAndSetImages(bottleId: Long, qty: Int, paths: List<String>, now: Long)

    /** Internal — increments only looseQty, leaves controlledImagePaths untouched. */
    @Query(
        """
        UPDATE bottle_info
        SET looseQty = IFNULL(looseQty, 0) + :qty,
            updatedAt = :now
        WHERE bottleId = :bottleId
        """
    )
    suspend fun _incrementLooseQtyOnly(bottleId: Long, qty: Int, now: Long)

    /**
     * Adds [qty] to the loose-pill total of a bottle line (treating NULL as 0) and, when
     * [paths] is non-null, also overwrites `controlledImagePaths`. Room `@Transaction` wraps
     * the whole thing so a process death mid-flush can't save the count while dropping the
     * image paths — either both writes commit or neither does.
     *
     * Passing `paths = null` leaves the image-paths column untouched, so callers on the
     * non-controlled path can't accidentally clobber previously-persisted paths.
     *
     * Merges what used to be two separate DAO calls (incrementLooseQty +
     * updateControlledImagePaths) into one atomic operation.
     */
    @Transaction
    suspend fun incrementLooseQtyAndImages(
        bottleId: Long,
        qty: Int,
        paths: List<String>?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (paths != null) {
            _incrementLooseAndSetImages(bottleId, qty, paths, now)
        } else {
            _incrementLooseQtyOnly(bottleId, qty, now)
        }
    }

    @Query("DELETE FROM bottle_info WHERE bottleId = :bottleId")
    suspend fun delete(bottleId: Long)

    @Query("DELETE FROM bottle_info")
    suspend fun deleteAll()

    /**
     * Observes all bottle lines in a batch, joined with drug/stock-header data, newest drug first.
     * Powers the Inventory/Batch screens. `txnId` in the DTO is the [BottleInfoEntity.bottleId].
     */
    @Query(
        """
        SELECT
            b.bottleId AS txnId,
            s.drugId AS drugId,
            dm.drugName AS drugName,
            dm.ndc AS ndc,
            b.lotNo AS lotNo,
            b.expNo AS expiry,
            b.bottleQty AS bottleQty,
            b.looseQty AS looseQty,
            dm.packageQty AS packageQty
        FROM bottle_info AS b
        INNER JOIN stock_txn AS s ON b.stockTxnId = s.txnId
        LEFT JOIN drug_master AS dm ON s.drugId = dm.drugId
        WHERE b.batchId = :batchId AND s.isDeleted = 0
        ORDER BY dm.drugName ASC
        """
    )
    fun observeByBatchId(batchId: Long): Flow<List<BatchTxnDto>>

    /** One-shot equivalent of [observeByBatchId] — used for HL7 inventory responses. */
    @Query(
        """
        SELECT
            b.bottleId AS txnId,
            s.drugId AS drugId,
            dm.drugName AS drugName,
            dm.ndc AS ndc,
            b.lotNo AS lotNo,
            b.expNo AS expiry,
            b.bottleQty AS bottleQty,
            b.looseQty AS looseQty,
            dm.packageQty AS packageQty,
            b.controlledImagePaths AS imagePaths
        FROM bottle_info AS b
        INNER JOIN stock_txn AS s ON b.stockTxnId = s.txnId
        LEFT JOIN drug_master AS dm ON s.drugId = dm.drugId
        WHERE b.batchId = :batchId AND s.isDeleted = 0
        ORDER BY dm.drugName ASC
        """
    )
    suspend fun getByBatchId(batchId: Long): List<BatchTxnDto>
}
