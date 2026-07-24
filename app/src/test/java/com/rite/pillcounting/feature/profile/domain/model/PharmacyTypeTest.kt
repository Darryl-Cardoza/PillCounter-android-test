package com.rite.pillcounting.feature.profile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PharmacyTypeTest {

    @Test
    fun `fromApiValue returns correct enum for each known apiValue`() {
        PharmacyType.entries.forEach { type ->
            assertEquals(type, PharmacyType.fromApiValue(type.apiValue))
        }
    }

    @Test
    fun `fromApiValue returns null for unknown value`() {
        assertNull(PharmacyType.fromApiValue("not_a_real_pharmacy_type"))
    }

    @Test
    fun `fromApiValue returns null for null input`() {
        assertNull(PharmacyType.fromApiValue(null))
    }

    @Test
    fun `fromApiValue returns null for empty string`() {
        assertNull(PharmacyType.fromApiValue(""))
    }

    @Test
    fun `fromApiValue is case sensitive`() {
        assertNull(PharmacyType.fromApiValue("CHAIN_PHARMACY"))
    }

    @Test
    fun `apiValue is unique across all entries`() {
        val apiValues = PharmacyType.entries.map { it.apiValue }
        assertEquals(apiValues.size, apiValues.toSet().size)
    }

    @Test
    fun `entries contains exactly the expected pharmacy types`() {
        val expected = setOf(
            "chain_pharmacy",
            "supermarket_big_box_pharmacy",
            "independent_pharmacy",
            "mail_order_online_pharmacy",
            "hospital_pharmacy",
            "clinic_pharmacy",
            "long_term_care_pharmacy",
            "compounding_pharmacy",
            "specialty_pharmacy"
        )
        val actual = PharmacyType.entries.map { it.apiValue }.toSet()
        assertEquals(expected, actual)
    }
}
