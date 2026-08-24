package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    /**
     * Existing SEALED bottle line for a `(stockTxn, lot, expiry)` tuple — a sealed line is one with
     * no loose pills (`looseQty` 0 or NULL). Used to collapse repeat sealed scans of the same NDC
     * onto a single running-count line, while loose sessions each get their own row.
     */
    @Query(
        """
        SELECT * FROM bottle_info
        WHERE stockTxnId = :stockTxnId
          AND (lotNo = :lotNo OR (lotNo IS NULL AND :lotNo IS NULL))
          AND (expNo = :expNo OR (expNo IS NULL AND :expNo IS NULL))
          AND IFNULL(looseQty, 0) = 0
        LIMIT 1
        """
    )
    suspend fun findSealedLine(stockTxnId: Long, lotNo: String?, expNo: String?): BottleInfoEntity?

    /** Adds [qty] to the loose-pill total of a bottle line (treating NULL as 0). */
    @Query("UPDATE bottle_info SET looseQty = IFNULL(looseQty, 0) + :qty, updatedAt = :now WHERE bottleId = :bottleId")
    suspend fun incrementLooseQty(bottleId: Long, qty: Int, now: Long = System.currentTimeMillis())

    /**
     * Overwrites the controlled-image path list on a bottle line and bumps `updatedAt`.
     *
     * Description:
     * Persists the absolute file paths of pill-count images captured during a Scan Pills
     * session onto an existing `bottle_info` row. Intended for controlled-substance rows
     * only; callers must gate on `isControlledDrugType(drugType)` before invoking.
     *
     * What it does:
     * - Replaces (not appends) `controlledImagePaths` with [paths].
     * - Serializes the list via `StringListConverter` (registered on the DB).
     *
     * @param bottleId Target bottle_info primary key.
     * @param paths List of absolute image file paths, or null to clear.
     * @param now Epoch-millis timestamp for `updatedAt`.
     *
     * Example Usage:
     * bottleInfoDao.updateControlledImagePaths(42L, listOf("/data/.../a.jpg"))
     */
    @Query("UPDATE bottle_info SET controlledImagePaths = :paths, updatedAt = :now WHERE bottleId = :bottleId")
    suspend fun updateControlledImagePaths(
        bottleId: Long,
        paths: List<String>?,
        now: Long = System.currentTimeMillis(),
    )

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
            dm.packageQty AS packageQty
        FROM bottle_info AS b
        INNER JOIN stock_txn AS s ON b.stockTxnId = s.txnId
        LEFT JOIN drug_master AS dm ON s.drugId = dm.drugId
        WHERE b.batchId = :batchId AND s.isDeleted = 0
        ORDER BY dm.drugName ASC
        """
    )
    suspend fun getByBatchId(batchId: Long): List<BatchTxnDto>
}
