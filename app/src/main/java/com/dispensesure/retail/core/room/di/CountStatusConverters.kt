package com.dispensesure.retail.core.room.di

import androidx.room.TypeConverter
import com.dispensesure.retail.core.room.models.enums.CountStatus

/**
 * Room [TypeConverter]s for mapping [CountStatus]
 * to and from their String representations for persistence in the database.
 *
 * By default, Room cannot store enums directly, so we persist them as
 * their enum constant [name] and reconstruct them during reads.
 */
object CountStatusConverters {

    /* ───────────────────────────── CountStatus ───────────────────────────── */

    /**
     * Converts a [CountStatus] enum into its [String] name for storage in Room.
     *
     * @param value The [CountStatus] enum value, or null.
     * @return The enum name as a string, or null if [value] is null.
     */
    @TypeConverter
    @JvmStatic
    fun fromCountStatus(value: CountStatus?): String? = value?.name

    /**
     * Converts a stored [String] name back into a [CountStatus] enum.
     *
     * @param value The stored enum name string, or null.
     * @return The corresponding [CountStatus] enum, or null if [value] is null.
     * @throws IllegalArgumentException if the string does not match any enum constant.
     */
    @TypeConverter
    @JvmStatic
    fun toCountStatus(value: String?): CountStatus? =
        value?.let { CountStatus.valueOf(it) }
}
