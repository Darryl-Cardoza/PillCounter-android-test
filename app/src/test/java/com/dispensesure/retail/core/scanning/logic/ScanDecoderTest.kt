package com.dispensesure.retail.core.scanning.logic

import com.dispensesure.retail.core.region.Region
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
        val scan = decoder.decode("]C1010036854010001417251231" + "10LOT123", Region.US)

        assertTrue(scan.isGs1)
        assertEquals("00368540100014", scan.gtin14)
        assertEquals("12-31-2025", scan.expiry)
        assertEquals("LOT123", scan.lot)
        assertNull(scan.serial)
    }

    @Test
    fun `GS1 label gives serial`() {
        val scan = decoder.decode("]C1010036854010001417251231" + "21SER456", Region.US)

        assertEquals("00368540100014", scan.gtin14)
        assertEquals("SER456", scan.serial)
    }

    @Test
    fun `plain 12-digit UPC is padded to GTIN-14`() {
        val scan = decoder.decode("368540100014", Region.US)

        assertFalse(scan.isGs1)
        assertEquals("00368540100014", scan.gtin14)
        assertEquals("00368540100014", scan.lookupCode)
        assertNull(scan.lot)
        assertNull(scan.expiry)
    }

    @Test
    fun `value that is not a barcode has no GTIN`() {
        val scan = decoder.decode("AB", Region.US)

        assertFalse(scan.isGs1)
        assertNull(scan.gtin14)
    }

    @Test
    fun `US pads an 8-digit code to GTIN-14`() {
        val scan = decoder.decode("00013285", Region.US)

        assertEquals("00000000013285", scan.gtin14)
        assertEquals("00000000013285", scan.lookupCode)
    }

    @Test
    fun `canada looks up an 8-digit DIN as scanned`() {
        val scan = decoder.decode("00013285", Region.CA)

        assertFalse(scan.isGs1)
        assertNull(scan.gtin14)
        assertEquals("00013285", scan.lookupCode)
    }

    @Test
    fun `canada does not pad a 12-digit UPC`() {
        val scan = decoder.decode("368540100014", Region.CA)

        assertNull(scan.gtin14)
        assertEquals("368540100014", scan.lookupCode)
    }

    @Test
    fun `canada keeps a 14-digit plain code as its GTIN`() {
        val scan = decoder.decode("00368540100014", Region.CA)

        assertEquals("00368540100014", scan.gtin14)
        assertEquals("00368540100014", scan.lookupCode)
    }

    @Test
    fun `canada still reads the GTIN, lot and expiry of a GS1 label`() {
        val scan = decoder.decode("]C1010036854010001417251231" + "10LOT123", Region.CA)

        assertTrue(scan.isGs1)
        assertEquals("00368540100014", scan.gtin14)
        assertEquals("00368540100014", scan.lookupCode)
        assertEquals("LOT123", scan.lot)
        assertEquals("12-31-2025", scan.expiry)
    }

    @Test
    fun `canada rejects plain codes that are too short or not all digits`() {
        assertNull(decoder.decode("1234567", Region.CA).lookupCode)
        assertNull(decoder.decode("LOT12345", Region.CA).lookupCode)
    }
}
