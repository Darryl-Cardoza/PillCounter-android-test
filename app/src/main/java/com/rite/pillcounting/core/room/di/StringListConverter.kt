package com.rite.pillcounting.core.room.di

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room TypeConverter for `List<String>?` ↔ JSON `String?`.
 *
 * Description:
 * Encodes a list of strings as a JSON array string for storage in a single TEXT column;
 * decodes back on read. `null` in ↔ `null` out.
 *
 * What it does:
 * - Uses `kotlinx.serialization` `Json.encodeToString` with `ListSerializer(String.serializer())`.
 * - Empty list encodes to `"[]"` (preserved on round-trip).
 *
 * @param value List of strings, or null.
 * @return JSON string, or null.
 *
 * Example Usage:
 * val encoded = StringListConverter.fromList(listOf("/a", "/b"))  // "[\"/a\",\"/b\"]"
 * val decoded = StringListConverter.toList(encoded)                // ["/a", "/b"]
 */
object StringListConverter {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    @JvmStatic
    fun fromList(value: List<String>?): String? =
        value?.let { Json.encodeToString(serializer, it) }

    @TypeConverter
    @JvmStatic
    fun toList(value: String?): List<String>? =
        value?.let { Json.decodeFromString(serializer, it) }
}