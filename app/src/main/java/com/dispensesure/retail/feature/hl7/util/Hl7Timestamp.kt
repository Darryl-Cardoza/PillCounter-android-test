package com.dispensesure.retail.feature.hl7.util

import com.dispensesure.retail.core.utils.common.DateFormats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The current time in HL7's timestamp format, for the outbound builders' MSH-7 and segment times. */
internal fun hl7TimestampNow(): String =
    SimpleDateFormat(DateFormats.HL7_TIMESTAMP, Locale.US).format(Date())

/** An epoch-millis instant in HL7's timestamp format, e.g. a transaction's real start time. */
internal fun hl7Timestamp(epochMillis: Long): String =
    SimpleDateFormat(DateFormats.HL7_TIMESTAMP, Locale.US).format(Date(epochMillis))

/** Display date patterns a stored expiry may use, newest-written first. */
private val EXPIRY_INPUT_PATTERNS = listOf("MM-dd-yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "yyyy/MM/dd")

/**
 * A stored display date (e.g. "11-03-2026") as an HL7 date, "yyyyMMdd".
 *
 * Expiries are persisted in the region's display format, but HL7 dates are unambiguous by
 * definition — sending "11-03-2026" leaves the receiver to guess month-first or day-first, which
 * on a controlled-substance count is a real safety problem. An already-HL7 value passes through;
 * anything unparseable returns blank rather than risk a wrong date.
 */
internal fun hl7Date(stored: String?): String {
    val value = stored?.trim().orEmpty()
    if (value.isEmpty()) return ""
    if (value.length == 8 && value.all { it.isDigit() }) return value
    for (pattern in EXPIRY_INPUT_PATTERNS) {
        runCatching {
            val parsed = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)
            if (parsed != null) return SimpleDateFormat("yyyyMMdd", Locale.US).format(parsed)
        }
    }
    // A month/year expiry ("2027-01", "01-2027") is normal on pharmacy stock. HL7 allows the
    // shorter yyyyMM precision, so keep it rather than dropping a real expiry.
    Regex("""^(\d{4})[-/](\d{2})$""").find(value)?.let { return it.groupValues[1] + it.groupValues[2] }
    Regex("""^(\d{2})[-/](\d{4})$""").find(value)?.let { return it.groupValues[2] + it.groupValues[1] }
    return ""
}
