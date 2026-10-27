package com.dispensesure.retail.core.scanning.engine

import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.logic.GloveDetection
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.scanning.logic.TrayDetection

/** What the camera and the ML models show right now, for the count screens. */
data class EngineState(
    val detectedPills: List<DetectedPill> = emptyList(),
    val filteredPills: List<DetectedPill> = emptyList(),
    val trayDetections: List<TrayDetection> = emptyList(),
    val gloveDetections: List<GloveDetection> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val isCameraPaused: Boolean = false,
    val showIdleOverlay: Boolean = false,
    val isAddCooldown: Boolean = false,
    val glovesDetected: Boolean = false,
    // Tray colour waiting for the user to say whether it is the hazardous tray.
    val pendingTrayColor: TrayColor? = null,
)
