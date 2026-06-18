package com.rite.pillcounting.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationSettingsHL7ConfigTest {

    @Test
    fun propertyGetter() {
        val config = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")
        assertEquals("CODE_128", config.barcodeFormat)
    }

    @Test
    fun equalsAndHashCode_equal() {
        val a = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")
        val b = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a)
    }

    @Test
    fun equalsAndHashCode_notEqual() {
        val a = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")
        val b = ApplicationSettingsHL7Config(barcodeFormat = "QR_CODE")
        assertNotEquals(a, b)
        assertNotEquals(a, null)
        assertNotEquals(a, "string")
    }

    @Test
    fun toStringContainsValue() {
        val config = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")
        assertTrue(config.toString().contains("CODE_128"))
    }

    @Test
    fun copyWorks() {
        val config = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")
        val copy = config.copy(barcodeFormat = "QR_CODE")
        assertEquals("QR_CODE", copy.barcodeFormat)
        assertEquals(config, config.copy())
    }

    @Test
    fun componentN() {
        val config = ApplicationSettingsHL7Config(barcodeFormat = "CODE_128")
        assertEquals("CODE_128", config.component1())
    }
}
