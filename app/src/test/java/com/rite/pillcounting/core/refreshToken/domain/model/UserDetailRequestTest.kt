package com.rite.pillcounting.core.refreshToken.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDetailRequestTest {

    private fun model() = UserDetailRequest(platform = "android", appVersion = "1.0.0")

    @Test
    fun getters_returnValues() {
        val m = model()
        assertEquals("android", m.platform)
        assertEquals("1.0.0", m.appVersion)
    }

    @Test
    fun equalsHashCode_equal() {
        assertEquals(model(), model())
        assertEquals(model().hashCode(), model().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(model(), model().copy(platform = "ios"))
    }

    @Test
    fun toString_containsField() {
        assertTrue(model().toString().contains("android"))
    }

    @Test
    fun copy_overrides() {
        assertEquals("2.0", model().copy(appVersion = "2.0").appVersion)
    }

    @Test
    fun componentN_returnValues() {
        val m = model()
        assertEquals("android", m.component1())
        assertEquals("1.0.0", m.component2())
    }
}
