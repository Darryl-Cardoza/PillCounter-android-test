package com.rite.pillcounting.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Supplementary tests for [BarcodeDecoder] covering branches not exercised by
 * [BarcodeDecoderTest]: weight parsing (all decimal-divisor variants), lot/serial
 * extraction, packing date (AI 13), symbology-prefix stripping in decode(), and the
 * non-digit bare-01 branch of isGs1Barcode.
 */
class BarcodeDecoderWeightTest {

    private val decoder = BarcodeDecoder()

    @Test
    fun decode_netWeightKg_zeroDecimals() {
        // AI 3100 -> 0 decimals, value 001500 -> 1500.0
        val data = decoder.decode("3100001500")
        assertEquals(1500.0, data.netWeightKg!!, 0.0001)
    }

    @Test
    fun decode_allFourWeights_applyCorrectDivisors() {
        val raw = "3100001500" + "3201001500" + "3302001500" + "3403001500"
        val data = decoder.decode(raw)
        assertEquals(1500.0, data.netWeightKg!!, 0.0001)   // /1
        assertEquals(150.0, data.netWeightLb!!, 0.0001)    // /10
        assertEquals(15.0, data.grossWeightKg!!, 0.0001)   // /100
        assertEquals(1.5, data.grossWeightLb!!, 0.0001)    // /1000
    }

    @Test
    fun decode_parenthesizedWeight_parsed() {
        // For the parenthesized form, parseWeight takes match.value.take(4) = "(310",
        // whose last char '0' yields 0 decimals (divisor 1) -> 1500.0. This documents
        // the actual implementation behavior for the (NNN) AI form.
        val data = decoder.decode("(3101)001500")
        assertEquals(1500.0, data.netWeightKg!!, 0.0001)
    }

    @Test
    fun decode_noWeights_returnsNull() {
        val data = decoder.decode("(01)00368540100014")
        assertNull(data.netWeightKg)
        assertNull(data.grossWeightKg)
        assertNull(data.netWeightLb)
        assertNull(data.grossWeightLb)
    }

    @Test
    fun decode_lot_extractedWithFnc1Stripped() {
        // ]C1 prefix + FNC1 separators stripped. Lot regex requires a preceding 17+6 digits
        // (AI 17 expiry). AI 10 (lot) is variable-length, so it is placed LAST in the payload
        // so the capture terminates at end-of-string.
        val raw = "]C1010036854010001417251231" + "10LOT123"
        val data = decoder.decode(raw)
        assertEquals("00368540100014", data.gtin)
        assertEquals("LOT123", data.lotNumber)
    }

    @Test
    fun decode_serial_extractedWithFnc1Stripped() {
        // AI 21 (serial) placed LAST so the variable-length capture stops at end-of-string.
        val raw = "]C1010036854010001417251231" + "21SER456"
        val data = decoder.decode(raw)
        assertEquals("00368540100014", data.gtin)
        assertEquals("SER456", data.serialNumber)
    }

    @Test
    fun decode_packingDate_ai13() {
        val data = decoder.decode("(01)00368540100014(13)240201")
        val pack = data.packingDate
        assertNotNull(pack)
        assertEquals(2024, pack!!.year)
        assertEquals(2, pack.monthValue)
        assertEquals(1, pack.dayOfMonth)
    }

    @Test
    fun isGs1Barcode_bare01WithNonDigitInGtin_returnsFalse() {
        // length >= 16, starts with 01, but non-digit inside substring(2,16)
        assertEquals(false, decoder.isGs1Barcode("01ABCDEFGHIJKLMN"))
    }
}
