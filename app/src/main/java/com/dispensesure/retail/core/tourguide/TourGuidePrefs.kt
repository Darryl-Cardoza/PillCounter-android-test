package com.dispensesure.retail.core.tourguide

import android.content.Context
import androidx.core.content.edit

/**
 * Whether the first-login tour offer has been answered, per user. Non-sensitive UI state,
 * so plain SharedPreferences like [com.dispensesure.retail.core.utils.preference.CountCirclePositionPrefs].
 */
object TourGuidePrefs {

    private const val PREF_NAME = "tour_guide_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun key(localId: Long) = "ftue_v2_$localId"

    fun hasSeen(context: Context, localId: Long): Boolean =
        prefs(context).getBoolean(key(localId), false)

    fun markSeen(context: Context, localId: Long) {
        prefs(context).edit { putBoolean(key(localId), true) }
    }
}
