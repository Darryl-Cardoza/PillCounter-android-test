package com.rite.pillcounting.core.room.models.dtos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTxnDtoTest {

    private fun sample() = BatchTxnDto(
        txnId = 1L,
        drugId = 2L,
        drugName = "Drug",
        ndc = "111",
        lotNo = "lot",
        expiry = "2027",
        bottleQty = 3,
        looseQty = 4,
        packageQty = 5
    )

    @Test
    fun getters() {
        val d = sample()
        assertEquals(1L, d.txnId)
        assertEquals(2L, d.drugId)
        assertEquals("Drug", d.drugName)
        assertEquals("111", d.ndc)
        assertEquals("lot", d.lotNo)
        assertEquals("2027", d.expiry)
        assertEquals(3, d.bottleQty)
        assertEquals(4, d.looseQty)
        assertEquals(5, d.packageQty)
    }

    @Test
    fun nullableNull() {
        val d = BatchTxnDto(1L, null, null, null, null, null, null, null, null)
        assertNull(d.drugId)
        assertNull(d.drugName)
        assertNull(d.ndc)
        assertNull(d.lotNo)
        assertNull(d.expiry)
        assertNull(d.bottleQty)
        assertNull(d.looseQty)
        assertNull(d.packageQty)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(txnId = 9L))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("txnId=1"))
    }

    @Test
    fun componentN() {
        val d = sample()
        assertEquals(1L, d.component1())
        assertEquals(2L, d.component2())
        assertEquals("Drug", d.component3())
        assertEquals("111", d.component4())
        assertEquals("lot", d.component5())
        assertEquals("2027", d.component6())
        assertEquals(3, d.component7())
        assertEquals(4, d.component8())
        assertEquals(5, d.component9())
    }
}
