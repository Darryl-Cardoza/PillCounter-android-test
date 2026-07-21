package com.rite.pillcounting.core.scanning.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottleInfoTest {

    @Test
    fun `default constructor produces null GS1 fields and empty txnDetailsIds`() {
        val before = System.currentTimeMillis()
        val bottle = BottleInfo()
        val after = System.currentTimeMillis()

        assertEquals(null, bottle.lotNumber)
        assertEquals(null, bottle.expirationDate)
        assertEquals(null, bottle.serialNumber)
        assertEquals(emptyList<Long>(), bottle.txnDetailsIds)
        assertTrue(bottle.scannedAt in before..after)
    }

    @Test
    fun `all fields can be set explicitly`() {
        val bottle = BottleInfo(
            lotNumber = "LOT123",
            expirationDate = "12-31-2026",
            serialNumber = "SN456",
            txnDetailsIds = listOf(1L, 2L, 3L),
            scannedAt = 1234567890L,
        )

        assertEquals("LOT123", bottle.lotNumber)
        assertEquals("12-31-2026", bottle.expirationDate)
        assertEquals("SN456", bottle.serialNumber)
        assertEquals(listOf(1L, 2L, 3L), bottle.txnDetailsIds)
        assertEquals(1234567890L, bottle.scannedAt)
    }

    @Test
    fun `equals and hashCode are structural (data class contract)`() {
        val a = BottleInfo("L1", "01-01-2027", "S1", listOf(1L), 100L)
        val b = BottleInfo("L1", "01-01-2027", "S1", listOf(1L), 100L)
        val c = BottleInfo("L2", "01-01-2027", "S1", listOf(1L), 100L)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    @Test
    fun `copy overrides only specified fields`() {
        val original = BottleInfo("L1", "01-01-2027", "S1", listOf(1L, 2L), 100L)
        val copy = original.copy(serialNumber = "S2")

        assertEquals("L1", copy.lotNumber)
        assertEquals("01-01-2027", copy.expirationDate)
        assertEquals("S2", copy.serialNumber)
        assertEquals(listOf(1L, 2L), copy.txnDetailsIds)
        assertEquals(100L, copy.scannedAt)
    }

    // ─────────────────────────── BottleInfoJson.encode ───────────────────────────

    @Test
    fun `encode of empty list produces empty json array`() {
        val json = BottleInfoJson.encode(emptyList())
        assertEquals("[]", json)
    }

    @Test
    fun `encode then decode round-trips a single bottle`() {
        val bottle = BottleInfo("L1", "01-01-2027", "S1", listOf(10L, 20L), 555L)
        val json = BottleInfoJson.encode(listOf(bottle))
        val decoded = BottleInfoJson.decode(json)

        assertEquals(listOf(bottle), decoded)
    }

    @Test
    fun `encode then decode round-trips multiple bottles preserving order`() {
        val bottles = listOf(
            BottleInfo("L1", null, null, emptyList(), 1L),
            BottleInfo(null, "02-02-2028", "S2", listOf(5L), 2L),
        )
        val json = BottleInfoJson.encode(bottles)
        val decoded = BottleInfoJson.decode(json)

        assertEquals(bottles, decoded)
    }

    @Test
    fun `encode then decode round-trips bottle with null GS1 fields`() {
        val bottle = BottleInfo(lotNumber = null, expirationDate = null, serialNumber = null, txnDetailsIds = listOf(7L), scannedAt = 42L)
        val decoded = BottleInfoJson.decode(BottleInfoJson.encode(listOf(bottle)))

        assertEquals(listOf(bottle), decoded)
    }

    // ─────────────────────────── BottleInfoJson.decode ───────────────────────────

    @Test
    fun `decode of null json returns empty list`() {
        assertEquals(emptyList<BottleInfo>(), BottleInfoJson.decode(null))
    }

    @Test
    fun `decode of blank json returns empty list`() {
        assertEquals(emptyList<BottleInfo>(), BottleInfoJson.decode(""))
        assertEquals(emptyList<BottleInfo>(), BottleInfoJson.decode("   "))
    }

    @Test
    fun `decode of empty array json returns empty list`() {
        assertEquals(emptyList<BottleInfo>(), BottleInfoJson.decode("[]"))
    }

    @Test
    fun `decode of malformed json returns empty list instead of throwing`() {
        assertEquals(emptyList<BottleInfo>(), BottleInfoJson.decode("not-json"))
        assertEquals(emptyList<BottleInfo>(), BottleInfoJson.decode("{invalid"))
    }

    @Test
    fun `decode of json object instead of array returns empty list`() {
        // A single object (not wrapped in an array) doesn't match Array<BottleInfo>, so Gson throws
        // internally and BottleInfoJson.decode's runCatching should fall back to emptyList.
        val json = """{"lotNumber":"L1"}"""
        assertEquals(emptyList<BottleInfo>(), BottleInfoJson.decode(json))
    }

    @Test
    fun `decode tolerates missing fields, nulling absent reference fields`() {
        // Gson leaves absent reference-typed fields null regardless of the data class's declared
        // defaults, since it doesn't invoke the constructor for present-but-partial JSON objects.
        // scannedAt is a primitive Long, so Gson still runs its declared default (System.currentTimeMillis())
        // rather than leaving it unset — verified by asserting it's within a tight window of "now".
        val before = System.currentTimeMillis()
        val json = """[{"lotNumber":"L1"}]"""
        val decoded = BottleInfoJson.decode(json)
        val after = System.currentTimeMillis()

        assertEquals(1, decoded.size)
        assertEquals("L1", decoded[0].lotNumber)
        assertEquals(null, decoded[0].expirationDate)
        assertEquals(null, decoded[0].serialNumber)
        assertTrue(decoded[0].scannedAt in before..after)
    }
}
