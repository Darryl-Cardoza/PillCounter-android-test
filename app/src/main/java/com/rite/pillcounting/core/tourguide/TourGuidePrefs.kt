package com.dispensesure.retail.core.tourguide

import android.content.Context
import androidx.core.content.edit

/**
 * Tracks which screens' tour guide the user has already seen, so it only auto-launches
 * once per screen. Non-sensitive per-device UI state — plain SharedPreferences, same as
 * [com.dispensesure.retail.core.utils.preference.CountCirclePositionPrefs].
 */
object TourGuidePrefs {

    private const val PREF_NAME = "tour_guide_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun hasSeen(context: Context, screenId: String): Boolean =
        prefs(context).getBoolean(screenId, false)

    fun markSeen(context: Context, screenId: String) {
        prefs(context).edit { putBoolean(screenId, true) }
    }
}
