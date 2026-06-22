package com.rite.pillcounting.feature.verifyPin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserRoleTest {

    @Test
    fun getters_returnValues() {
        val role = UserRole(id = "r1", name = "admin")
        assertEquals("r1", role.id)
        assertEquals("admin", role.name)
    }

    @Test
    fun defaults_areNull() {
        val role = UserRole()
        assertNull(role.id)
        assertNull(role.name)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        val a = UserRole("r1", "admin")
        val b = UserRole("r1", "admin")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(UserRole("r1", "admin"), UserRole("r2", "user"))
    }

    @Test
    fun toString_containsValues() {
        assertTrue(UserRole("r1", "admin").toString().contains("id=r1"))
    }

    @Test
    fun copy_overridesValue() {
        assertEquals("r2", UserRole("r1", "admin").copy(id = "r2").id)
    }

    @Test
    fun componentN_returnValues() {
        val role = UserRole("r1", "admin")
        assertEquals("r1", role.component1())
        assertEquals("admin", role.component2())
    }
}
