package com.dispensesure.retail.core.room.di

import com.dispensesure.retail.core.room.models.enums.CountStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the Room [androidx.room.TypeConverter]s in [CountStatusConverters].
 *
 * Note: this is a TypeConverter `object`, not a Hilt `@Provides` module, but its functions
 * carry coverable logic so they are exercised here.
 */
class CountStatusConvertersTest {

    @Test
    fun `fromCountStatus maps each enum to its name and null to null`() {
        assertEquals("PARTIAL", CountStatusConverters.fromCountStatus(CountStatus.PARTIAL))
        assertEquals("COMPLETED", CountStatusConverters.fromCountStatus(CountStatus.COMPLETED))
        assertEquals(
            "FORCE_COMPLETED",
            CountStatusConverters.fromCountStatus(CountStatus.FORCE_COMPLETED)
        )
        assertEquals("ON_HOLD", CountStatusConverters.fromCountStatus(CountStatus.ON_HOLD))
        assertNull(CountStatusConverters.fromCountStatus(null))
    }

    @Test
    fun `toCountStatus parses each name and maps null to null`() {
        assertEquals(CountStatus.PARTIAL, CountStatusConverters.toCountStatus("PARTIAL"))
        assertEquals(CountStatus.COMPLETED, CountStatusConverters.toCountStatus("COMPLETED"))
        assertEquals(
            CountStatus.FORCE_COMPLETED,
            CountStatusConverters.toCountStatus("FORCE_COMPLETED")
        )
        assertEquals(CountStatus.ON_HOLD, CountStatusConverters.toCountStatus("ON_HOLD"))
        assertNull(CountStatusConverters.toCountStatus(null))
    }
}
