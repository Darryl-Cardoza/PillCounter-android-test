package com.rite.pillcounting.core.utils.common

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [NdcVisualTransformation].
 *
 * filter() and its OffsetMapping are pure JVM (Compose text types only), so they run
 * on the unit-test JVM. Covers digit filtering/truncation, dash insertion, and the
 * full range of both offset-mapping branches.
 */
class NdcVisualTransformationTest {

    private val transform = NdcVisualTransformation()

    private fun formatted(input: String): String =
        transform.filter(AnnotatedString(input)).text.text

    @Test
    fun filter_formatsFull11Digits_with5_4_2Grouping() {
        assertEquals("12345-6789-01", formatted("12345678901"))
    }

    @Test
    fun filter_stripsNonDigits() {
        assertEquals("12345-6789-01", formatted("12a345-6789b01"))
    }

    @Test
    fun filter_truncatesBeyond11Digits() {
        assertEquals("12345-6789-01", formatted("123456789012345"))
    }

    @Test
    fun filter_partialInput_noTrailingDashAfterLastDigit() {
        // 5 digits exactly -> index 4 is lastIndex so no dash appended
        assertEquals("12345", formatted("12345"))
    }

    @Test
    fun filter_sixDigits_insertsFirstDash() {
        assertEquals("12345-6", formatted("123456"))
    }

    @Test
    fun filter_nineDigits_noSecondDashYet() {
        // index 8 is lastIndex -> no dash appended after 9th digit
        assertEquals("12345-6789", formatted("123456789"))
    }

    @Test
    fun filter_tenDigits_insertsSecondDash() {
        assertEquals("12345-6789-0", formatted("1234567890"))
    }

    @Test
    fun filter_empty_returnsEmpty() {
        assertEquals("", formatted(""))
    }

    @Test
    fun offsetMapping_originalToTransformed_allBranches() {
        val mapping = transform.filter(AnnotatedString("12345678901")).offsetMapping
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(5, mapping.originalToTransformed(5))   // <= 5 branch
        assertEquals(7, mapping.originalToTransformed(6))   // <= 9 branch (+1)
        assertEquals(10, mapping.originalToTransformed(9))  // <= 9 branch (+1)
        assertEquals(12, mapping.originalToTransformed(10)) // <= 11 branch (+2)
        assertEquals(13, mapping.originalToTransformed(11)) // <= 11 branch (+2)
        // else branch -> formatted.length (13)
        assertEquals(13, mapping.originalToTransformed(99))
    }

    @Test
    fun offsetMapping_transformedToOriginal_allBranches() {
        val mapping = transform.filter(AnnotatedString("12345678901")).offsetMapping
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(5, mapping.transformedToOriginal(5))   // <= 5 branch
        assertEquals(5, mapping.transformedToOriginal(6))   // <= 10 branch (-1)
        assertEquals(9, mapping.transformedToOriginal(10))  // <= 10 branch (-1)
        assertEquals(9, mapping.transformedToOriginal(11))  // <= 13 branch (-2)
        assertEquals(11, mapping.transformedToOriginal(13)) // <= 13 branch (-2)
        // else branch -> digits.length (11)
        assertEquals(11, mapping.transformedToOriginal(99))
    }
}
