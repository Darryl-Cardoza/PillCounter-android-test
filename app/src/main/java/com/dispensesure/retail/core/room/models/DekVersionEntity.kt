package com.dispensesure.retail.core.room.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One wrapped value of the DB DEK; a new row each time the wrap changes, never deleted.
 * `version` changes only with the raw DEK; `is_registered` means the server holds that version.
 */
@Entity(tableName = "dek_versions")
data class DekVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "dek_key_id") val dekKeyId: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "wrapped_dek") val wrappedDek: ByteArray,
    @ColumnInfo(name = "kek_key_id") val kekKeyId: String,
    @ColumnInfo(name = "kek_version") val kekVersion: Int,
    @ColumnInfo(name = "kek_algorithm") val kekAlgorithm: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "is_registered") val isRegistered: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
