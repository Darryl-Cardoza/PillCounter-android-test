package com.dispensesure.retail.core.utils.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhiRedactorTest {

    @Test
    fun `redacts an email address`() {
        val result = PhiRedactor.redact("Failed to notify jane.doe@example.com about the refill")
        assertFalse(result.contains("jane.doe@example.com"))
        assertEquals("Failed to notify ***REDACTED-EMAIL*** about the refill", result)
    }

    @Test
    fun `redacts a dashed SSN`() {
        val result = PhiRedactor.redact("Lookup failed for SSN 123-45-6789")
        assertEquals("Lookup failed for SSN ***REDACTED-SSN***", result)
    }

    @Test
    fun `redacts a credit card style digit run`() {
        val result = PhiRedactor.redact("Charge failed for card 4111 1111 1111 1111")
        assertEquals("Charge failed for card ***REDACTED-CC***", result)
    }

    @Test
    fun `redacts a phone number with separators`() {
        val result = PhiRedactor.redact("Callback requested at 555-123-4567")
        assertEquals("Callback requested at ***REDACTED-PHONE***", result)
    }

    @Test
    fun `does not redact an unrelated NDC-style digit run`() {
        // A plain 10-11 digit NDC/Rx number, no separators, should not be flagged as a phone or card.
        val result = PhiRedactor.redact("Drug lookup failed for NDC 00071015523")
        assertEquals("Drug lookup failed for NDC 00071015523", result)
    }

    @Test
    fun `leaves ordinary text untouched`() {
        val result = PhiRedactor.redact("Scan timed out after 2 attempts")
        assertEquals("Scan timed out after 2 attempts", result)
    }
}
