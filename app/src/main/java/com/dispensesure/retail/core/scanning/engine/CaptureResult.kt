package com.dispensesure.retail.core.scanning.engine

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.dispensesure.retail.R
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

/** Toast text for an Add the engine turned down. */
@StringRes
fun CaptureResult.Reason.toastText(): Int = when (this) {
    CaptureResult.Reason.Cooldown -> R.string.add_button_wait
    CaptureResult.Reason.ZeroCount -> R.string.add_zero_detected
    CaptureResult.Reason.NoFrame -> R.string.add_camera_not_ready
    CaptureResult.Reason.Duplicate -> R.string.duplicate_scan_ignored
}
