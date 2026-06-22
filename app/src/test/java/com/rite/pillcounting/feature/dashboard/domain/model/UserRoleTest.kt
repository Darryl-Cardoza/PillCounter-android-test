package com.rite.pillcounting.feature.dashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserRoleTest {

    @Test
    fun getters_returnConstructorValues() {
        val r = UserRole(id = "1", name = "Admin")
        assertEquals("1", r.id)
        assertEquals("Admin", r.name)
    }

    @Test
    fun defaultValues_areNull() {
        val r = UserRole()
        assertNull(r.id)
        assertNull(r.name)
    }

    @Test
    fun equalsAndHashCode() {
        val a = UserRole("1", "Admin")
        val b = UserRole("1", "Admin")
        val c = a.copy(name = "User")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun toString_containsClassName() {
        assertTrue(UserRole("1", "Admin").toString().contains("UserRole"))
    }

    @Test
    fun copy_overridesField() {
        val copy = UserRole("1", "Admin").copy(id = "2")
        assertEquals("2", copy.id)
        assertEquals("Admin", copy.name)
    }

    @Test
    fun componentFunctions() {
        val r = UserRole("1", "Admin")
        assertEquals("1", r.component1())
        assertEquals("Admin", r.component2())
    }
}
