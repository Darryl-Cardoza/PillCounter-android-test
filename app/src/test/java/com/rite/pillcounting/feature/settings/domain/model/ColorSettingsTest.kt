package com.rite.pillcounting.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSettingsTest {

    private fun theme(seed: String) = ThemeColors(
        primary = "${seed}1",
        secondary = "${seed}2",
        tertiary = "${seed}3",
        primaryBackground = "${seed}4",
        secondaryBackground = "${seed}5",
        textColor = "${seed}6",
        inputBackground = "${seed}7",
        statusChipBackgroundOnPrimary = "${seed}8",
        statusChipBackgroundOnSecondary = "${seed}9"
    )

    private fun sample() = ColorSettings(light = theme("L"), dark = theme("D"))

    @Test
    fun propertyGetters() {
        val c = sample()
        assertEquals(theme("L"), c.light)
        assertEquals(theme("D"), c.dark)
    }

    @Test
    fun equalsAndHashCode_equal() {
        val a = sample()
        val b = sample()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a)
    }

    @Test
    fun equalsAndHashCode_notEqual() {
        val a = sample()
        val b = sample().copy(light = theme("X"))
        assertNotEquals(a, b)
        assertNotEquals(a, null)
        assertNotEquals(a, "string")
    }

    @Test
    fun toStringContainsValues() {
        val s = sample().toString()
        assertTrue(s.contains("L1"))
        assertTrue(s.contains("D1"))
    }

    @Test
    fun copyWorks() {
        val c = sample()
        val newDark = theme("Z")
        val copy = c.copy(dark = newDark)
        assertEquals(newDark, copy.dark)
        assertEquals(theme("L"), copy.light)
        assertEquals(c, c.copy())
    }

    @Test
    fun componentN() {
        val c = sample()
        assertEquals(theme("L"), c.component1())
        assertEquals(theme("D"), c.component2())
    }
}
