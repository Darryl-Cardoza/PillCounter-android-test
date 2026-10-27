package com.dispensesure.retail.core.scanning.engine

import android.graphics.Bitmap
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.logic.TrayDetection

/** Outcome of an ADD tap. [Captured] holds what was on screen at the tap, for [PillCountingEngine.saveCountPhoto]. */
sealed interface CaptureResult {
    data class Captured(
        val count: Int,
        val frame: Bitmap,
        val filteredPills: List<DetectedPill>,
        val trays: List<TrayDetection>,
    ) : CaptureResult

    data class Rejected(val reason: Reason) : CaptureResult

    enum class Reason { Cooldown, ZeroCount, NoFrame, Duplicate }
}

/** Paths of the JPEGs written for one ADD; null when a write failed or was not asked for. */
data class SavedPhoto(val imagePath: String?, val rawImagePath: String?)
