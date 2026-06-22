package com.rite.pillcounting.core.room.di

import com.rite.pillcounting.core.room.models.enums.BatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the Room [androidx.room.TypeConverter]s in [BatchConverters].
 *
 * Note: this is a TypeConverter `object`, not a Hilt `@Provides` module, but its functions
 * carry coverable logic so they are exercised here.
 */
class BatchConvertersTest {

    @Test
    fun `fromBatchStatus maps each enum to its name and null to null`() {
        assertEquals("INPROGRESS", BatchConverters.fromBatchStatus(BatchStatus.INPROGRESS))
        assertEquals("COMPLETED", BatchConverters.fromBatchStatus(BatchStatus.COMPLETED))
        assertNull(BatchConverters.fromBatchStatus(null))
    }

    @Test
    fun `toBatchStatus parses each name and maps null to null`() {
        assertEquals(BatchStatus.INPROGRESS, BatchConverters.toBatchStatus("INPROGRESS"))
        assertEquals(BatchStatus.COMPLETED, BatchConverters.toBatchStatus("COMPLETED"))
        assertNull(BatchConverters.toBatchStatus(null))
    }
}
