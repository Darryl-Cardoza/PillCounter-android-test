package com.dispensesure.retail.core.scanning.engine

import com.dispensesure.retail.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the toast text of a turned-down Add. */
class CaptureResultTest {

    @Test
    fun `each turned-down Add has its own toast`() {
        assertEquals(R.string.add_button_wait, CaptureResult.Reason.Cooldown.toastText())
        assertEquals(R.string.add_zero_detected, CaptureResult.Reason.ZeroCount.toastText())
        assertEquals(R.string.add_camera_not_ready, CaptureResult.Reason.NoFrame.toastText())
        assertEquals(R.string.duplicate_scan_ignored, CaptureResult.Reason.Duplicate.toastText())
    }
}
