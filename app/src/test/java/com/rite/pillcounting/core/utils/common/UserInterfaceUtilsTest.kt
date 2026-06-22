package com.rite.pillcounting.core.utils.common

import androidx.compose.ui.text.AnnotatedString
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toColor
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toDateString
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toTimeString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Unit tests for the NON-@Composable members of UserInterfaceUtils.kt:
 *  - PhoneNumberVisualTransformation (pure Compose-text logic)
 *  - TABLET_BREAKPOINT_DP constant
 *  - String.toColor()           (see NOTE below)
 *  - Long?.toFormattedDate(), Long.toDateString(), Long.toTimeString()
 *
 * The @Composable members (isTablet, isLandscape, dialogs, text fields, responsive* helpers,
 * AppInfo, etc.) require a Compose runtime / LocalConfiguration and are NOT covered here.
 *
 * NOTE on toColor(): String.toColorInt() ultimately calls android.graphics.Color.parseColor,
 * which under isReturnDefaultValues=true returns 0 (no real parsing). We therefore only assert
 * the call returns a Color without throwing, not a specific ARGB value.
 */
class UserInterfaceUtilsTest {

    // ───────────────────────────── PhoneNumberVisualTransformation ─────────────────────────────

    private val phone = PhoneNumberVisualTransformation()

    private fun phoneFormatted(input: String): String =
        phone.filter(AnnotatedString(input)).text.text

    @Test
    fun phone_formatsFull10Digits() {
        assertEquals("(123) 456-7890", phoneFormatted("1234567890"))
    }

    @Test
    fun phone_partial_oneDigit() {
        assertEquals("(1", phoneFormatted("1"))
    }

    @Test
    fun phone_partial_fourDigits_insertsCloseParen() {
        assertEquals("(123) 4", phoneFormatted("1234"))
    }

    @Test
    fun phone_partial_sevenDigits_insertsDash() {
        assertEquals("(123) 456-7", phoneFormatted("1234567"))
    }

    @Test
    fun phone_empty_returnsEmpty() {
        assertEquals("", phoneFormatted(""))
    }

    @Test
    fun phone_offsetMapping_originalToTransformed_allBranches() {
        val mapping = phone.filter(AnnotatedString("1234567890")).offsetMapping
        assertEquals(0, mapping.originalToTransformed(0))    // offset == 0
        assertEquals(2, mapping.originalToTransformed(1))    // <= 3 (+1)
        assertEquals(4, mapping.originalToTransformed(3))    // <= 3 (+1)
        assertEquals(7, mapping.originalToTransformed(4))    // <= 6 (+3)
        assertEquals(9, mapping.originalToTransformed(6))    // <= 6 (+3)
        assertEquals(11, mapping.originalToTransformed(7))   // else (+4)
        assertEquals(14, mapping.originalToTransformed(10))  // else (+4) coerced to length
    }

    @Test
    fun phone_offsetMapping_transformedToOriginal_allBranches() {
        val mapping = phone.filter(AnnotatedString("1234567890")).offsetMapping
        assertEquals(0, mapping.transformedToOriginal(0))    // <= 1
        assertEquals(0, mapping.transformedToOriginal(1))    // <= 1
        assertEquals(1, mapping.transformedToOriginal(2))    // <= 4 (-1)
        assertEquals(3, mapping.transformedToOriginal(4))    // <= 4 (-1)
        assertEquals(3, mapping.transformedToOriginal(5))    // <= 6 -> 3
        assertEquals(3, mapping.transformedToOriginal(6))    // <= 6 -> 3
        assertEquals(4, mapping.transformedToOriginal(7))    // <= 9 (-3)
        assertEquals(6, mapping.transformedToOriginal(9))    // <= 9 (-3)
        assertEquals(6, mapping.transformedToOriginal(10))   // == 10 -> 6
        assertEquals(7, mapping.transformedToOriginal(11))   // else (-4)
    }

    // ───────────────────────────── constant ─────────────────────────────

    @Test
    fun tabletBreakpoint_is600() {
        assertEquals(600, UserInterfaceUtils.TABLET_BREAKPOINT_DP)
    }

    // ───────────────────────────── toColor ─────────────────────────────

    @Test
    fun toColor_doesNotThrow_returnsColor() {
        val color = "#FF5733".toColor()
        assertNotNull(color)
    }

    // ───────────────────────────── date/time extensions ─────────────────────────────

    @Test
    fun toFormattedDate_null_returnsDash() {
        val value: Long? = null
        assertEquals("-", value.toFormattedDate())
    }

    @Test
    fun toFormattedDate_zero_returnsDash() {
        assertEquals("-", 0L.toFormattedDate())
    }

    @Test
    fun toFormattedDate_negative_returnsDash() {
        assertEquals("-", (-5L).toFormattedDate())
    }

    @Test
    fun toFormattedDate_validEpoch_returnsFormattedString() {
        // 2021-01-01T00:00:00Z = 1609459200000
        val result = 1609459200000L.toFormattedDate()
        assertTrue("got '$result'", result.contains("2020") || result.contains("2021"))
        assertTrue(result != "-")
    }

    @Test
    fun toDateString_default_formatsKnownEpoch() {
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            // 1609459200000 = 01 Jan 2021 UTC
            assertEquals("01 Jan 2021", 1609459200000L.toDateString())
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    @Test
    fun toDateString_customPattern() {
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("2021-01-01", 1609459200000L.toDateString("yyyy-MM-dd"))
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    @Test
    fun toTimeString_default_formatsTime() {
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            // 1609459200000 = midnight UTC. The AM/PM marker's case is locale-dependent
            // (some JDK locales render "am"/"pm"), so compare case-insensitively.
            assertEquals("12:00 am", 1609459200000L.toTimeString().lowercase())
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    @Test
    fun toTimeString_customPattern() {
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("00:00", 1609459200000L.toTimeString("HH:mm"))
        } finally {
            TimeZone.setDefault(tz)
        }
    }
}
