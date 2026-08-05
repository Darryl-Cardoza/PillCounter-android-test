package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [FaceProfileEntity] rows.
 *
 * Description:
 * Backs the Quick Access Users list and registration flow.
 *
 * What it does:
 * - Reactive [observeAll] for the list screen.
 * - Plain suspend reads for matching (`FaceMatcher` needs a snapshot, not a stream).
 *
 * Example Usage:
 * val id = faceProfileDao.insert(FaceProfileEntity(firstName = "Bruce", lastName = "Wayne", email = null, createdAt = now))
 */
@Dao
interface FaceProfileDao {

    /**
     * Inserts a new face profile.
     *
     * @param profile The profile to insert (id = 0 to autogenerate).
     * @return The generated `id`.
     */
    @Insert
    suspend fun insert(profile: FaceProfileEntity): Long

    /**
     * Updates an existing face profile in place (matched by `id`).
     *
     * @param profile The profile with updated field values.
     */
    @Update
    suspend fun update(profile: FaceProfileEntity)

    /**
     * Deletes a face profile; its embeddings cascade-delete with it.
     *
     * @param profile The profile to delete.
     */
    @Delete
    suspend fun delete(profile: FaceProfileEntity)

    /**
     * Observes every enrolled profile, most recently created first.
     *
     * @return A [Flow] emitting the full profile list on every change.
     */
    @Query("SELECT * FROM face_profiles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FaceProfileEntity>>

    /**
     * Reads every *enabled* profile once — the gallery `FaceMatcher` matches against.
     *
     * @return The current list of enabled profiles.
     */
    @Query("SELECT * FROM face_profiles WHERE isEnabled = 1")
    suspend fun getEnabled(): List<FaceProfileEntity>

    /**
     * Reads a single profile by id once — a fresh DB lookup, not the cached [observeAll] list.
     *
     * @param id The profile's Room id.
     * @return The matching profile, or null if no such id exists.
     */
    @Query("SELECT * FROM face_profiles WHERE id = :id")
    suspend fun getById(id: Long): FaceProfileEntity?

    /**
     * Stamps a profile's `lastUsedAt` after a successful verify match.
     *
     * @param id The profile's Room id.
     * @param timestamp Epoch millis of the match.
     */
    @Query("UPDATE face_profiles SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: Long, timestamp: Long)
}
