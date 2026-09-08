package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Unit tests for [sanitizeTypedName], the name filter on the face-enrollment fields. */
class ScanPhotoIdNameInputTest {

    @Test
    fun `clean input is returned untouched`() {
        val value = TextFieldValue("John", TextRange(2))
        assertSame(value, sanitizeTypedName(value))
    }

    @Test
    fun `digits are stripped from the text`() {
        val result = sanitizeTypedName(TextFieldValue("Jo3hn", TextRange(5)))
        assertEquals("John", result.text)
    }

    @Test
    fun `cursor stays after the surviving characters before it`() {
        // Cursor sat after "Jo3" — one of those three was dropped, so it lands after "Jo".
        val result = sanitizeTypedName(TextFieldValue("Jo3hn", TextRange(3)))
        assertEquals(2, result.selection.start)
        assertEquals(2, result.selection.end)
    }

    @Test
    fun `cursor never exceeds the filtered text`() {
        val result = sanitizeTypedName(TextFieldValue("12345", TextRange(5)))
        assertEquals("", result.text)
        assertEquals(0, result.selection.end)
    }
}
