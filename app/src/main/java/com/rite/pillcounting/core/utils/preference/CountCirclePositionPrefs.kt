package com.rite.pillcounting.core.utils.preference

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.core.content.edit

/**
 * Lightweight (non-encrypted) persistence for the draggable count-circle position
 * in the landscape count overlay. The offset is stored in raw pixels relative to
 * the screen centre — this is a per-device UI convenience, not sensitive data, so
 * a plain SharedPreferences is intentionally used instead of [SecurePreferences].
 *
 * Returns null when no position has been saved yet, so the caller can fall back
 * to centring the circle.
 */
object CountCirclePositionPrefs {

    private const val PREF_NAME = "count_circle_position_prefs"
    private const val KEY_X = "offset_x"
    private const val KEY_Y = "offset_y"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Saved offset (px, relative to centre) or null if the user never moved it. */
    fun getOffset(context: Context): Offset? {
        val p = prefs(context)
        if (!p.contains(KEY_X) || !p.contains(KEY_Y)) return null
        return Offset(
            p.getFloat(KEY_X, 0f),
            p.getFloat(KEY_Y, 0f)
        )
    }

    fun setOffset(context: Context, offset: Offset) {
        prefs(context).edit {
            putFloat(KEY_X, offset.x)
            putFloat(KEY_Y, offset.y)
        }
    }
}
