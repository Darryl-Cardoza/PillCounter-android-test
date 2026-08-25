package com.rite.pillcounting.core.room.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single physical bottle/loose line for a stock count — one row per
 * `(stockTxn, lot, expiry)` variant of a drug within a batch.
 *
 * Part of the normalized stock model: `batch` → [StockTxnEntity] → [BottleInfoEntity].
 * Replaces the per-`(drug, lot, expiry)` rows that previously lived in
 * `pill_count_txn` with `CountType.REGULAR`.
 *
 * @property bottleId   Auto-generated primary key.
 * @property stockTxnId FK to [StockTxnEntity.txnId]. Bottles cascade-delete with their header.
 * @property batchId    FK to [BatchEntity.batchId]. Null if the batch is deleted. Denormalized
 *                      from the parent [StockTxnEntity] so batch-scoped joins stay cheap.
 * @property lotNo      Lot/batch number from the scanned label.
 * @property expNo      Expiry (stored as a formatted string, e.g. MM-dd-yyyy).
 * @property serialNo   GS1 serial number (AI 21) of the scanned unit, if present.
 * @property bottleQty  Count of sealed bottles for this line.
 * @property looseQty   Count of loose/open pills counted for this line.
 * @property createdAt  Creation timestamp (epoch millis).
 * @property updatedAt  Last update timestamp (epoch millis).
 * @property controlledImagePaths  Absolute file paths of the encrypted pill-count images captured
 *                      during the Scan Pills session that produced this row. Populated only when
 *                      the drug is a controlled substance (DEA schedule CII–CVI); `null` for all
 *                      other drugs to avoid DB bloat. One row per Scan Pills session, so this list
 *                      is scoped to a single session.
 */
@Entity(
    tableName = "bottle_info",
    foreignKeys = [
        ForeignKey(
            entity = StockTxnEntity::class,
            parentColumns = ["txnId"],
            childColumns = ["stockTxnId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BatchEntity::class,
            parentColumns = ["batchId"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["stockTxnId"], name = "idx_bottle_info_stockTxnId"),
        Index(value = ["batchId"], name = "idx_bottle_info_batchId"),
    ]
)
data class BottleInfoEntity(
    @PrimaryKey(autoGenerate = true)
    val bottleId: Long = 0L,

    val stockTxnId: Long,
    val batchId: Long? = null,

    val lotNo: String? = null,
    val expNo: String? = null,
    val serialNo: String? = null,
    val bottleQty: Int? = null,
    val looseQty: Int? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    val controlledImagePaths: List<String>? = null,
)
