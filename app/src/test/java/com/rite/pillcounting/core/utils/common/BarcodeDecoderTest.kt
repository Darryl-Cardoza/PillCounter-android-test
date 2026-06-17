package com.rite.pillcounting.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Month

/**
 * Unit tests for [BarcodeDecoder].
 *
 * Covers GS1 barcode parsing, isGs1Barcode detection, and GTIN-14 padding.
 * android.util.Log calls inside BarcodeDecoder are silenced by isReturnDefaultValues = true.
 */
class BarcodeDecoderTest {

    private lateinit var decoder: BarcodeDecoder

    @Before
    fun setup() {
        decoder = BarcodeDecoder()
    }

    // -------------------------------------------------------------------------
    // decode() — GTIN extraction
    // GTIN regex: (?:\(01\)|01)(\d{13,14})
    // -------------------------------------------------------------------------

    // BARCODE_001
    @Test
    fun `decode extracts GTIN from parenthesised GS1 barcode`() {
        val barcode = "(01)00368540100014"
        val result = decoder.decode(barcode)
        assertEquals("00368540100014", result.gtin)
    }

    // BARCODE_002
    @Test
    fun `decode extracts GTIN from bare GS1 barcode (no parentheses)`() {
        // "01" + 14-digit GTIN = 16 chars before any other AI
        val barcode = "010036854010001417241231"
        val result = decoder.decode(barcode)
        assertNotNull(result.gtin)
        // regex \d{13,14} is greedy — captures 14 digits after "01"
        assertEquals("00368540100014", result.gtin)
    }

    // BARCODE_003
    @Test
    fun `decode returns BarcodeData with null gtin for non-GS1 barcode`() {
        val barcode = "1234567890123"  // plain EAN-13, no AI prefix
        val result = decoder.decode(barcode)
        assertNull(result.gtin)
    }

    // BARCODE_004
    @Test
    fun `decode returns BarcodeData with all fields null for empty string`() {
        val result = decoder.decode("")
        assertNull(result.gtin)
        assertNull(result.lotNumber)
        assertNull(result.expirationDate)
    }

    // BARCODE_005 — Expiration date extraction via AI 17
    @Test
    fun `decode extracts expiration date from parenthesised barcode`() {
        val barcode = "(01)00368540100014(17)241231"
        val result = decoder.decode(barcode)
        val expiry = result.expirationDate
        assertNotNull(expiry)
        assertEquals(2024, expiry!!.year)
        assertEquals(Month.DECEMBER, expiry.month)
        assertEquals(31, expiry.dayOfMonth)
    }

    // BARCODE_006 — Production date AI 11
    @Test
    fun `decode extracts production date from parenthesised barcode`() {
        val barcode = "(01)00368540100014(11)240101"
        val result = decoder.decode(barcode)
        val prod = result.productionDate
        assertNotNull(prod)
        assertEquals(2024, prod!!.year)
        assertEquals(Month.JANUARY, prod.month)
    }

    // BARCODE_007 — Sell-by date AI 15
    @Test
    fun `decode extracts sell-by date from parenthesised barcode`() {
        val barcode = "(01)00368540100014(15)250630"
        val result = decoder.decode(barcode)
        val sellBy = result.sellByDate
        assertNotNull(sellBy)
        assertEquals(2025, sellBy!!.year)
        assertEquals(Month.JUNE, sellBy.month)
    }

    // -------------------------------------------------------------------------
    // isGs1Barcode()
    // -------------------------------------------------------------------------

    // BARCODE_008 — symbology identifier prefix ]C1
    @Test
    fun `isGs1Barcode returns true for barcode with GS1 symbology identifier prefix`() {
        assertTrue(decoder.isGs1Barcode("]C1010036854010001417241231"))
    }

    // BARCODE_009 — GS1 separator character
    @Test
    fun `isGs1Barcode returns true for barcode containing GS1 separator character`() {
        assertTrue(decoder.isGs1Barcode("01003685401000141724123110LOT"))
    }

    // BARCODE_010 — parenthesised AI
    @Test
    fun `isGs1Barcode returns true for barcode with parenthesised application identifiers`() {
        assertTrue(decoder.isGs1Barcode("(01)00368540100014(17)241231"))
    }

    // BARCODE_011 — bare 01 + 14-digit GTIN (length >= 16)
    @Test
    fun `isGs1Barcode returns true for bare GS1 barcode starting with 01 and 14 digits`() {
        assertTrue(decoder.isGs1Barcode("010036854010001417241231"))
    }

    // BARCODE_012 — plain barcode, no GS1 markers
    @Test
    fun `isGs1Barcode returns false for plain non-GS1 barcode`() {
        assertFalse(decoder.isGs1Barcode("1234567890123"))
    }

    // BARCODE_013
    @Test
    fun `isGs1Barcode returns false for empty string`() {
        assertFalse(decoder.isGs1Barcode(""))
    }

    // BARCODE_014 — starts with "01" but shorter than 16 chars
    @Test
    fun `isGs1Barcode returns false for barcode starting with 01 but too short`() {
        assertFalse(decoder.isGs1Barcode("01123456"))
    }

    // -------------------------------------------------------------------------
    // toGtin14()
    // -------------------------------------------------------------------------

    // BARCODE_015
    @Test
    fun `toGtin14 returns unchanged string for already 14-digit GTIN`() {
        assertEquals("00368540100014", decoder.toGtin14("00368540100014"))
    }

    // BARCODE_016
    @Test
    fun `toGtin14 pads 13-digit GTIN to 14 digits`() {
        val result = decoder.toGtin14("0368540100014")
        assertNotNull(result)
        assertEquals(14, result!!.length)
        assertEquals("00368540100014", result)
    }

    // BARCODE_017
    @Test
    fun `toGtin14 pads 12-digit GTIN to 14 digits`() {
        val result = decoder.toGtin14("036854010001")
        assertNotNull(result)
        assertEquals(14, result!!.length)
    }

    // BARCODE_018
    @Test
    fun `toGtin14 pads 8-digit GTIN to 14 digits`() {
        val result = decoder.toGtin14("36854010")
        assertNotNull(result)
        assertEquals(14, result!!.length)
    }

    // BARCODE_019 — invalid length returns null
    @Test
    fun `toGtin14 returns null for 7-digit input (invalid length)`() {
        assertNull(decoder.toGtin14("1234567"))
    }

    // BARCODE_020
    @Test
    fun `toGtin14 returns null for null input`() {
        assertNull(decoder.toGtin14(null))
    }

    // BARCODE_021
    @Test
    fun `toGtin14 returns null for blank input`() {
        assertNull(decoder.toGtin14(""))
    }

    // BARCODE_022 — strips non-digit characters before counting length
    @Test
    fun `toGtin14 strips non-digit chars and pads to 14 digits`() {
        // "036854-010001" → stripped "036854010001" (12 digits) → pads to 14
        val result = decoder.toGtin14("036854-010001")
        assertNotNull(result)
        assertEquals(14, result!!.length)
    }
}
