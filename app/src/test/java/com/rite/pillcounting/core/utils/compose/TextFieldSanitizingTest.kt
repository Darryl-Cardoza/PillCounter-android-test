package com.rite.pillcounting.core.utils.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Unit tests for the shared name-field filter and its cursor rule. */
class TextFieldSanitizingTest {

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
    fun `cursor keeps its distance from the end`() {
        // Cursor sat after "Jo3" with "hn" behind it, so it lands after "Jo".
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

    @Test
    fun `withReplacedText moves the cursor with the shortened text`() {
        val result = withReplacedText(TextFieldValue("CVS1", TextRange(4)), "CVS")
        assertEquals("CVS", result.text)
        assertEquals(3, result.selection.end)
    }
}
