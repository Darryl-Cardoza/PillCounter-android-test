package com.rite.pillcounting.core.room.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing the master record of a drug in the local Room database.
 *
 * @property drugId      Auto-generated unique identifier for each drug.
 * @property drugName    Display name of the drug.
 * @property ndc         National Drug Code (unique identifier per drug).
 * @property drugType    Type/classification of the drug (e.g., tablet, capsule).
 * @property createdAt   Timestamp (epoch millis) when the record was created.
 */
@Entity(
    tableName = "drug_master",
    indices = [Index(value = ["ndc"], unique = true)]
)
data class DrugMasterEntity(
    @PrimaryKey(autoGenerate = true)
    val drugId: Long = 0L,
    val drugName: String? = null,
    val ndc: String,
    val drugType: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val gtin: String?= null,
    val packageQty: Int?= 0
)
