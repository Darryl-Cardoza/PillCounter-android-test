package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rite.pillcounting.core.room.models.StockTxnEntity
import com.rite.pillcounting.core.room.models.dtos.RequestedDrugDto
import com.rite.pillcounting.core.room.models.enums.CountStatus
import kotlinx.coroutines.flow.Flow

/**
 * **Stock Transaction Data Access Object**
 *
 * Manages [StockTxnEntity] — the per-drug-in-batch headers of the normalized stock model
 * (`batch` → `stock_txn` → `bottle_info`). Dispense transactions live in `pill_count_txn`
 * and are handled by [PillCountTxnDao]; this DAO never touches that table.
 */
@Dao
interface StockTxnDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(txn: StockTxnEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(txns: List<StockTxnEntity>): List<Long>

    @Update
    suspend fun update(txn: StockTxnEntity)

    /**
     * Safe upsert preserving [StockTxnEntity.txnId]. Inserts a new header when [txnId] is 0
     * (or missing), otherwise updates the existing row in place.
     */
    @Transaction
    suspend fun upsertPreservingId(txn: StockTxnEntity): Long {
        return if (txn.txnId != 0L) {
            val existing = getById(txn.txnId)
            if (existing != null) {
                update(txn.copy(txnId = existing.txnId))
                existing.txnId
            } else {
                val newId = insertIgnore(txn)
                if (newId == -1L) {
                    getById(txn.txnId)?.txnId
                        ?: throw IllegalStateException("Stock txn insert failed unexpectedly")
                } else newId
            }
        } else {
            val newId = insertIgnore(txn)
            if (newId == -1L) throw IllegalStateException("Insert failed: stock txn already exists")
            newId
        }
    }

    @Query("SELECT * FROM stock_txn WHERE txnId = :id LIMIT 1")
    suspend fun getById(id: Long): StockTxnEntity?

    /**
     * The stock header for a given drug within a batch. Stock counting reuses one header per
     * `(drugId, batchId)`; all its lot/expiry variants hang off it as `bottle_info` rows.
     */
    @Query(
        """
        SELECT * FROM stock_txn
        WHERE batchId = :batchId
          AND drugId = :drugId
          AND isDeleted = 0
        LIMIT 1
        """
    )
    suspend fun findByDrugInBatch(batchId: Long, drugId: Long): StockTxnEntity?

    @Query("SELECT * FROM stock_txn WHERE batchId = :batchId AND isDeleted = 0")
    suspend fun getByBatchId(batchId: Long): List<StockTxnEntity>

    /**
     * Distinct NDCs of the drugs requested in a batch, read from the stock-txn headers joined to
     * `drug_master`. A PMS (INR^U04) batch is pre-populated with one header per requested drug and
     * NO `bottle_info` lines yet, so this — not `bottle_info` — is the source of truth for the
     * "expected NDCs" allowlist. Blank/null NDCs are excluded.
     */
    @Query(
        """
        SELECT DISTINCT dm.ndc FROM stock_txn AS s
        INNER JOIN drug_master AS dm ON s.drugId = dm.drugId
        WHERE s.batchId = :batchId
          AND s.isDeleted = 0
          AND dm.ndc IS NOT NULL
          AND dm.ndc != ''
        """
    )
    suspend fun getNdcsForBatch(batchId: Long): List<String>

    /**
     * Observes the drugs requested in a batch, one row per distinct stock-txn header joined to
     * `drug_master`. Powers the Recent Counts list so a PMS batch shows its requested drugs by
     * default (with a zero count) before any bottle is scanned; counted drugs are then rendered
     * from their `bottle_info` totals instead. Ordered by drug name to match the bottle-line query.
     */
    @Query(
        """
        SELECT DISTINCT s.drugId AS drugId, dm.ndc AS ndc, dm.drugName AS drugName
        FROM stock_txn AS s
        INNER JOIN drug_master AS dm ON s.drugId = dm.drugId
        WHERE s.batchId = :batchId AND s.isDeleted = 0
        ORDER BY dm.drugName ASC
        """
    )
    fun observeRequestedDrugs(batchId: Long): Flow<List<RequestedDrugDto>>

    /** Distinct-drug (NDC) count in a batch — mirrors the number of drug groups shown in the UI. */
    @Query("SELECT COUNT(DISTINCT drugId) FROM stock_txn WHERE batchId = :batchId AND isDeleted = 0")
    suspend fun getUniqueNdcCountForBatch(batchId: Long): Int

    /**
     * Recomputes and persists `batch.totalNdcs` from the current distinct-drug count of the batch's
     * stock transactions. Call whenever a stock txn is added/removed so the stored total stays live.
     */
    @Query(
        """
        UPDATE batch
        SET totalNdcs = (
            SELECT COUNT(DISTINCT drugId) FROM stock_txn
            WHERE batchId = :batchId AND isDeleted = 0
        )
        WHERE batchId = :batchId
        """
    )
    suspend fun refreshBatchTotalNdcs(batchId: Long)

    /** Persists the display name of the user running the count onto the batch. */
    @Query("UPDATE batch SET userName = :userName WHERE batchId = :batchId")
    suspend fun updateBatchUserName(batchId: Long, userName: String?)

    @Query("UPDATE stock_txn SET isDeleted = 1, updatedAt = :now WHERE txnId = :txnId")
    suspend fun softDelete(txnId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE stock_txn SET status = :status, updatedAt = :now WHERE txnId = :txnId")
    suspend fun updateStatus(
        txnId: Long,
        status: CountStatus,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM stock_txn WHERE batchId IN (:batchIds)")
    suspend fun deleteByBatchIds(batchIds: List<Long>)

    @Query("DELETE FROM stock_txn")
    suspend fun deleteAll()
}
