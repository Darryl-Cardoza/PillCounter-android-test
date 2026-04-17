package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rite.pillcounting.core.room.models.DrugMasterEntity

/**
 * Data Access Object (DAO) for managing [DrugMasterEntity] persistence.
 *
 * ### Design Principles
 * - **Stable Primary Key**: `drugId` (PK) is the only FK exposed to other tables
 *   (e.g., [com.rite.pillcounting.core.room.models.PillCountTxnEntity]).
 * - **Preserve IDs**: All upserts ensure the same `drugId` is reused if an NDC already exists.
 * - **Safe Defaults**:
 *   - Avoid `OnConflictStrategy.REPLACE` (can reset PKs and break FKs).
 *   - Prefer `IGNORE + update in place`.
 * - **Reactive Support**: Provides both `suspend` and `Flow` queries for UI observation.
 */
@Dao
interface DrugMasterDao {

    /* ────────────────────────── Insert / Update ────────────────────────── */

    /**
     * Inserts a new drug into the [drug_master] table.
     *
     * - Fails silently if an entry with the same [DrugMasterEntity.ndc] already exists.
     * - Returns:
     *   - Newly inserted rowId (drugId) if successful.
     *   - `-1` if ignored due to conflict.
     *
     * @param drug The [DrugMasterEntity] to insert.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(drug: DrugMasterEntity): Long

    /**
     * Updates an existing drug by matching on its [DrugMasterEntity.drugId].
     *
     * @param drug The entity with updated values (must have a valid PK).
     */
    @Update
    suspend fun update(drug: DrugMasterEntity)

    /**
     * Safely upserts a single [DrugMasterEntity].
     *
     * - If a row with the same NDC exists:
     *   - Reuses its stable [drugId].
     *   - Updates the row in place with new values.
     * - If no match is found:
     *   - Inserts a new row and returns its new [drugId].
     * - Prevents accidental PK resets or duplicate entries.
     *
     * @param drug The drug entity to insert or update.
     * @return The stable primary key ([drugId]).
     */
    @Transaction
    suspend fun upsertPreservingId(drug: DrugMasterEntity): Long {
        val existingId = getDrugIdByNdc(drug.ndc)
        return if (existingId != null) {
            update(drug.copy(drugId = existingId))
            existingId
        } else {
            val newId = insertIgnore(drug)
            if (newId == -1L) {
                // Possible race condition: fetch again
                getDrugIdByNdc(drug.ndc)
                    ?: throw IllegalStateException("Drug insert failed unexpectedly")
            } else {
                newId
            }
        }
    }

    /* ───────────────────────────── Queries ────────────────────────────── */

    /**
     * Retrieves a drug by its National Drug Code (NDC).
     *
     * @param ndc National Drug Code string.
     * @return The matching [DrugMasterEntity], or `null` if not found.
     */
    @Query("SELECT * FROM drug_master WHERE ndc = :ndc LIMIT 1")
    suspend fun getDrugByNdc(ndc: String): DrugMasterEntity?

    /**
     * Retrieves a drug by its GTIN.
     *
     * @param gtin Global Trade Item Number.
     * @return The matching [DrugMasterEntity], or `null` if not found.
     */
    @Query("SELECT * FROM drug_master WHERE gtin = :gtin LIMIT 1")
    suspend fun getDrugByGtin(gtin: String): DrugMasterEntity?

    /**
     * Retrieves only the primary key ([drugId]) for a given NDC.
     *
     * @param ndc National Drug Code string.
     * @return [drugId] if found, or `null` if not present.
     */
    @Query("SELECT drugId FROM drug_master WHERE ndc = :ndc LIMIT 1")
    suspend fun getDrugIdByNdc(ndc: String): Long?


    @Query("SELECT * FROM drug_master WHERE drugId = :drugId LIMIT 1")
    suspend fun getDrugById(drugId: Long?): DrugMasterEntity?

}
