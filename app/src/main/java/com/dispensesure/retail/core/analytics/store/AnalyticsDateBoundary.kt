package com.dispensesure.retail.core.analytics.store

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Business-date calculations for analytics.
 *
 * All date arithmetic uses [java.time], which is proleptic Gregorian by definition — no
 * `java.util.Calendar` or locale-sensitive `SimpleDateFormat` is used, so the ISO date is
 * correct under any system locale, including non-Gregorian ones like Buddhist Thai.
 *
 * @param zone The time zone to interpret "local midnight" in. Injected so tests can use a fixed
 *   zone without touching the system default.
 */
class AnalyticsDateBoundary(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val isoDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        .withLocale(Locale.ROOT)
        .withZone(zone)

    /**
     * The business date string (yyyy-MM-dd) for a given epoch-ms instant.
     *
     * Uses the system time zone so a count completed at 11:58 PM local time lands on that local
     * calendar day, not the UTC date.
     */
    fun businessDate(nowMillis: Long): String =
        isoDateFormatter.format(java.time.Instant.ofEpochMilli(nowMillis))

    /**
     * The zone offset in minutes for a given epoch-ms instant. Positive east of UTC.
     *
     * Stored in the row so the server can detect timezone shifts between retries of the same day.
     */
    fun timezoneOffsetMinutes(nowMillis: Long): Int {
        val instant = java.time.Instant.ofEpochMilli(nowMillis)
        return zone.rules.getOffset(instant).totalSeconds / 60
    }

    /**
     * The [start, end) epoch-ms range for the local calendar day identified by [businessDate].
     *
     * Uses `LocalDate.plusDays(1).atStartOfDay(zone)` rather than `+86_400_000` so DST transitions
     * produce a 23- or 25-hour range instead of always 24 hours.
     *
     * @return null if [businessDate] cannot be parsed as ISO yyyy-MM-dd.
     */
    fun dayRangeMillis(businessDate: String): Pair<Long, Long>? {
        val date = runCatching { LocalDate.parse(businessDate) }.getOrNull() ?: return null
        val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }
}
