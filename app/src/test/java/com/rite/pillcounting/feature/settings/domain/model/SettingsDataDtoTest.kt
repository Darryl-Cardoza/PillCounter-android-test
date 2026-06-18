package com.rite.pillcounting.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataDtoTest {

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

    private fun settings() = ApplicationSettingsResponse(
        colors = ColorSettings(light = theme("L"), dark = theme("D")),
        appLogo = "app_logo.png",
        placeholderLogo = "placeholder.png"
    )

    private fun hl7() = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")

    private fun sample(hl7Config: ApplicationSettingsHL7Config? = hl7()) = SettingsDataDto(
        minVersion = "1.0.0",
        isMaintenanceMode = true,
        settings = settings(),
        hl7Config = hl7Config
    )

    @Test
    fun propertyGetters_nonNullHl7() {
        val dto = sample()
        assertEquals("1.0.0", dto.minVersion)
        assertTrue(dto.isMaintenanceMode)
        assertEquals(settings(), dto.settings)
        assertEquals(hl7(), dto.hl7Config)
    }

    @Test
    fun propertyGetters_nullHl7AndNullMinVersion() {
        val dto = SettingsDataDto(
            minVersion = null,
            isMaintenanceMode = false,
            settings = settings(),
            hl7Config = null
        )
        assertNull(dto.minVersion)
        assertEquals(false, dto.isMaintenanceMode)
        assertEquals(settings(), dto.settings)
        assertNull(dto.hl7Config)
    }

    @Test
    fun equalsAndHashCode_equal() {
        val a = sample()
        val b = sample()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a)

        val c = sample(hl7Config = null)
        val d = sample(hl7Config = null)
        assertEquals(c, d)
        assertEquals(c.hashCode(), d.hashCode())
    }

    @Test
    fun equalsAndHashCode_notEqual() {
        val a = sample()
        assertNotEquals(a, sample(hl7Config = null))
        assertNotEquals(a, a.copy(minVersion = "2.0.0"))
        assertNotEquals(a, null)
        assertNotEquals(a, "string")
    }

    @Test
    fun toStringContainsValues() {
        val s = sample().toString()
        assertTrue(s.contains("1.0.0"))
        assertTrue(s.contains("CODE_128"))

        val sNull = sample(hl7Config = null).toString()
        assertTrue(sNull.contains("null"))
    }

    @Test
    fun copyWorks() {
        val dto = sample()
        val copy = dto.copy(isMaintenanceMode = false, hl7Config = null)
        assertEquals(false, copy.isMaintenanceMode)
        assertNull(copy.hl7Config)
        assertEquals("1.0.0", copy.minVersion)
        assertEquals(dto, dto.copy())
    }

    @Test
    fun componentN() {
        val dto = sample()
        assertEquals("1.0.0", dto.component1())
        assertEquals(true, dto.component2())
        assertEquals(settings(), dto.component3())
        assertEquals(hl7(), dto.component4())
    }
}
