package com.rite.pillcounting.core.room.models.enums

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CountTypeTest {

    @Test
    fun values_containsAllConstants() {
        assertArrayEquals(
            arrayOf(CountType.FIXED, CountType.REGULAR),
            CountType.values()
        )
    }

    @Test
    fun valueOf_eachConstant() {
        assertEquals(CountType.FIXED, CountType.valueOf("FIXED"))
        assertEquals(CountType.REGULAR, CountType.valueOf("REGULAR"))
    }
}
