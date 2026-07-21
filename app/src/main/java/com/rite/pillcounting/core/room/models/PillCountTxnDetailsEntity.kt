package com.rite.pillcounting.core.room.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing the details line of a pill count transaction.
 *
 * Each record links to a parent transaction in [PillCountTxnEntity].
 *
 * @property txnDetailsId Auto-generated primary key for the detail record.
 * @property txnId        Foreign key referencing the parent transaction ID (nullable due to SET_NULL).
 * @property txnDetailsNo Sequential number for the detail within a transaction.
 * @property pillCount    Number of pills counted in this detail line.
 * @property imagePath    Path/URI to an associated image, if any.
 * @property type         Optional category/type (e.g., "fixed", "partial").
 * @property isManual     Whether this detail was entered manually (`true`) or automatically (`false`).
 * @property isDeleted    Soft-delete flag.
 * @property createdAt    Creation timestamp (epoch millis).
 * @property updatedAt    Last update timestamp (epoch millis).
 */
@Entity(
    tableName = "pill_count_txn_details",
    foreignKeys = [
        ForeignKey(
            entity = PillCountTxnEntity::class,
            parentColumns = ["txnId"],
            childColumns = ["txnId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["txnId"], name = "idx_txn_details_txnId")
    ]
)

data class PillCountTxnDetailsEntity(
    @PrimaryKey(autoGenerate = true)
    val txnDetailsId: Long = 0L,

    val txnId: Long? = null,
    val pillCount: Int? = null,
    val imagePath: String? = null,
    val type: String? = null,

    val isManual: Boolean = false,
    val isDeleted: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
