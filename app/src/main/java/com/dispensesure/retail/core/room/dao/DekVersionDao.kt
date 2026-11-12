package com.dispensesure.retail.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.dispensesure.retail.core.room.models.DekVersionEntity

@Dao
interface DekVersionDao {

    @Query("SELECT * FROM dek_versions WHERE is_active = 1 LIMIT 1")
    suspend fun getActive(): DekVersionEntity?

    @Query("SELECT * FROM dek_versions ORDER BY id")
    suspend fun getAll(): List<DekVersionEntity>

    @Insert
    suspend fun insert(row: DekVersionEntity): Long

    @Query("UPDATE dek_versions SET is_active = 0 WHERE is_active = 1")
    suspend fun deactivateAll()

    /** Adds [row] as the only active row; older rows stay as history. */
    @Transaction
    suspend fun insertAsActive(row: DekVersionEntity) {
        deactivateAll()
        insert(row.copy(isActive = true))
    }

    /** The first row of [version] wrapped by a server KEK: the one registration always sends. */
    @Query("SELECT * FROM dek_versions WHERE version = :version AND kek_key_id != :bootstrapKekId ORDER BY id LIMIT 1")
    suspend fun getOldestServerWrapped(version: Int, bootstrapKekId: String): DekVersionEntity?

    @Query("UPDATE dek_versions SET is_registered = 1 WHERE version = :version AND kek_key_id != :bootstrapKekId")
    suspend fun markVersionRegistered(version: Int, bootstrapKekId: String)

    @Query("UPDATE dek_versions SET version = :newVersion WHERE version = :oldVersion AND is_registered = 0")
    suspend fun renumberUnregistered(oldVersion: Int, newVersion: Int)
}
