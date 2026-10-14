package com.dispensesure.retail.core.scanning.logic

import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDecoderTest {

    private val decoder = ScanDecoder(BarcodeDecoder())

    @Test
    fun `GS1 label gives GTIN, expiry and lot`() {
        val scan = decoder.decode("]C1010036854010001417251231" + "10LOT123")

        assertTrue(scan.isGs1)
        assertEquals("00368540100014", scan.gtin14)
        assertEquals("12-31-2025", scan.expiry)
        assertEquals("LOT123", scan.lot)
        assertNull(scan.serial)
    }

    @Test
    fun `GS1 label gives serial`() {
        val scan = decoder.decode("]C1010036854010001417251231" + "21SER456")

        assertEquals("00368540100014", scan.gtin14)
        assertEquals("SER456", scan.serial)
    }

    @Test
    fun `plain 12-digit UPC is padded to GTIN-14`() {
        val scan = decoder.decode("368540100014")

        assertFalse(scan.isGs1)
        assertEquals("00368540100014", scan.gtin14)
        assertNull(scan.lot)
        assertNull(scan.expiry)
    }

    @Test
    fun `value that is not a barcode has no GTIN`() {
        val scan = decoder.decode("AB")

        assertFalse(scan.isGs1)
        assertNull(scan.gtin14)
    }
}
