package com.rite.pillcounting.core.utils.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class NdcVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.filter(Char::isDigit).take(11)

        val formatted = buildString {
            digits.forEachIndexed { index, c ->
                append(c)
                if ((index == 4 || index == 8) && index != digits.lastIndex) {
                    append("-")
                }
            }
        }

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                return when {
                    offset <= 5 -> offset
                    offset <= 9 -> offset + 1
                    offset <= 11 -> offset + 2
                    else -> formatted.length
                }
            }

            override fun transformedToOriginal(offset: Int): Int {
                return when {
                    offset <= 5 -> offset
                    offset <= 10 -> offset - 1
                    offset <= 13 -> offset - 2
                    else -> digits.length
                }
            }
        }

        return TransformedText(
            AnnotatedString(formatted),
            offsetMapping
        )
    }
}
