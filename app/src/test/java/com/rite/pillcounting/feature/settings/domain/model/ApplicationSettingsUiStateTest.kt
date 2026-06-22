package com.rite.pillcounting.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationSettingsUiStateTest {

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

    private fun appSettings() = SettingsDataDto(
        minVersion = "1.0.0",
        isMaintenanceMode = true,
        settings = ApplicationSettingsResponse(
            colors = colors(),
            appLogo = "app_logo.png",
            placeholderLogo = "placeholder.png"
        ),
        hl7Config = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")
    )

    private fun fullSample() = ApplicationSettingsUiState(
        isLoading = true,
        errorMessage = "error",
        colorSettings = colors(),
        appLogoUrl = "logo.png",
        appSettings = appSettings(),
        isMaintenanceMode = true,
        isUpdateRequired = true,
        isHl7Enabled = true,
        nsdBroadcastType = "broadcast",
        nsdDiscoveryType = "discovery"
    )

    @Test
    fun defaultValues() {
        val state = ApplicationSettingsUiState()
        assertEquals(false, state.isLoading)
        assertNull(state.errorMessage)
        assertNull(state.colorSettings)
        assertNull(state.appLogoUrl)
        assertNull(state.appSettings)
        assertEquals(false, state.isMaintenanceMode)
        assertEquals(false, state.isUpdateRequired)
        assertNull(state.isHl7Enabled)
        assertNull(state.nsdBroadcastType)
        assertNull(state.nsdDiscoveryType)
    }

    @Test
    fun propertyGetters() {
        val state = fullSample()
        assertEquals(true, state.isLoading)
        assertEquals("error", state.errorMessage)
        assertEquals(colors(), state.colorSettings)
        assertEquals("logo.png", state.appLogoUrl)
        assertEquals(appSettings(), state.appSettings)
        assertEquals(true, state.isMaintenanceMode)
        assertEquals(true, state.isUpdateRequired)
        assertEquals(true, state.isHl7Enabled)
        assertEquals("broadcast", state.nsdBroadcastType)
        assertEquals("discovery", state.nsdDiscoveryType)
    }

    @Test
    fun equalsAndHashCode_equal() {
        val a = fullSample()
        val b = fullSample()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a)

        val d1 = ApplicationSettingsUiState()
        val d2 = ApplicationSettingsUiState()
        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
    }

    @Test
    fun equalsAndHashCode_notEqual() {
        val a = fullSample()
        assertNotEquals(a, a.copy(errorMessage = "other"))
        assertNotEquals(a, null)
        assertNotEquals(a, "string")
    }

    @Test
    fun toStringContainsValues() {
        val s = fullSample().toString()
        assertTrue(s.contains("error"))
        assertTrue(s.contains("logo.png"))
        assertTrue(s.contains("broadcast"))
        assertTrue(s.contains("discovery"))
    }

    @Test
    fun copyWorks() {
        val state = fullSample()
        val copy = state.copy(isLoading = false, errorMessage = null)
        assertEquals(false, copy.isLoading)
        assertNull(copy.errorMessage)
        assertEquals("logo.png", copy.appLogoUrl)
        assertEquals(state, state.copy())
    }

    @Test
    fun componentN() {
        val state = fullSample()
        assertEquals(true, state.component1())
        assertEquals("error", state.component2())
        assertEquals(colors(), state.component3())
        assertEquals("logo.png", state.component4())
        assertEquals(appSettings(), state.component5())
        assertEquals(true, state.component6())
        assertEquals(true, state.component7())
        assertEquals(true, state.component8())
        assertEquals("broadcast", state.component9())
        assertEquals("discovery", state.component10())
    }
}
