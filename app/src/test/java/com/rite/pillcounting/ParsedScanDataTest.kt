package com.rite.pillcounting

import ParsedScanData
import org.junit.Assert
import org.junit.Test
import parseScanData

/**
 * Unit tests for the root-package [parseScanData] function and [ParsedScanData] data class.
 *
 * NOTE: ParsedScanData.kt declares no package, so these symbols live in the default
 * (root) package and this test must also live in the root package.
 *
 * android.util.Log calls inside the logger are silenced by isReturnDefaultValues = true.
 */
class ParsedScanDataTest {

    @Test
    fun parseScanData_mapsAllKnownKeys() {
        val template = "{RxNo}|{NdcNo}|{Qty}|{Bucket}"
        val raw = "RX123|00123456789|30|A1"
        val result = parseScanData(template, raw)

        Assert.assertEquals("RX123", result.rxNo)
        Assert.assertEquals("00123456789", result.ndcNo)
        Assert.assertEquals("30", result.qty)
        Assert.assertEquals("A1", result.bucket)
        Assert.assertEquals(4, result.rawMap.size)
        Assert.assertEquals("RX123", result.rawMap["RXNO"])
    }

    @Test
    fun parseScanData_keysAreUppercasedAndTrimmed() {
        val template = "{ rxno }|{ qty }"
        val raw = " RX9 | 12 "
        val result = parseScanData(template, raw)

        Assert.assertEquals("RX9", result.rxNo)
        Assert.assertEquals("12", result.qty)
    }

    @Test
    fun parseScanData_missingTrailingValues_areOmitted() {
        val template = "{RxNo}|{NdcNo}|{Qty}"
        val raw = "RX123" // only one value, two keys missing
        val result = parseScanData(template, raw)

        Assert.assertEquals("RX123", result.rxNo)
        Assert.assertNull(result.ndcNo)
        Assert.assertNull(result.qty)
        Assert.assertEquals(1, result.rawMap.size)
    }

    @Test
    fun parseScanData_emptyTemplate_returnsDefault() {
        val result = parseScanData("", "RX|10")
        Assert.assertNull(result.rxNo)
        Assert.assertNull(result.ndcNo)
        Assert.assertTrue(result.rawMap.isEmpty())
    }

    @Test
    fun parseScanData_emptyActualValue_returnsDefaultBecauseNoKeysWhenTemplateAlsoEmpty() {
        // values come from split('|'); "" splits to a single empty-string element,
        // so keys-empty guard triggers the default when template has no placeholders.
        val result = parseScanData("", "")
        Assert.assertNull(result.rxNo)
        Assert.assertTrue(result.rawMap.isEmpty())
    }

    @Test
    fun parseScanData_unknownKeysGoOnlyIntoRawMap() {
        val template = "{Foo}|{Bar}"
        val raw = "abc|def"
        val result = parseScanData(template, raw)

        Assert.assertNull(result.rxNo)
        Assert.assertNull(result.ndcNo)
        Assert.assertEquals("abc", result.rawMap["FOO"])
        Assert.assertEquals("def", result.rawMap["BAR"])
    }

    // ───────────────────────────── data class ─────────────────────────────

    @Test
    fun parsedScanData_defaults_areNullAndEmptyMap() {
        val d = ParsedScanData()
        Assert.assertNull(d.rxNo)
        Assert.assertNull(d.ndcNo)
        Assert.assertNull(d.qty)
        Assert.assertNull(d.bucket)
        Assert.assertTrue(d.rawMap.isEmpty())
    }

    @Test
    fun parsedScanData_equalsHashCodeCopyToString() {
        val a = ParsedScanData(rxNo = "R", ndcNo = "N", qty = "1", bucket = "B", rawMap = mapOf("X" to "Y"))
        val b = a.copy()
        Assert.assertEquals(a, b)
        Assert.assertEquals(a.hashCode(), b.hashCode())
        Assert.assertTrue(a.toString().contains("rxNo"))

        val c = a.copy(qty = "2")
        Assert.assertTrue(a != c)
        Assert.assertEquals("2", c.qty)

        // component functions
        Assert.assertEquals("R", a.component1())
        Assert.assertEquals("N", a.component2())
        Assert.assertEquals("1", a.component3())
        Assert.assertEquals("B", a.component4())
        Assert.assertEquals(mapOf("X" to "Y"), a.component5())
    }
}