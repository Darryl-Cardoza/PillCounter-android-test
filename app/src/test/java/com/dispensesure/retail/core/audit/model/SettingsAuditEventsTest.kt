package com.dispensesure.retail.core.audit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsAuditEventsTest {

    @Test
    fun `ifChanged is null when the value did not change`() {
        assertNull(SettingChanged.ifChanged(SecuritySetting.FACE_LOCK_TIMEOUT_MINUTES, 5, 5))
        assertNull(ServerConfigChanged.ifChanged(ServerSetting.PMS_PORT, 0, 0))
        assertNull(ServerConfigChanged.ifChanged(ServerSetting.PMS_IP, null, null))
    }

    @Test
    fun `ifChanged keeps the setting and old and new as text`() {
        assertEquals(
            SettingChanged(SecuritySetting.REQUIRE_BACK_COUNT, "false", "true"),
            SettingChanged.ifChanged(SecuritySetting.REQUIRE_BACK_COUNT, false, true)
        )
        assertEquals(
            ServerConfigChanged(ServerSetting.PMS_IP, null, "10.0.0.5"),
            ServerConfigChanged.ifChanged(ServerSetting.PMS_IP, null, "10.0.0.5")
        )
    }
}
