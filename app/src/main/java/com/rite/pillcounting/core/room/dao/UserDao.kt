package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rite.pillcounting.core.room.models.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * **User Data Access Object (DAO)**
 *
 * Manages persistence of [UserEntity] records within the local Room database.
 * Provides consistent, reactive access to user profile information while
 * maintaining strict key integrity and avoiding destructive operations.
 *
 * ---
 * ### ⚙️ Core Principles
 * - **Stable Primary Key (`localId`)**
 *   - The Room `localId` acts as the *true internal FK reference* for all related tables.
 *   - `localId` is never recreated or replaced.
 * - **Immutable Business Identifier (`userId`)**
 *   - `userId` corresponds to the server or JWT identifier.
 *   - It is unique but not used as a foreign key.
 * - **Upsert Safety**
 *   - Never use `REPLACE` since it resets primary keys.
 *   - Use `upsertPreservingLocalId()` to safely insert or update without breaking links.
 * - **Reactive Design**
 *   - `Flow` queries are used for live UI updates (e.g., profile screens).
 *
 * ---
 * ### Example Usage
 * ```kotlin
 * val userDao: UserDao = db.userDao()
 * val currentUser = userDao.getByUserId("USR-001")
 * userDao.upsertPreservingLocalId(newUserEntity)
 * userDao.observeByLocalId(currentUser.localId).collect { user -> ... }
 * ```
 */
@Dao
interface UserDao {

    // ───────────────────────────── Insert / Update ─────────────────────────────

    /**
     * Inserts a new [UserEntity] record into the database.
     *
     * - Uses [OnConflictStrategy.IGNORE] to avoid replacing existing rows.
     * - Ensures that the primary key (`localId`) remains stable.
     * - Returns `-1` if a duplicate `userId` already exists.
     *
     * @param user The user entity to insert.
     * @return The generated `localId` (row ID) if inserted, or `-1` if ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(user: UserEntity): Long

    /**
     * Updates an existing [UserEntity] based on its primary key (`localId`).
     *
     * - Only non-null fields in [user] are updated.
     * - Intended for local profile updates or post-sync state adjustments.
     * - Does not create new rows — use [upsertPreservingLocalId] for safe creation.
     *
     * @param user The user entity with updated values.
     */
    @Update
    suspend fun update(user: UserEntity)

    /**
     * Safely upserts a [UserEntity] while preserving its `localId`.
     *
     * - If a user with the same `userId` exists, it updates that record in place.
     * - If no user exists, it inserts a new one and auto-generates a `localId`.
     * - Prevents destructive replacement of primary keys.
     *
     * @param user The user entity to insert or update.
     * @return The stable `localId` (Room primary key) of the inserted or updated user.
     * @throws IllegalStateException if the insert fails unexpectedly due to concurrency.
     */
    @Transaction
    suspend fun upsertPreservingLocalId(user: UserEntity): Long {
        val existing = getByUserId(user.userId)
        return if (existing != null) {
            val toSave = user.copy(localId = existing.localId)
            update(toSave)
            existing.localId
        } else {
            insertIgnore(user).let { newId ->
                if (newId == -1L) {
                    // Race condition: inserted concurrently by another coroutine → re-fetch
                    getByUserId(user.userId)?.localId
                        ?: throw IllegalStateException("User insert failed unexpectedly")
                } else {
                    newId
                }
            }
        }
    }

    // ──────────────────────────────── Reads ────────────────────────────────

    /**
     * Observes the [UserEntity] with the specified `localId`.
     *
     * - Emits updates whenever the user record changes in the database.
     * - Commonly used for displaying the active user profile.
     *
     * @param localId The Room primary key for the user.
     * @return A [Flow] emitting the current [UserEntity] or `null` if not found.
     */
    @Query("SELECT * FROM users WHERE localId = :localId LIMIT 1")
    fun observeByLocalId(localId: Long): Flow<UserEntity?>

    /**
     * Retrieves a user record using the Room primary key (`localId`), as a one-shot read.
     *
     * - Unlike [observeByLocalId], this suspends and returns once rather than streaming.
     * - Intended for one-time lookups (e.g. reading the current session's email).
     * - Transactions reference their operator with [PillCountTxnEntity.localId], which is a FK to
     *   [UserEntity.localId] — not the business `userId`. Resolving that FK through
     *   [getByUserId] never matches, so the operator came back null and outbound HL7 carried no
     *   dispensing provider at all.
     *
     * @param localId The Room primary key for the user.
     * @return The matching [UserEntity], or `null` if not found.
     */
    @Query("SELECT * FROM users WHERE localId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: Long): UserEntity?

    /**
     * Retrieves a user record using the external or business identifier (`userId`).
     *
     * - Typically used during login or sync operations.
     * - Returns `null` if no user with the given `userId` exists.
     *
     * @param userId The external (server-assigned) user ID.
     * @return The matching [UserEntity], or `null` if not found.
     */
    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun getByUserId(userId: String): UserEntity?
}
