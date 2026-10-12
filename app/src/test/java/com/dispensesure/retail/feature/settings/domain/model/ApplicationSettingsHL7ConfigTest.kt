package com.dispensesure.retail.feature.settings.domain.model

import com.dispensesure.retail.core.di.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationSettingsHL7ConfigTest {

    @Test
    fun propertyGetter() {
        val config = ApplicationSettingsHL7Config(pmsHostName = "pms", pillCounterHostName = "counter")
        assertEquals("pms", config.pmsHostName)
        assertEquals("counter", config.pillCounterHostName)
    }

    @Test
    fun equalsAndHashCode_equal() {
        val a = ApplicationSettingsHL7Config(pmsHostName = "pms")
        val b = ApplicationSettingsHL7Config(pmsHostName = "pms")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a)
    }

    @Test
    fun equalsAndHashCode_notEqual() {
        val a = ApplicationSettingsHL7Config(pmsHostName = "pms")
        val b = ApplicationSettingsHL7Config(pmsHostName = "other")
        assertNotEquals(a, b)
        assertNotEquals(a, null)
        assertNotEquals(a, "string")
    }

    @Test
    fun toStringContainsValue() {
        val config = ApplicationSettingsHL7Config(pmsHostName = "pms")
        assertTrue(config.toString().contains("pms"))
    }

    @Test
    fun copyWorks() {
        val config = ApplicationSettingsHL7Config(pmsHostName = "pms")
        val copy = config.copy(pmsHostName = "other")
        assertEquals("other", copy.pmsHostName)
        assertEquals(config, config.copy())
    }

    @Test
    fun componentN() {
        val config = ApplicationSettingsHL7Config(pmsHostName = "pms", pillCounterHostName = "counter")
        assertEquals("pms", config.component1())
        assertEquals("counter", config.component2())
    }

    @Test
    fun parsesFromJson() {
        val json = """{"pms_host_name":"pms","pillcounter_host_name":"counter"}"""
        val config = NetworkModule.provideMoshi()
            .adapter(ApplicationSettingsHL7Config::class.java).fromJson(json)
        assertEquals("pms", config?.pmsHostName)
        assertEquals("counter", config?.pillCounterHostName)
    }
}
