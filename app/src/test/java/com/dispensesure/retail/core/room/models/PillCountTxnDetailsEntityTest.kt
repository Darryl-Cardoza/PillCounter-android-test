package com.dispensesure.retail.core.room.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PillCountTxnDetailsEntityTest {

    private fun sample() = PillCountTxnDetailsEntity(
        txnDetailsId = 2L,
        txnId = 9L,
        pillCount = 15,
        imagePath = "img.png",
        rawImagePath = "img_raw.png",
        type = "fixed",
        isManual = true,
        isDeleted = true,
        createdAt = 100L,
        updatedAt = 200L
    )

    @Test
    fun defaultValues() {
        val e = PillCountTxnDetailsEntity()
        assertEquals(0L, e.txnDetailsId)
        assertNull(e.txnId)
        assertNull(e.pillCount)
        assertNull(e.imagePath)
        assertNull(e.rawImagePath)
        assertNull(e.type)
        assertFalse(e.isManual)
        assertFalse(e.isDeleted)
        assertTrue(e.createdAt > 0L)
        assertTrue(e.updatedAt > 0L)
    }

    @Test
    fun getters() {
        val e = sample()
        assertEquals(2L, e.txnDetailsId)
        assertEquals(9L, e.txnId)
        assertEquals(15, e.pillCount)
        assertEquals("img.png", e.imagePath)
        assertEquals("img_raw.png", e.rawImagePath)
        assertEquals("fixed", e.type)
        assertTrue(e.isManual)
        assertTrue(e.isDeleted)
        assertEquals(100L, e.createdAt)
        assertEquals(200L, e.updatedAt)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(txnDetailsId = 99L))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("txnDetailsId=2"))
    }

    @Test
    fun copy() {
        assertEquals(5, sample().copy(pillCount = 5).pillCount)
    }

    @Test
    fun componentN() {
        val e = sample()
        assertEquals(2L, e.component1())
        assertEquals(9L, e.component2())
        assertEquals(15, e.component3())
        assertEquals("img.png", e.component4())
        assertEquals("img_raw.png", e.component5())
        assertEquals("fixed", e.component6())
        assertEquals(true, e.component7())
        assertEquals(true, e.component8())
        assertEquals(100L, e.component9())
        assertEquals(200L, e.component10())
    }
}
