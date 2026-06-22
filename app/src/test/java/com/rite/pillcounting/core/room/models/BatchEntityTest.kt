package com.rite.pillcounting.core.room.models

import com.rite.pillcounting.core.room.models.enums.BatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchEntityTest {

    private fun sample() = BatchEntity(
        batchId = 5L,
        startDateTime = 1000L,
        endDateTime = 2000L,
        status = BatchStatus.COMPLETED,
        isDeleted = true,
        note = "note",
        bucketId = "bucket",
        requestIdFromPMS = "pms",
        isSynced = true
    )

    @Test
    fun defaultValues() {
        val e = BatchEntity()
        assertEquals(0L, e.batchId)
        assertTrue(e.startDateTime > 0L)
        assertNull(e.endDateTime)
        assertEquals(BatchStatus.INPROGRESS, e.status)
        assertFalse(e.isDeleted)
        assertNull(e.note)
        assertNull(e.bucketId)
        assertNull(e.requestIdFromPMS)
        assertFalse(e.isSynced)
    }

    @Test
    fun getters() {
        val e = sample()
        assertEquals(5L, e.batchId)
        assertEquals(1000L, e.startDateTime)
        assertEquals(2000L, e.endDateTime)
        assertEquals(BatchStatus.COMPLETED, e.status)
        assertTrue(e.isDeleted)
        assertEquals("note", e.note)
        assertEquals("bucket", e.bucketId)
        assertEquals("pms", e.requestIdFromPMS)
        assertTrue(e.isSynced)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(batchId = 9L))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("batchId=5"))
    }

    @Test
    fun copy() {
        assertEquals(7L, sample().copy(batchId = 7L).batchId)
    }

    @Test
    fun componentN() {
        val e = sample()
        assertEquals(5L, e.component1())
        assertEquals(1000L, e.component2())
        assertEquals(2000L, e.component3())
        assertEquals(BatchStatus.COMPLETED, e.component4())
        assertEquals(true, e.component5())
        assertEquals("note", e.component6())
        assertEquals("bucket", e.component7())
        assertEquals("pms", e.component8())
        assertEquals(true, e.component9())
    }
}
