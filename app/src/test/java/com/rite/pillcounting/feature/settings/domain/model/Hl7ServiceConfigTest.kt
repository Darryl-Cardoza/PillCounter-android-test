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
}
