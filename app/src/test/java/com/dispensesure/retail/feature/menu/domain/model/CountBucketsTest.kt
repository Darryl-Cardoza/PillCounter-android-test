package com.dispensesure.retail.feature.menu.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountBucketsTest {

    @Test
    fun getters_returnValues() {
        val buckets = CountBuckets(dispenseCompleted = 1, dispensePartial = 2)
        assertEquals(1, buckets.dispenseCompleted)
        assertEquals(2, buckets.dispensePartial)
    }

    @Test
    fun defaultValues_areZero() {
        val buckets = CountBuckets()
        assertEquals(0, buckets.dispenseCompleted)
        assertEquals(0, buckets.dispensePartial)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = CountBuckets(1, 2)
        val b = CountBuckets(1, 2)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(CountBuckets(1, 2), CountBuckets(2, 1))
    }

    @Test
    fun toString_containsValues() {
        assertTrue(CountBuckets(1, 2).toString().contains("dispenseCompleted=1"))
    }

    @Test
    fun copy_overridesValues() {
        val a = CountBuckets(1, 2)
        val b = a.copy(dispensePartial = 20)
        assertEquals(1, b.dispenseCompleted)
        assertEquals(20, b.dispensePartial)
    }

    @Test
    fun componentN_returnValues() {
        val a = CountBuckets(1, 2)
        assertEquals(1, a.component1())
        assertEquals(2, a.component2())
    }
}
