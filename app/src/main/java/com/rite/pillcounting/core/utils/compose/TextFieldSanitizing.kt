package com.rite.pillcounting.core.utils.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.rite.pillcounting.core.utils.validator.CredentialsValidator

/**
 * Swaps [current]'s text for [newText], keeping the cursor the same distance
 * from the end. This is the one cursor rule for fields whose text is replaced
 * by a filtered version while the user types — reuse it, don't write another.
 *
 * @param current The value the field is showing.
 * @param newText The text that should replace it.
 * @return [current] itself when nothing changed, else the new text with the cursor moved.
 *
 * Example Usage:
 * withReplacedText(TextFieldValue("Jo3hn", TextRange(3)), "John") // cursor at 2
 */
fun withReplacedText(current: TextFieldValue, newText: String): TextFieldValue {
    if (current.text == newText) return current
    val fromEnd = current.text.length - current.selection.end
    return TextFieldValue(
        text = newText,
        selection = TextRange((newText.length - fromEnd).coerceIn(0, newText.length))
    )
}

/**
 * Filters typed input down to name characters, leaving the cursor where
 * [withReplacedText] puts it instead of jumping it to the end.
 *
 * @param value The value the name field is showing.
 * @return The same value with disallowed characters removed.
 *
 * Example Usage:
 * sanitizeTypedName(TextFieldValue("Jo3hn", TextRange(3))) // "John", cursor at 2
 */
fun sanitizeTypedName(value: TextFieldValue): TextFieldValue =
    withReplacedText(value, CredentialsValidator.sanitizeName(value.text))
