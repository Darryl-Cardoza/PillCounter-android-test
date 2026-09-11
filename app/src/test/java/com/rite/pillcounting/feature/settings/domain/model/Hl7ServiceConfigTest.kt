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
    fun resolve_doesNotValidateFormat() {
        // No format validation by design — a malformed value is stored and used verbatim.
        assertEquals("PMS", Hl7ServiceConfig.resolvePmsHostName("PMS"))
    }
}
