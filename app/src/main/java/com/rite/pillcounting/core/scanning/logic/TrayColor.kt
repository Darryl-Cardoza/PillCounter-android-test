package com.rite.pillcounting.core.scanning.logic

import android.graphics.Color

/**
 * Represents the detected color of a pill tray.
 *
 * Each entry carries:
 *  - [label]   Human-readable name shown in the UI overlay.
 *  - [bgArgb]  Semi-transparent ARGB used as the label badge background.
 */
enum class TrayColor(val label: String, val bgArgb: Int) {
    WHITE("White",   Color.argb(200, 220, 220, 220)),
    BLACK("Black",   Color.argb(200,  30,  30,  30)),
    GRAY("Gray",     Color.argb(200, 100, 100, 100)),
    YELLOW("Yellow", Color.argb(200, 200, 170,   0)),
    ORANGE("Orange", Color.argb(200, 210, 100,   0)),
    RED("Red",       Color.argb(200, 190,  30,  30)),
    BLUE("Blue",     Color.argb(200,  30,  90, 200)),
    GREEN("Green",   Color.argb(200,  20, 140,  50)),
    PURPLE("Purple", Color.argb(200, 110,  30, 160)),
    UNKNOWN("?",     Color.argb(180,  60,  60,  60)),
}
