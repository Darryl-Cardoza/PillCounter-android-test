package com.dispensesure.retail.core.scanning.engine

import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.utils.logger.AppLogger

/**
 * Hazardous tray rules: the tray colour on screen vs the saved hazardous colour.
 * Returns what to do; the engine saves preferences, updates state and sends events.
 */
class HazardousTrayMonitor {

    // Same tag as the engine, so `logcat -s PillCountingEngine` still shows [HAZARDOUS] lines.
    private val logger = AppLogger("PillCountingEngine")

    // Tray color detection: always enabled during COUNTING stage.
    // @Volatile ensures main-thread write is visible to Dispatchers.Default immediately.
    @Volatile var isDetectionEnabled = false
        private set
    // true when drug is hazardous AND global "Hazardous Drug" setting is ON → show popup.
    private var hazardousTrayPopupEnabled = false
    // Prevents saving hazardousTrayDetected to DB more than once per transaction.
    private var hazardousTrayResultSaved = false
    // Remembered so resumeAfterIdle() can restore detection without re-calling configure.
    private var lastHazardousByDrug = false
    // Tracks colors already prompted this session to avoid repeated popups per color.
    private val promptedTrayColors = mutableSetOf<TrayColor>()
    // In-memory cache of the single saved hazardous tray color (null if none saved yet).
    private var cachedHazardousColor: String? = null
    // Whether the current transaction is for a hazardous drug.
    private var isHazardousTxn = false
    // Prevents the "using hazardous tray" toast from firing on every frame for non-hazardous txns.
    private var hazardousTrayToastShown = false
    // Colour on the popup, waiting for the user's answer.
    private var pendingTrayColor: TrayColor? = null

    fun configure(isHazardousDrug: Boolean, hazardousSettingOn: Boolean, savedHazardousColor: String?) {
        lastHazardousByDrug = isHazardousDrug
        isHazardousTxn = isHazardousDrug
        isDetectionEnabled = true
        hazardousTrayPopupEnabled = isHazardousDrug && hazardousSettingOn
        hazardousTrayResultSaved = false
        hazardousTrayToastShown = false
        cachedHazardousColor = savedHazardousColor

        logger.d("=== configureHazardous ===")
        logger.d("  drugFlag=$isHazardousDrug  globalSettingOn=$hazardousSettingOn")
        logger.d("  detectionEnabled=$isDetectionEnabled  popupEnabled=$hazardousTrayPopupEnabled  hazardousColor=$cachedHazardousColor")

        if (isHazardousDrug) {
            promptedTrayColors.clear()
            logger.i("Hazardous drug transaction. popupEnabled=$hazardousTrayPopupEnabled hazardousColor=$cachedHazardousColor")
        } else {
            logger.i("Non-hazardous drug — tray detection active for hazardous tray warning. hazardousColor=$cachedHazardousColor")
        }
    }

