package com.rite.pillcounting.core.room.models.enums

/**
 * Priority levels for a pill count transaction.
 *
 * Enum names match the values stored in the database (`priority` column).
 * The [sortOrder] property drives ascending priority sort in queries:
 * High → 1, Medium → 2, Low → 3.
 */
enum class TxnPriority(val sortOrder: Int) {
    High(1),
    Medium(2),
    Low(3);

    companion object {
        fun fromString(value: String?): TxnPriority? =
            value?.let { raw -> entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
    }
}
