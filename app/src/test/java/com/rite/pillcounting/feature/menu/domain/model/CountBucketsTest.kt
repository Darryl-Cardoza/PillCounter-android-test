package com.rite.pillcounting.feature.menu.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountBucketsTest {

    @Test
    fun getters_returnValues() {
        val buckets = CountBuckets(
            fixedCompleted = 1,
            fixedPartial = 2,
            regularCompleted = 3,
            regularPartial = 4
        )
        assertEquals(1, buckets.fixedCompleted)
        assertEquals(2, buckets.fixedPartial)
        assertEquals(3, buckets.regularCompleted)
        assertEquals(4, buckets.regularPartial)
    }

    @Test
    fun defaultValues_areZero() {
        val buckets = CountBuckets()
        assertEquals(0, buckets.fixedCompleted)
        assertEquals(0, buckets.fixedPartial)
        assertEquals(0, buckets.regularCompleted)
        assertEquals(0, buckets.regularPartial)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = CountBuckets(1, 2, 3, 4)
        val b = CountBuckets(1, 2, 3, 4)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(CountBuckets(1, 2, 3, 4), CountBuckets(4, 3, 2, 1))
    }

    @Test
    fun toString_containsValues() {
        assertTrue(CountBuckets(1, 2, 3, 4).toString().contains("fixedCompleted=1"))
    }

    @Test
    fun copy_overridesValues() {
        val a = CountBuckets(1, 2, 3, 4)
        val b = a.copy(fixedPartial = 20)
        assertEquals(1, b.fixedCompleted)
        assertEquals(20, b.fixedPartial)
    }

    @Test
    fun componentN_returnValues() {
        val a = CountBuckets(1, 2, 3, 4)
        assertEquals(1, a.component1())
        assertEquals(2, a.component2())
        assertEquals(3, a.component3())
        assertEquals(4, a.component4())
    }
}
