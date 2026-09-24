package com.dispensesure.retail.core.utils.common

import com.dispensesure.retail.core.utils.common.HistoryRetention.optionsDays


/**
 * Utility object defining supported **history retention durations** (in days)
 * and mapping logic for display-friendly text in the UI.
 *
 * Used for showing "Keep history for X days" options consistently across the app.
 */
object HistoryRetention {

    /** Supported history retention durations in days. */
    val optionsDays = listOf(7, 15, 30, 60, 90)

    /**
     * Returns a display-friendly text label for the given retention duration.
     *
     * @param days Selected retention duration (e.g., 30).
     * @param displayStrings Localized display strings corresponding to [optionsDays].
     * @return Matching label from [displayStrings] or a fallback (e.g., "45 days").
     */
    fun getTrailingText(days: Int, displayStrings: List<String>): String {
        return when (days) {
            7 -> displayStrings.getOrNull(0)
            15 -> displayStrings.getOrNull(1)
            30 -> displayStrings.getOrNull(2)
            60 -> displayStrings.getOrNull(3)
            90 -> displayStrings.getOrNull(4)
            else -> "$days days"
        } ?: "$days days"
    }
}
