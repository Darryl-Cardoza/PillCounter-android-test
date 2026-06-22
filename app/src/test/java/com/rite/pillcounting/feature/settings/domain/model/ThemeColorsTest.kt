package com.rite.pillcounting.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorsTest {

    private fun sample() = ThemeColors(
        primary = "#111111",
        secondary = "#222222",
        tertiary = "#333333",
        primaryBackground = "#444444",
        secondaryBackground = "#555555",
        textColor = "#666666",
        inputBackground = "#777777",
        statusChipBackgroundOnPrimary = "#888888",
        statusChipBackgroundOnSecondary = "#999999"
    )

    @Test
    fun propertyGetters() {
        val c = sample()
        assertEquals("#111111", c.primary)
        assertEquals("#222222", c.secondary)
        assertEquals("#333333", c.tertiary)
        assertEquals("#444444", c.primaryBackground)
        assertEquals("#555555", c.secondaryBackground)
        assertEquals("#666666", c.textColor)
        assertEquals("#777777", c.inputBackground)
        assertEquals("#888888", c.statusChipBackgroundOnPrimary)
        assertEquals("#999999", c.statusChipBackgroundOnSecondary)
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
        val b = sample().copy(primary = "#000000")
        assertNotEquals(a, b)
        assertNotEquals(a, null)
        assertNotEquals(a, "string")
    }

    @Test
    fun toStringContainsValues() {
        val s = sample().toString()
        assertTrue(s.contains("#111111"))
        assertTrue(s.contains("#999999"))
    }

    @Test
    fun copyWorks() {
        val c = sample()
        val copy = c.copy(textColor = "#ABCDEF")
        assertEquals("#ABCDEF", copy.textColor)
        assertEquals("#111111", copy.primary)
        assertEquals(c, c.copy())
    }

    @Test
    fun componentN() {
        val c = sample()
        assertEquals("#111111", c.component1())
        assertEquals("#222222", c.component2())
        assertEquals("#333333", c.component3())
        assertEquals("#444444", c.component4())
        assertEquals("#555555", c.component5())
        assertEquals("#666666", c.component6())
        assertEquals("#777777", c.component7())
        assertEquals("#888888", c.component8())
        assertEquals("#999999", c.component9())
    }
}
