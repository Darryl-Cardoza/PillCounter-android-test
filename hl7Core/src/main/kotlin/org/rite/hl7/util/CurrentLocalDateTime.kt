package org.rite.hl7.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun currentLocalDateTime(): String {
    val formatter = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss",
        Locale.US
    )
    formatter.timeZone = TimeZone.getDefault()
    return formatter.format(Date(System.currentTimeMillis()))
}
