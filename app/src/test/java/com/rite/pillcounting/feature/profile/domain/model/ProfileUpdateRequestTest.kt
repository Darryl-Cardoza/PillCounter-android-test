package com.rite.pillcounting.feature.profile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUpdateRequestTest {

    private fun sample() = ProfileUpdateRequest(
        fName = "John",
        lName = "Doe",
        pharmacyName = "Pharmacy",
        phoneNumber = "1234567890",
        npiId = "npi1",
        isProfileComplete = true,
        avatarUrl = "http://avatar",
        notificationsEnabled = false,
        language = "en",
        timezone = "UTC",
        terminalId = "term1"
    )

    @Test
    fun getters_returnValues() {
        val r = sample()
        assertEquals("John", r.fName)
        assertEquals("Doe", r.lName)
        assertEquals("Pharmacy", r.pharmacyName)
        assertEquals("1234567890", r.phoneNumber)
        assertEquals("npi1", r.npiId)
        assertTrue(r.isProfileComplete)
        assertEquals("http://avatar", r.avatarUrl)
        assertEquals(false, r.notificationsEnabled)
        assertEquals("en", r.language)
        assertEquals("UTC", r.timezone)
        assertEquals("term1", r.terminalId)
    }

    @Test
    fun terminalId_defaultsToNull() {
        val r = ProfileUpdateRequest(
            fName = "John",
            lName = "Doe",
            pharmacyName = "Pharmacy",
            phoneNumber = "1234567890",
            npiId = "npi1",
            isProfileComplete = true,
            avatarUrl = "http://avatar",
            notificationsEnabled = false,
            language = "en",
            timezone = "UTC"
        )
        assertNull(r.terminalId)
    }

    @Test
    fun equals_and_hashCode_areEqualForSameValues() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
    }

    @Test
    fun equals_notEqualForDifferentValues() {
        assertNotEquals(sample(), sample().copy(fName = "Jane"))
    }

    @Test
    fun toString_containsValues() {
        assertTrue(sample().toString().contains("fName=John"))
    }

    @Test
    fun copy_overridesValues() {
        assertEquals("Jane", sample().copy(fName = "Jane").fName)
    }

    @Test
    fun componentN_returnValues() {
        val r = sample()
        assertEquals("John", r.component1())
        assertEquals("Doe", r.component2())
        assertEquals("Pharmacy", r.component3())
        assertEquals("1234567890", r.component4())
        assertEquals("npi1", r.component5())
        assertEquals(true, r.component6())
        assertEquals("http://avatar", r.component7())
        assertEquals(false, r.component8())
        assertEquals("en", r.component9())
        assertEquals("UTC", r.component10())
        assertEquals("term1", r.component11())
    }
}
