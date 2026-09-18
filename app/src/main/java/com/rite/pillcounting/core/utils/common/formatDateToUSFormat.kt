package com.rite.pillcounting.core.utils.common

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.*

fun formatDateToUSFormat(
    input: String,
    outputPattern: String = DateFormats.MM_DD_YYYY
): String {
    return try {
        val cleanInput = input.trim()

        val inputFormats = DateFormats.INPUT_FORMATS

        val outputFormat = SimpleDateFormat(outputPattern, Locale.US)

        for (format in inputFormats) {
            val parser = SimpleDateFormat(format, Locale.US).apply {
                isLenient = false
            }

            val position = ParsePosition(0)
            val date = parser.parse(cleanInput, position)

            if (date != null && position.index == cleanInput.length) {
                return outputFormat.format(date)
            }
        }

        cleanInput
    } catch (_: Exception) {
        input
    }
}
