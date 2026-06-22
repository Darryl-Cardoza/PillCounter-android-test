package com.rite.pillcounting.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationSettingsResponseTest {

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

    private fun colors() = ColorSettings(light = theme("L"), dark = theme("D"))

    private fun sample() = ApplicationSettingsResponse(
        colors = colors(),
        appLogo = "app_logo.png",
        placeholderLogo = "placeholder.png"
    )

    @Test
    fun propertyGetters() {
        val r = sample()
        assertEquals(colors(), r.colors)
        assertEquals("app_logo.png", r.appLogo)
        assertEquals("placeholder.png", r.placeholderLogo)
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
        val b = sample().copy(appLogo = "other.png")
        assertNotEquals(a, b)
        assertNotEquals(a, null)
        assertNotEquals(a, "string")
    }

    @Test
    fun toStringContainsValues() {
        val s = sample().toString()
        assertTrue(s.contains("app_logo.png"))
        assertTrue(s.contains("placeholder.png"))
    }

    @Test
    fun copyWorks() {
        val r = sample()
        val copy = r.copy(placeholderLogo = "new.png")
        assertEquals("new.png", copy.placeholderLogo)
        assertEquals("app_logo.png", copy.appLogo)
        assertEquals(r, r.copy())
    }

    @Test
    fun componentN() {
        val r = sample()
        assertEquals(colors(), r.component1())
        assertEquals("app_logo.png", r.component2())
        assertEquals("placeholder.png", r.component3())
    }
}
