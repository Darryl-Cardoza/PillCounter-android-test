package com.dispensesure.retail.core.room.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing one count (one ADD) of a dispense transaction.
 *
 * Each record links to a parent transaction in [DispenseTxnEntity].
 *
 * @property countId      Auto-generated primary key of the count.
 * @property txnId        Foreign key referencing the parent transaction ID (nullable due to SET_NULL).
 * @property txnDetailsNo Sequential number for the detail within a transaction.
 * @property pillCount    Number of pills counted in this detail line.
 * @property imagePath    Path/URI to an associated image, if any.
 * @property rawImagePath TARGET_VERIFICATION crop with nothing drawn on it, if any.
 * @property step         [com.dispensesure.retail.core.models.StepState] name of the workflow step this count belongs to.
 * @property isManual     Whether this detail was entered manually (`true`) or automatically (`false`).
 * @property createdAt    Creation timestamp (epoch millis).
 * @property updatedAt    Last update timestamp (epoch millis).
 */
@Entity(
    tableName = "dispense_count",
    foreignKeys = [
        ForeignKey(
            entity = DispenseTxnEntity::class,
            parentColumns = ["txnId"],
            childColumns = ["txnId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["txnId"], name = "idx_dispense_count_txnId")
    ]
)

data class DispenseCountEntity(
    @PrimaryKey(autoGenerate = true)
    val countId: Long = 0L,

    val txnId: Long? = null,
    val pillCount: Int? = null,
    val imagePath: String? = null,
    val rawImagePath: String? = null,
    val step: String? = null,

    val isManual: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
