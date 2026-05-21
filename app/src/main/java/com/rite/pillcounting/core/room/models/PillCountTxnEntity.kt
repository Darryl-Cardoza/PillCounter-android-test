package com.rite.pillcounting.core.room.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.rite.pillcounting.core.room.di.PillCountTxnConverters
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority

/**
 * Entity representing a pill count transaction (header/master).
 *
 * Each transaction can belong to a user and a drug. Both FKs are nullable
 * because `onDelete = SET_NULL` is used on the foreign keys.
 *
 * @property txnId        Auto-generated primary key.
 * @property localId       FK to [UserEntity.localId]. Null if the user is deleted.
 * @property drugId       FK to [DrugMasterEntity.drugId]. Null if the drug is deleted (type must match parent: Long?).
 * @property countType    How the count is performed (requires a TypeConverter).
 * @property targetCount  Expected/target count for reconciliation.
 * @property status       Transaction status (requires a TypeConverter).
 * @property note         Free-form note for the transaction.
 * @property expiry       Optional expiry (stored as string; consider epoch millis for strictness).
 * @property lotNo        Lot/batch number.
 * @property barcodeImage Path/URI to a barcode image, if captured.
 * @property isSubstitute Whether a substitute drug was used.
 * @property rxNo         Prescription number.
 * @property refillNo     Refill number or code.
 * @property patientName  Patient display name associated with the transaction.
 * @property isDeleted    Soft-delete flag.
 * @property createdAt    Creation timestamp (epoch millis).
 * @property updatedAt    Last update timestamp (epoch millis).
 */
@Entity(
    tableName = "pill_count_txn",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["localId"],
            childColumns = ["localId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = DrugMasterEntity::class,
            parentColumns = ["drugId"],
            childColumns = ["drugId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = DrugMasterEntity::class,
            parentColumns = ["drugId"],
            childColumns = ["substitutedDrugId"],
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
        Index(value = ["localId"], name = "idx_txn_localId"),
        Index(value = ["drugId"], name = "idx_txn_drugId"),
        Index(value = ["substitutedDrugId"], name = "idx_txn_substitutedDrugId"),
        Index(value = ["batchId"], name = "idx_txn_batchId"),
    ]
)

@TypeConverters(PillCountTxnConverters::class)
data class PillCountTxnEntity(
    @PrimaryKey(autoGenerate = true)
    val txnId: Long = 0L,

    val localId: Long? = null,
    val drugId: Long? = null,

    val countType: CountType,
    val targetCount: Int? = null,
    val status: CountStatus,

    val note: String? = null,
    val expiry: String? = null,
    val lotNo: String? = null,
    val barcodeImage: String? = null,
    val isSubstitute: Boolean = false,
    val rxNo: String? = null,
    val refillNo: String? = null,
    val patientName: String? = null,
    val isDeleted: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    /** Require for HL7 flow **/
    val isComingFromHL7: Boolean? = null,
    val isSynced: Boolean? = null,
    val isNdcVerified: Boolean? = null,

    val bucketId: String? = null,
    val batchId: Long? = null,
    val bottleQty: Int? = null,
    val looseQty: Int? = null,
    val substitutedDrugId: Long? = null,

    val workflowStep: String? = null,

    val priority: TxnPriority? = null

)
