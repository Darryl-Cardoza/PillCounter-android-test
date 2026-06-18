package com.rite.pillcounting.core.room.di

import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the Room [androidx.room.TypeConverter]s in [PillCountTxnConverters].
 *
 * Note: this is a TypeConverter `object`, not a Hilt `@Provides` module, but its functions
 * carry coverable logic so they are exercised here.
 */
class PillCountTxnConvertersTest {

    @Test
    fun `fromCountType maps each enum to its name and null to null`() {
        assertEquals("FIXED", PillCountTxnConverters.fromCountType(CountType.FIXED))
        assertEquals("REGULAR", PillCountTxnConverters.fromCountType(CountType.REGULAR))
        assertNull(PillCountTxnConverters.fromCountType(null))
    }

    @Test
    fun `toCountType parses each name and maps null to null`() {
        assertEquals(CountType.FIXED, PillCountTxnConverters.toCountType("FIXED"))
        assertEquals(CountType.REGULAR, PillCountTxnConverters.toCountType("REGULAR"))
        assertNull(PillCountTxnConverters.toCountType(null))
    }

    @Test
    fun `fromCountStatus maps each enum to its name and null to null`() {
        assertEquals("PARTIAL", PillCountTxnConverters.fromCountStatus(CountStatus.PARTIAL))
        assertEquals("COMPLETED", PillCountTxnConverters.fromCountStatus(CountStatus.COMPLETED))
        assertEquals(
            "FORCE_COMPLETED",
            PillCountTxnConverters.fromCountStatus(CountStatus.FORCE_COMPLETED)
        )
        assertEquals("ON_HOLD", PillCountTxnConverters.fromCountStatus(CountStatus.ON_HOLD))
        assertNull(PillCountTxnConverters.fromCountStatus(null))
    }

    @Test
    fun `toCountStatus parses each name and maps null to null`() {
        assertEquals(CountStatus.PARTIAL, PillCountTxnConverters.toCountStatus("PARTIAL"))
        assertEquals(CountStatus.COMPLETED, PillCountTxnConverters.toCountStatus("COMPLETED"))
        assertEquals(
            CountStatus.FORCE_COMPLETED,
            PillCountTxnConverters.toCountStatus("FORCE_COMPLETED")
        )
        assertEquals(CountStatus.ON_HOLD, PillCountTxnConverters.toCountStatus("ON_HOLD"))
        assertNull(PillCountTxnConverters.toCountStatus(null))
    }
}
