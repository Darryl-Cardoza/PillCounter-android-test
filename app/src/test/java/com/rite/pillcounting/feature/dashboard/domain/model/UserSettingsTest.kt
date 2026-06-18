package com.rite.pillcounting.feature.dashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsTest {

    private fun sample() = UserSettings(
        notificationsEnabled = true,
        language = "en",
        timezone = "UTC",
    )

    @Test
    fun getters_returnConstructorValues() {
        val s = sample()
        assertEquals(true, s.notificationsEnabled)
        assertEquals("en", s.language)
        assertEquals("UTC", s.timezone)
    }

    @Test
    fun defaultValues_areNull() {
        val s = UserSettings()
        assertNull(s.notificationsEnabled)
        assertNull(s.language)
        assertNull(s.timezone)
    }

    @Test
    fun equalsAndHashCode() {
        val a = sample()
        val b = sample()
        val c = a.copy(language = "fr")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun toString_containsClassName() {
        assertTrue(sample().toString().contains("UserSettings"))
    }

    @Test
    fun copy_overridesField() {
        val copy = sample().copy(notificationsEnabled = false)
        assertEquals(false, copy.notificationsEnabled)
        assertEquals("en", copy.language)
    }

    @Test
    fun componentFunctions() {
        val s = sample()
        assertEquals(true, s.component1())
        assertEquals("en", s.component2())
        assertEquals("UTC", s.component3())
    }
}
