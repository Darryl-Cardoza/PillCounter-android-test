package com.rite.pillcounting.core.room.models.dtos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSummaryDtoTest {

    private fun sample() = BatchSummaryDto(
        batchId = 1L,
        createdAt = 100L,
        uniqueNdcCount = 3,
        status = "INPROGRESS",
        bucketId = "bucket",
        requestIdFromPMS = "pms"
    )

    @Test
    fun getters() {
        val d = sample()
        assertEquals(1L, d.batchId)
        assertEquals(100L, d.createdAt)
        assertEquals(3, d.uniqueNdcCount)
        assertEquals("INPROGRESS", d.status)
        assertEquals("bucket", d.bucketId)
        assertEquals("pms", d.requestIdFromPMS)
    }

    @Test
    fun nullableNull() {
        val d = sample().copy(bucketId = null, requestIdFromPMS = null)
        assertNull(d.bucketId)
        assertNull(d.requestIdFromPMS)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(batchId = 2L))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("batchId=1"))
    }

    @Test
    fun componentN() {
        val d = sample()
        assertEquals(1L, d.component1())
        assertEquals(100L, d.component2())
        assertEquals(3, d.component3())
        assertEquals("INPROGRESS", d.component4())
        assertEquals("bucket", d.component5())
        assertEquals("pms", d.component6())
    }
}
