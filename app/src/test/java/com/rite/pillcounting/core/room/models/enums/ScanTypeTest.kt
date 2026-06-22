package com.rite.pillcounting.core.room.models.enums

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanTypeTest {

    @Test
    fun values_containsAllConstants() {
        assertArrayEquals(
            arrayOf(ScanType.RX_LABEL, ScanType.BARCODE, ScanType.STOCK_COUNT),
            ScanType.values()
        )
    }

    @Test
    fun valueOf_eachConstant() {
        assertEquals(ScanType.RX_LABEL, ScanType.valueOf("RX_LABEL"))
        assertEquals(ScanType.BARCODE, ScanType.valueOf("BARCODE"))
        assertEquals(ScanType.STOCK_COUNT, ScanType.valueOf("STOCK_COUNT"))
    }
}
