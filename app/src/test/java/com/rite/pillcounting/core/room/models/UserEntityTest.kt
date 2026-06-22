package com.rite.pillcounting.core.room.models

import com.rite.pillcounting.core.security.models.SecureString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserEntityTest {

    private fun sample() = UserEntity(
        localId = 1L,
        userId = "u1",
        email = SecureString("a@b.com"),
        fName = "First",
        lName = "Last",
        phoneNumber = SecureString("12345"),
        avatarUrl = "url",
        role = "admin",
        isVerified = true,
        isProfileCompleted = true,
        pharmacyName = "Pharm",
        npiId = "npi",
        language = "en",
        timezone = "Asia/Kolkata",
        notifications = true,
        createdAt = 100L
    )

    @Test
    fun defaultValues() {
        val e = UserEntity(userId = "u")
        assertEquals(0L, e.localId)
        assertEquals("u", e.userId)
        assertNull(e.email)
        assertNull(e.fName)
        assertNull(e.lName)
        assertNull(e.phoneNumber)
        assertNull(e.avatarUrl)
        assertNull(e.role)
        assertFalse(e.isVerified)
        assertNull(e.isProfileCompleted)
        assertNull(e.pharmacyName)
        assertNull(e.npiId)
        assertNull(e.language)
        assertNull(e.timezone)
        assertNull(e.notifications)
        assertTrue(e.createdAt > 0L)
    }

    @Test
    fun getters() {
        val e = sample()
        assertEquals(1L, e.localId)
        assertEquals("u1", e.userId)
        assertEquals(SecureString("a@b.com"), e.email)
        assertEquals("First", e.fName)
        assertEquals("Last", e.lName)
        assertEquals(SecureString("12345"), e.phoneNumber)
        assertEquals("url", e.avatarUrl)
        assertEquals("admin", e.role)
        assertTrue(e.isVerified)
        assertEquals(true, e.isProfileCompleted)
        assertEquals("Pharm", e.pharmacyName)
        assertEquals("npi", e.npiId)
        assertEquals("en", e.language)
        assertEquals("Asia/Kolkata", e.timezone)
        assertEquals(true, e.notifications)
        assertEquals(100L, e.createdAt)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(userId = "other"))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("userId=u1"))
    }

    @Test
    fun copy() {
        assertEquals("z", sample().copy(userId = "z").userId)
    }

    @Test
    fun componentN() {
        val e = sample()
        assertEquals(1L, e.component1())
        assertEquals("u1", e.component2())
        assertEquals(SecureString("a@b.com"), e.component3())
        assertEquals("First", e.component4())
        assertEquals("Last", e.component5())
        assertEquals(SecureString("12345"), e.component6())
        assertEquals("url", e.component7())
        assertEquals("admin", e.component8())
        assertEquals(true, e.component9())
        assertEquals(true, e.component10())
        assertEquals("Pharm", e.component11())
        assertEquals("npi", e.component12())
        assertEquals("en", e.component13())
        assertEquals("Asia/Kolkata", e.component14())
        assertEquals(true, e.component15())
        assertEquals(100L, e.component16())
    }
}
