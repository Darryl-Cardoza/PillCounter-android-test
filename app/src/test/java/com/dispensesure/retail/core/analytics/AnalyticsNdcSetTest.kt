package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.store.AnalyticsNdcSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticsNdcSetTest {

    @Test
    fun `encodes sorted and de-duplicated`() {
        assertEquals("""["a","b","c"]""", AnalyticsNdcSet.encodeSorted(listOf("c", "a", "b", "a")))
    }

    /** A retried row must produce a byte-identical body, so input order cannot leak through. */
    @Test
    fun `encoding is stable regardless of insertion order`() {
        val one = AnalyticsNdcSet.encodeSorted(listOf("z", "m", "a"))
        val two = AnalyticsNdcSet.encodeSorted(listOf("a", "z", "m"))
        assertEquals(one, two)
    }

    @Test
    fun `blank and empty values are dropped`() {
        assertNull(AnalyticsNdcSet.encodeSorted(listOf("", "   ")))
        assertEquals("""["a"]""", AnalyticsNdcSet.encodeSorted(listOf("a", "", "  ")))
    }

    @Test
    fun `empty input encodes to null rather than an empty array`() {
        assertNull(AnalyticsNdcSet.encodeSorted(emptyList()))
    }

    @Test
    fun `values are trimmed`() {
        assertEquals("""["a"]""", AnalyticsNdcSet.encodeSorted(listOf("  a  ")))
    }

    @Test
    fun `decode round-trips`() {
        val json = AnalyticsNdcSet.encodeSorted(listOf("b", "a"))
        assertEquals(listOf("a", "b"), AnalyticsNdcSet.decode(json))
    }

    /** A corrupt cell must not take down an upload. */
    @Test
    fun `decode tolerates null, blank and malformed input`() {
        assertEquals(emptyList<String>(), AnalyticsNdcSet.decode(null))
        assertEquals(emptyList<String>(), AnalyticsNdcSet.decode(""))
        assertEquals(emptyList<String>(), AnalyticsNdcSet.decode("not json"))
    }

    @Test
    fun `insert adds a new value`() {
        val json = AnalyticsNdcSet.insert(null, "a")
        assertEquals(listOf("a"), AnalyticsNdcSet.decode(json))
    }

    @Test
    fun `insert of an existing value leaves the json untouched`() {
        val json = AnalyticsNdcSet.encodeSorted(listOf("a", "b"))
        assertEquals(json, AnalyticsNdcSet.insert(json, "a"))
    }

    @Test
    fun `insert of a blank value leaves the json untouched`() {
        val json = AnalyticsNdcSet.encodeSorted(listOf("a"))
        assertEquals(json, AnalyticsNdcSet.insert(json, "   "))
        assertEquals(json, AnalyticsNdcSet.insert(json, null))
    }

    @Test
    fun `insert keeps the set sorted`() {
        var json = AnalyticsNdcSet.insert(null, "c")
        json = AnalyticsNdcSet.insert(json, "a")
        json = AnalyticsNdcSet.insert(json, "b")
        assertEquals(listOf("a", "b", "c"), AnalyticsNdcSet.decode(json))
    }

    @Test
    fun `size counts distinct codes`() {
        assertEquals(0, AnalyticsNdcSet.size(null))
        assertEquals(2, AnalyticsNdcSet.size(AnalyticsNdcSet.encodeSorted(listOf("a", "b", "a"))))
    }
}
