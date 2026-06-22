package com.rite.pillcounting.core.room.models.enums

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CountStatusTest {

    @Test
    fun values_containsAllConstants() {
        assertArrayEquals(
            arrayOf(
                CountStatus.PARTIAL,
                CountStatus.COMPLETED,
                CountStatus.FORCE_COMPLETED,
                CountStatus.ON_HOLD
            ),
            CountStatus.values()
        )
    }

    @Test
    fun valueOf_eachConstant() {
        assertEquals(CountStatus.PARTIAL, CountStatus.valueOf("PARTIAL"))
        assertEquals(CountStatus.COMPLETED, CountStatus.valueOf("COMPLETED"))
        assertEquals(CountStatus.FORCE_COMPLETED, CountStatus.valueOf("FORCE_COMPLETED"))
        assertEquals(CountStatus.ON_HOLD, CountStatus.valueOf("ON_HOLD"))
    }
}
