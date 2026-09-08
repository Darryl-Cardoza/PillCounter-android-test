package com.rite.pillcounting.core.room.di

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Room TypeConverter: `List<String>?` ↔ JSON string. null ↔ null. */
object StringListConverter {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    @JvmStatic
    fun fromList(value: List<String>?): String? =
        value?.let { Json.encodeToString(serializer, it) }

    @TypeConverter
    @JvmStatic
    fun toList(value: String?): List<String>? =
        value?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }
}