    // ── Tray color classification + hazardousTrayDetected DB save ────────
    // Runs only for hazardous drug transactions. Saves exactly once per txn.
    //
    // Scenario 1 (setting OFF): immediate save — true if in hazardous list, false otherwise.
    // Scenario 2 (setting ON):  true if in hazardous list (immediate), otherwise show popup;
    //                           user response (yes/no) determines the saved value.
    /** [trayColor] is the first known tray colour of the frame, null when there is none. */
    fun observe(trayColor: TrayColor?): TrayDecision {
        logger.d(
            "Frame: detectionEnabled=$isDetectionEnabled popupEnabled=$hazardousTrayPopupEnabled " +
            "resultSaved=$hazardousTrayResultSaved pending=${pendingTrayColor?.label}"
        )
        if (!isDetectionEnabled) return TrayDecision.None
        logger.d("  First known tray color: ${trayColor?.label ?: "none"} hazardousColor=$cachedHazardousColor isHazardousTxn=$isHazardousTxn")
        if (trayColor == null) return TrayDecision.None

        if (isHazardousTxn && !hazardousTrayResultSaved) {
            return when {
                cachedHazardousColor == null -> {
                    // No hazardous color saved yet — first hazardous transaction.
                    // Show prompt so the user can designate this tray (setting must be ON).
                    if (hazardousTrayPopupEnabled &&
                        pendingTrayColor == null &&
                        trayColor !in promptedTrayColors) {
                        promptedTrayColors.add(trayColor)
                        pendingTrayColor = trayColor
                        logger.i("[HAZARDOUS] First hazardous txn — showing tray classification popup for ${trayColor.label}")
                        TrayDecision.AskUser(trayColor)
                    } else if (!hazardousTrayPopupEnabled) {
                        // Global setting OFF — nothing to classify, save false.
                        logger.i("[HAZARDOUS] No saved color, setting OFF — saving false for ${trayColor.label}")
                        reportResultOnce(false)
                    } else TrayDecision.None
                }
                trayColor.name == cachedHazardousColor -> {
                    // Correct hazardous tray detected — save true.
                    logger.i("[HAZARDOUS] Tray ${trayColor.label} matches hazardous color — saving true")
                    reportResultOnce(true)
                }
                else -> {
                    // A hazardous color is saved but this tray does not match it.
                    if (!hazardousTrayPopupEnabled) {
                        // Global setting OFF — save false silently.
                        logger.i("[HAZARDOUS] Setting OFF, tray ${trayColor.label} != hazardous color — saving false")
                        reportResultOnce(false)
                    } else if (!hazardousTrayToastShown) {
                        // Setting ON — warn user to swap to the correct hazardous tray.
                        hazardousTrayToastShown = true
                        logger.i("[HAZARDOUS] Tray ${trayColor.label} is NOT the hazardous tray — showing warning toast")
                        TrayDecision.WarnNotHazardousTray
                    } else TrayDecision.None
                }
            }
        }
        if (!isHazardousTxn && !hazardousTrayToastShown && trayColor.name == cachedHazardousColor) {
            // Non-hazardous transaction using the hazardous tray — warn the user.
            hazardousTrayToastShown = true
            logger.i("[HAZARDOUS] Hazardous tray ${trayColor.label} detected in non-hazardous transaction — showing toast")
            return TrayDecision.WarnHazardousTrayInUse
        }
        return TrayDecision.None
    }

    /** The user's popup answer. The engine saves a hazardous colour to preferences itself. */
    fun classify(color: TrayColor, isHazardous: Boolean): TrayDecision {
        if (isHazardous) {
            cachedHazardousColor = color.name
            logger.i("Tray color ${color.label} saved as hazardous color → saving true to DB")
        } else {
            logger.i("Tray color ${color.label} not classified as hazardous → saving false to DB")
        }
        pendingTrayColor = null
        return reportResultOnce(isHazardous)
    }

    /** Idle pause: no tray checks and no popup until [resumeAfterIdle]. */
    fun pauseForIdle() {
        pendingTrayColor = null
        isDetectionEnabled = false
        hazardousTrayPopupEnabled = false
        promptedTrayColors.clear()
        isHazardousTxn = false
        hazardousTrayToastShown = false
    }

    fun resumeAfterIdle(hazardousSettingOn: Boolean, savedHazardousColor: String?) {
        pendingTrayColor = null
        // Restore detection flags using the drug flag remembered from configure().
        // Do NOT reset hazardousTrayResultSaved — the transaction is the same, result already saved.
        isDetectionEnabled = true
        isHazardousTxn = lastHazardousByDrug
        hazardousTrayPopupEnabled = lastHazardousByDrug && hazardousSettingOn
        hazardousTrayToastShown = false
        cachedHazardousColor = savedHazardousColor
        if (lastHazardousByDrug) {
            promptedTrayColors.clear()
        }
    }

    // Once per transaction, like the DB save it feeds.
    private fun reportResultOnce(detected: Boolean): TrayDecision {
        if (hazardousTrayResultSaved) return TrayDecision.None
        hazardousTrayResultSaved = true
        return TrayDecision.Save(detected)
    }
}

/** What the engine does after [HazardousTrayMonitor] looks at a tray. */
sealed interface TrayDecision {
    data object None : TrayDecision
    // Save the hazardous tray result on the txn.
    data class Save(val detected: Boolean) : TrayDecision
    // Ask the user whether this colour is the hazardous tray.
    data class AskUser(val color: TrayColor) : TrayDecision
    data object WarnNotHazardousTray : TrayDecision
    data object WarnHazardousTrayInUse : TrayDecision
}
