package com.rite.pillcounting.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class Hl7ServiceConfigTest {

    @Test
    fun pmsHostNameConstant() {
        assertEquals("_ritepmsserver._tcp", Hl7ServiceConfig.PMS_HOST_NAME)
    }

    @Test
    fun pillCounterHostNameConstant() {
        assertEquals("_pillcounting._tcp", Hl7ServiceConfig.PILL_COUNTER_HOST_NAME)
    }

    @Test
    fun resolvePmsHostName_usesServerValue() {
        assertEquals("_mypms._tcp", Hl7ServiceConfig.resolvePmsHostName("_mypms._tcp"))
    }

    @Test
    fun resolvePmsHostName_nullFallsBackToConstant() {
        assertEquals(Hl7ServiceConfig.PMS_HOST_NAME, Hl7ServiceConfig.resolvePmsHostName(null))
    }

    @Test
    fun resolvePmsHostName_blankFallsBackToConstant() {
        assertEquals(Hl7ServiceConfig.PMS_HOST_NAME, Hl7ServiceConfig.resolvePmsHostName("   "))
    }

    @Test
    fun resolvePillCounterHostName_usesServerValue() {
        assertEquals("_mycounter._tcp", Hl7ServiceConfig.resolvePillCounterHostName("_mycounter._tcp"))
    }

    @Test
    fun resolvePillCounterHostName_nullFallsBackToConstant() {
        assertEquals(
            Hl7ServiceConfig.PILL_COUNTER_HOST_NAME,
            Hl7ServiceConfig.resolvePillCounterHostName(null)
        )
    }

    @Test
    fun resolvePmsHostName_malformedFallsBackToConstant() {
        // Was asserted as pass-through before the review; NsdManager rejects it.
        assertEquals(Hl7ServiceConfig.PMS_HOST_NAME, Hl7ServiceConfig.resolvePmsHostName("PMS"))
    }

    @Test
    fun resolvePmsHostName_withSpaceFallsBackToConstant() {
        assertEquals(
            Hl7ServiceConfig.PMS_HOST_NAME,
            Hl7ServiceConfig.resolvePmsHostName("_my pms._tcp")
        )
    }

    @Test
    fun resolvePmsHostName_missingTransportFallsBackToConstant() {
        assertEquals(Hl7ServiceConfig.PMS_HOST_NAME, Hl7ServiceConfig.resolvePmsHostName("_mypms"))
    }

    @Test
    fun resolvePmsHostName_acceptsUdp() {
        assertEquals("_mypms._udp", Hl7ServiceConfig.resolvePmsHostName("_mypms._udp"))
    }

    @Test
    fun resolvePillCounterHostName_malformedFallsBackToConstant() {
        assertEquals(
            Hl7ServiceConfig.PILL_COUNTER_HOST_NAME,
            Hl7ServiceConfig.resolvePillCounterHostName("pill counter")
        )
    }
}
