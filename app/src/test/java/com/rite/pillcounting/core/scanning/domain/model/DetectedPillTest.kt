package com.rite.pillcounting.core.scanning.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedPillTest {

    private fun pill() = DetectedPill(x = 1.5f, y = 2.5f, confidence = 0.9f)

    @Test
    fun getters_returnValues() {
        val p = pill()
        assertEquals(1.5f, p.x)
        assertEquals(2.5f, p.y)
        assertEquals(0.9f, p.confidence)
    }

    @Test
    fun equalsHashCode_equal() {
        assertEquals(pill(), pill())
        assertEquals(pill().hashCode(), pill().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(pill(), pill().copy(x = 9f))
    }

    @Test
    fun toString_containsField() {
        assertTrue(pill().toString().contains("0.9"))
    }

    @Test
    fun copy_overrides() {
        assertEquals(5f, pill().copy(y = 5f).y)
    }

    @Test
    fun componentN_returnValues() {
        val p = pill()
        assertEquals(1.5f, p.component1())
        assertEquals(2.5f, p.component2())
        assertEquals(0.9f, p.component3())
    }
}
