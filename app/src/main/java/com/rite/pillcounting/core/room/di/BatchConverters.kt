package com.rite.pillcounting.core.room.di

import androidx.room.TypeConverter
import com.rite.pillcounting.core.room.models.enums.BatchStatus

/**
 * Room [TypeConverter]s for mapping enum types ([BatchStatus])
 * to and from their String representations for persistence in the database.
 *
 * By default, Room cannot store enums directly, so we persist them as
 * their enum constant [name] and reconstruct them during reads.
 */
object BatchConverters {

    /* ────────────────────────────── BatchStatus ────────────────────────────── */

    /**
     * Converts a [BatchStatus] enum into its [String] name for storage in Room.
     *
     * @param value The [BatchStatus] enum value, or null.
     * @return The enum name as a string, or null if [value] is null.
     */
    @TypeConverter
    @JvmStatic
    fun fromBatchStatus(value: BatchStatus?): String? = value?.name

    /**
     * Converts a stored [String] name back into a [BatchStatus] enum.
     *
     * @param value The stored enum name string, or null.
     * @return The corresponding [BatchStatus] enum, or null if [value] is null.
     * @throws IllegalArgumentException if the string does not match any enum constant.
     */
    @TypeConverter
    @JvmStatic
    fun toBatchStatus(value: String?): BatchStatus? =
        value?.let { BatchStatus.valueOf(it) }
}

