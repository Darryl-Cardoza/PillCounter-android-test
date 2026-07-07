package com.rite.pillcounting.core.room.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.rite.pillcounting.core.room.di.PillCountTxnConverters
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType

/**
 * Stock/inventory transaction header — one row per drug counted inside a batch.
 *
 * Part of the normalized stock model: `batch` → [StockTxnEntity] → [BottleInfoEntity].
 * A [StockTxnEntity] groups all the physical bottle/loose lines ([BottleInfoEntity])
 * counted for a single drug within one [BatchEntity]. Dispense transactions stay in
 * [PillCountTxnEntity]; stock transactions never touch that table.
 *
 * @property txnId     Auto-generated primary key.
 * @property drugId    FK to [DrugMasterEntity.drugId]. Null if the drug is deleted.
 * @property countType Always [CountType.REGULAR] for stock; kept for symmetry/reporting.
 * @property status    Transaction status (requires a TypeConverter).
 * @property isDeleted Soft-delete flag.
 * @property createdAt Creation timestamp (epoch millis).
 * @property updatedAt Last update timestamp (epoch millis).
 * @property bucketId  Optional container/bucket label.
 * @property batchId   FK to [BatchEntity.batchId]. Null if the batch is deleted.
 */
@Entity(
    tableName = "stock_txn",
    foreignKeys = [
        ForeignKey(
            entity = DrugMasterEntity::class,
            parentColumns = ["drugId"],
            childColumns = ["drugId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = BatchEntity::class,
            parentColumns = ["batchId"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["drugId"], name = "idx_stock_txn_drugId"),
        Index(value = ["batchId"], name = "idx_stock_txn_batchId"),
    ]
)
@TypeConverters(PillCountTxnConverters::class)
data class StockTxnEntity(
    @PrimaryKey(autoGenerate = true)
    val txnId: Long = 0L,

    val drugId: Long? = null,

    val countType: CountType = CountType.REGULAR,
    val status: CountStatus = CountStatus.PARTIAL,

    val isDeleted: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    val bucketId: String? = null,
    val batchId: Long? = null,
)
