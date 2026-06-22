package com.rite.pillcounting.core.room.models.enums

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxnPriorityTest {

    @Test
    fun values_containsAllConstants() {
        assertArrayEquals(
            arrayOf(TxnPriority.High, TxnPriority.Medium, TxnPriority.Low),
            TxnPriority.values()
        )
    }

    @Test
    fun valueOf_eachConstant() {
        assertEquals(TxnPriority.High, TxnPriority.valueOf("High"))
        assertEquals(TxnPriority.Medium, TxnPriority.valueOf("Medium"))
        assertEquals(TxnPriority.Low, TxnPriority.valueOf("Low"))
    }

    @Test
    fun sortOrder_values() {
        assertEquals(1, TxnPriority.High.sortOrder)
        assertEquals(2, TxnPriority.Medium.sortOrder)
        assertEquals(3, TxnPriority.Low.sortOrder)
    }

    @Test
    fun fromString_exactMatch() {
        assertEquals(TxnPriority.High, TxnPriority.fromString("High"))
        assertEquals(TxnPriority.Medium, TxnPriority.fromString("Medium"))
        assertEquals(TxnPriority.Low, TxnPriority.fromString("Low"))
    }

    @Test
    fun fromString_caseInsensitive() {
        assertEquals(TxnPriority.High, TxnPriority.fromString("high"))
        assertEquals(TxnPriority.Medium, TxnPriority.fromString("MEDIUM"))
        assertEquals(TxnPriority.Low, TxnPriority.fromString("lOw"))
    }

    @Test
    fun fromString_null_returnsNull() {
        assertNull(TxnPriority.fromString(null))
    }

    @Test
    fun fromString_unknown_returnsNull() {
        assertNull(TxnPriority.fromString("Urgent"))
        assertNull(TxnPriority.fromString(""))
    }
}
