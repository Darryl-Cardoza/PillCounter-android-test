package com.rite.pillcounting.core.utils.validator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [CredentialsValidator].
 *
 * RobolectricTestRunner is used because [CredentialsValidator] calls
 * android.util.Patterns.EMAIL_ADDRESS / PHONE — static fields that are null
 * in the plain Android stub JAR. Robolectric initialises them with real values
 * so validation logic runs correctly on the JVM without a device/emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CredentialsValidatorTest {

    private lateinit var validator: CredentialsValidator

    @Before
    fun setup() {
        validator = CredentialsValidator()
    }

    // -------------------------------------------------------------------------
    // validateEmail
    // -------------------------------------------------------------------------

    // CRED_001
    @Test
    fun `validateEmail returns success for valid email`() {
        val result = validator.validateEmail("user@pharmacy.com")
        assertTrue(result.isSuccess)
        assertNull(result.errorMessageResId)
    }

    // CRED_002 — blank is optional (isBlank check returns true before regex)
    @Test
    fun `validateEmail returns success for blank email because field is optional`() {
        val result = validator.validateEmail("")
        assertTrue(result.isSuccess)
    }

    // CRED_003
    @Test
    fun `validateEmail returns success for whitespace-only email because field is optional`() {
        val result = validator.validateEmail("   ")
        assertTrue(result.isSuccess)
    }

    // CRED_004
    @Test
    fun `validateEmail returns failure for email without at symbol`() {
        val result = validator.validateEmail("userpharma.com")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_005
    @Test
    fun `validateEmail returns failure for email without domain extension`() {
        val result = validator.validateEmail("user@pharmacy")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_006
    @Test
    fun `validateEmail returns failure for email starting with at symbol`() {
        val result = validator.validateEmail("@pharmacy.com")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_007
    @Test
    fun `validateEmail returns success for email with subdomain`() {
        val result = validator.validateEmail("admin@mail.pharmacy.com")
        assertTrue(result.isSuccess)
    }

    // -------------------------------------------------------------------------
    // validatePassword
    // -------------------------------------------------------------------------

    // CRED_008
    @Test
    fun `validatePassword returns success when all rules satisfied`() {
        val result = validator.validatePassword("Secure#1pass")
        assertTrue(result.isSuccess)
        assertNull(result.errorMessageResId)
    }

    // CRED_009
    @Test
    fun `validatePassword returns failure when fewer than 8 characters`() {
        val result = validator.validatePassword("Ab1#")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_010
    @Test
    fun `validatePassword returns failure when exactly 7 characters`() {
        val result = validator.validatePassword("Ab1#efg")
        assertFalse(result.isSuccess)
    }

    // CRED_011
    @Test
    fun `validatePassword returns failure when no uppercase letter`() {
        val result = validator.validatePassword("secure#1pass")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_012
    @Test
    fun `validatePassword returns failure when no lowercase letter`() {
        val result = validator.validatePassword("SECURE#1PASS")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_013
    @Test
    fun `validatePassword returns failure when no digit`() {
        val result = validator.validatePassword("Secure#pass")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_014
    @Test
    fun `validatePassword returns failure when no special character`() {
        val result = validator.validatePassword("Secure1pass")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // -------------------------------------------------------------------------
    // validateRequiredName (blank = REQUIRED, not optional)
    // -------------------------------------------------------------------------

    // CRED_015
    @Test
    fun `validateRequiredName returns success for valid alphabetic name`() {
        val result = validator.validateRequiredName("John Doe")
        assertTrue(result.isSuccess)
        assertNull(result.errorMessageResId)
    }

    // CRED_016 — blank triggers error_name_required
    @Test
    fun `validateRequiredName returns failure for blank name because field is required`() {
        val result = validator.validateRequiredName("")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_017
    @Test
    fun `validateRequiredName returns failure when name contains a digit`() {
        val result = validator.validateRequiredName("John123")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_018
    @Test
    fun `validateRequiredName returns success for name with hyphens and spaces`() {
        val result = validator.validateRequiredName("Mary-Jane Watson")
        assertTrue(result.isSuccess)
    }

    // -------------------------------------------------------------------------
    // validatePharmacyName
    // -------------------------------------------------------------------------

    // CRED_019 — blank is optional
    @Test
    fun `validatePharmacyName returns success for blank pharmacy name because field is optional`() {
        val result = validator.validatePharmacyName("")
        assertTrue(result.isSuccess)
    }

    // CRED_020
    @Test
    fun `validatePharmacyName returns failure for single-character name`() {
        val result = validator.validatePharmacyName("A")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_021
    @Test
    fun `validatePharmacyName returns success for two-character name (minimum)`() {
        val result = validator.validatePharmacyName("AB")
        assertTrue(result.isSuccess)
    }

    // CRED_022
    @Test
    fun `validatePharmacyName returns success for full pharmacy name`() {
        val result = validator.validatePharmacyName("Rite Pharmacy LLC")
        assertTrue(result.isSuccess)
    }

    // -------------------------------------------------------------------------
    // validateNpi (optional; digits only; length 6..12)
    // -------------------------------------------------------------------------

    // CRED_023
    @Test
    fun `validateNpi returns success for blank NPI because field is optional`() {
        val result = validator.validateNpi("")
        assertTrue(result.isSuccess)
    }

    // CRED_024
    @Test
    fun `validateNpi returns success for 6-digit NPI (minimum length)`() {
        val result = validator.validateNpi("123456")
        assertTrue(result.isSuccess)
    }

    // CRED_025
    @Test
    fun `validateNpi returns success for standard 10-digit NPI`() {
        val result = validator.validateNpi("1234567890")
        assertTrue(result.isSuccess)
    }

    // CRED_026
    @Test
    fun `validateNpi returns success for 12-digit NPI (maximum length)`() {
        val result = validator.validateNpi("123456789012")
        assertTrue(result.isSuccess)
    }

    // CRED_027
    @Test
    fun `validateNpi returns failure for 5-digit NPI (below minimum)`() {
        val result = validator.validateNpi("12345")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_028
    @Test
    fun `validateNpi returns failure for 13-digit NPI (above maximum)`() {
        val result = validator.validateNpi("1234567890123")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_029
    @Test
    fun `validateNpi returns failure when NPI contains letters`() {
        val result = validator.validateNpi("12345A")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // -------------------------------------------------------------------------
    // validatePhone (optional; Patterns.PHONE match + length >= 7)
    // -------------------------------------------------------------------------

    // CRED_030
    @Test
    fun `validatePhone returns success for blank phone because field is optional`() {
        val result = validator.validatePhone("")
        assertTrue(result.isSuccess)
    }

    // CRED_031 — 6 digits falls under length guard (< 7)
    @Test
    fun `validatePhone returns failure for phone shorter than 7 digits`() {
        val result = validator.validatePhone("123456")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessageResId)
    }

    // CRED_032
    @Test
    fun `validatePhone returns success for 10-digit phone number`() {
        val result = validator.validatePhone("1234567890")
        assertTrue(result.isSuccess)
    }
}
