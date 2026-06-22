package com.rite.pillcounting.core.room.models.enums

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchStatusTest {

    @Test
    fun values_containsAllConstants() {
        assertArrayEquals(
            arrayOf(BatchStatus.INPROGRESS, BatchStatus.COMPLETED),
            BatchStatus.values()
        )
    }

    @Test
    fun valueOf_eachConstant() {
        assertEquals(BatchStatus.INPROGRESS, BatchStatus.valueOf("INPROGRESS"))
        assertEquals(BatchStatus.COMPLETED, BatchStatus.valueOf("COMPLETED"))
    }
}
