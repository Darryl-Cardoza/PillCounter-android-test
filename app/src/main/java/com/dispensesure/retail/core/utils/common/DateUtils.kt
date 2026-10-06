package com.dispensesure.retail.core.utils.common

import com.dispensesure.retail.core.utils.logger.AppLogger
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Utility object for date-related operations, including conversion
 * between UTC and local timezones and GS1-style YYMMDD formatting.
 */
object DateUtils {

    private val logger = AppLogger.create<DateUtils>()

    // Common date format constants
    private const val UTC_TIME_ZONE = "UTC"

    /**
     * Converts a UTC timestamp string into a formatted local date string.
     * Expected input format: yyyy-MM-dd'T'HH:mm:ss'Z'
     *
     * @param timestamp The UTC timestamp string to convert.
     * @return A formatted local date string, or empty string if conversion fails.
     */
    fun convertUTCTimestampToDate(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return ""

        return try {
            val utcFormat = SimpleDateFormat(DateFormats.ISO_UTC, Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone(UTC_TIME_ZONE)
            }

            val date: Date? = utcFormat.parse(timestamp)
            if (date == null) {
                logger.e("Failed to parse UTC timestamp: $timestamp")
                return ""
            }

            val localFormat = SimpleDateFormat(DateFormats.MM_DD_YY, Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }

            val formatted = localFormat.format(date)
            logger.d("Converted UTC timestamp: $timestamp → $formatted")
            formatted
        } catch (e: Exception) {
            logger.e("Error converting UTC timestamp: $timestamp", e)
            ""
        }
    }

    /**
     * Converts a GS1-style date string (YYMMDD) to a human-readable date.
     * Example input: "251006" → Output: "10/06/25"
     *
     * @param dateString The date string in YYMMDD format.
     * @return The formatted date string, or empty string if invalid.
     */
    fun convertToDate(dateString: String?): String {
        if (dateString.isNullOrBlank()) return ""

        return try {
            val parser = DateTimeFormatter.ofPattern(DateFormats.GS1_YYMMDD)
            val date = LocalDate.parse(dateString, parser)

            val formatter = DateTimeFormatter.ofPattern(DateFormats.MM_DD_YY, Locale.getDefault())
            val formattedDate = date.format(formatter)

            logger.d("Formatted GS1 date: $dateString → $formattedDate")
            formattedDate
        } catch (e: Exception) {
            logger.e("Error converting GS1 date: $dateString", e)
            ""
        }
    }

    /**
     * Parses an ISO-8601 UTC timestamp into epoch milliseconds.
     *
     * Description:
     * Shared entry point used by any code path that needs epoch ms from a server-
     * supplied ISO-8601 string (e.g. `/health` `checked_at`). Consolidates the
     * tolerant cleanup previously duplicated inside `SessionHealthController`.
     *
     * What it does:
     * - Returns `null` for null / blank / unparseable input.
     * - Strips a trailing fractional-second suffix (`.SSS`), a timezone offset
     *   (`+HH:mm`), and a trailing `Z` before parsing, so `2025-01-15T12:30:45`,
     *   `2025-01-15T12:30:45Z`, `2025-01-15T12:30:45.123Z`, and
     *   `2025-01-15T12:30:45+05:30` all resolve to the same second boundary.
     * - Parses the trimmed value with a UTC-anchored `SimpleDateFormat` so no
     *   device-timezone drift creeps in.
     *
     * @param raw ISO-8601 UTC timestamp string, or `null`.
     * @return Epoch ms in UTC, or `null` if the input is null / blank / unparseable.
     *
     * Example Usage:
     * val ms = DateUtils.parseUtcIsoToEpochMs("2025-01-15T12:30:45Z") // 1_736_944_245_000
     */
    fun parseUtcIsoToEpochMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw
            .substringBefore('.')
            .substringBefore('+')
            .substringBefore('Z')
        return try {
            val parser = SimpleDateFormat(DateFormats.ISO_UTC_NO_SUFFIX, Locale.US).apply {
                timeZone = TimeZone.getTimeZone(UTC_TIME_ZONE)
            }
            parser.parse(cleaned)?.time
        } catch (e: Exception) {
            logger.e("Error parsing ISO UTC timestamp: $raw", e)
            null
        }
    }

    /**
     * Converts a GS1-style-BarcodeDate date (YYMMDD) to a LocalDate instance.
     * Example: "251006" → LocalDate(2025, 10, 6)
     */
    fun parseBarcodeDate(dateString: String?): LocalDate? {
        if (dateString.isNullOrBlank()) return null
        return try {
            val parser = DateTimeFormatter.ofPattern(DateFormats.GS1_YYMMDD)
            LocalDate.parse(dateString, parser)
        } catch (e: Exception) {
            logger.e("Error parsing GS1 date to LocalDate: $dateString", e)
            null
        }
    }
}
