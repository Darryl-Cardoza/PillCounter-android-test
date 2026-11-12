package com.dispensesure.retail.core.room.di

import com.dispensesure.retail.core.room.models.enums.BatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the Room [androidx.room.TypeConverter]s in [StockBatchConverters].
 *
 * Note: this is a TypeConverter `object`, not a Hilt `@Provides` module, but its functions
 * carry coverable logic so they are exercised here.
 */
class StockBatchConvertersTest {

    @Test
    fun `fromBatchStatus maps each enum to its name and null to null`() {
        assertEquals("INPROGRESS", StockBatchConverters.fromBatchStatus(BatchStatus.INPROGRESS))
        assertEquals("COMPLETED", StockBatchConverters.fromBatchStatus(BatchStatus.COMPLETED))
        assertNull(StockBatchConverters.fromBatchStatus(null))
    }

    @Test
    fun `toBatchStatus parses each name and maps null to null`() {
        assertEquals(BatchStatus.INPROGRESS, StockBatchConverters.toBatchStatus("INPROGRESS"))
        assertEquals(BatchStatus.COMPLETED, StockBatchConverters.toBatchStatus("COMPLETED"))
        assertNull(StockBatchConverters.toBatchStatus(null))
    }
}
