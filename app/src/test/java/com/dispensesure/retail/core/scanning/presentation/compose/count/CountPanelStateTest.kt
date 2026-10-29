package com.dispensesure.retail.core.scanning.presentation.compose.count

import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/** Unit tests for the step rules on [CountPanelState]. */
class CountPanelStateTest {

    private fun panelState(
        stepType: StepState = StepState.TARGET_VERIFICATION,
        isOpenCount: Boolean = false,
        totalCount: Int = 0,
        targetCount: Int = 30,
    ) = CountPanelState(
        drugName = "Aspirin",
        ndc = "00093-0058",
        strength = "",
        bucket = "",
        dosageForm = "",
        drugImage = null,
        totalCount = totalCount,
        targetCount = targetCount,
        isOpenCount = isOpenCount,
        stepType = stepType,
        steps = emptyList(),
        isVoiceOverEnabled = false,
        detectedCount = 0,
        recentDetectionCounts = ArrayDeque(),
        isAddCooldown = false,
        isIdle = false,
        showGloveIcon = false,
        glovesDetected = false,
        hasStill = false,
        isCapturing = false,
    )

    // ─────────────────────────── isFixed ───────────────────────────

    @Test
    fun `a dispense counting step counts against the target`() {
        assertTrue(panelState(stepType = StepState.TARGET_VERIFICATION).isFixed)
        assertTrue(panelState(stepType = StepState.TARGET_REVERIFICATION).isFixed)
    }

    @Test
    fun `the stock-bottle pour-out step has no target`() {
        assertFalse(panelState(stepType = StepState.CONTAINER_INITIATE).isFixed)
    }

    @Test
    fun `the stock-bottle count after dispensing keeps the target`() {
        assertTrue(panelState(stepType = StepState.CONTAINER_PENDING).isFixed)
    }

    @Test
    fun `an open count has no target`() {
        assertFalse(panelState(isOpenCount = true).isFixed)
    }

    // ─────────────────────────── isAllDone ───────────────────────────

    @Test
    fun `reaching the target is all done`() {
        assertTrue(panelState(totalCount = 30, targetCount = 30).isAllDone)
    }

    @Test
    fun `going past the target is all done`() {
        assertTrue(panelState(totalCount = 31, targetCount = 30).isAllDone)
    }

    @Test
    fun `below the target is not all done`() {
        assertFalse(panelState(totalCount = 29, targetCount = 30).isAllDone)
    }

    @Test
    fun `no target yet is never all done`() {
        assertFalse(panelState(totalCount = 5, targetCount = 0).isAllDone)
    }

    @Test
    fun `an open count is never all done`() {
        assertFalse(panelState(isOpenCount = true, totalCount = 30, targetCount = 30).isAllDone)
    }

    @Test
    fun `the pour-out step is never all done`() {
        assertFalse(panelState(stepType = StepState.CONTAINER_INITIATE, totalCount = 30, targetCount = 30).isAllDone)
    }

    // ─────────────────────────── showProceed ───────────────────────────

    @Test
    fun `both stock-bottle steps finish with Proceed`() {
        assertTrue(panelState(stepType = StepState.CONTAINER_INITIATE).showProceed)
        assertTrue(panelState(stepType = StepState.CONTAINER_PENDING).showProceed)
    }

    @Test
    fun `an open count finishes with Proceed`() {
        assertTrue(panelState(isOpenCount = true).showProceed)
    }

    @Test
    fun `dispense counting and vial steps finish from the circle, not Proceed`() {
        assertFalse(panelState(stepType = StepState.TARGET_VERIFICATION).showProceed)
        assertFalse(panelState(stepType = StepState.TARGET_REVERIFICATION).showProceed)
        assertFalse(panelState(stepType = StepState.VIAL).showProceed)
    }

    // ─────────────────────────── stepTitleOverrides ───────────────────────────

    @Test
    fun `an open count calls its counting step Scan Open Pills`() {
        assertEquals(
            mapOf(StepState.TARGET_VERIFICATION to R.string.scan_open_pills),
            panelState(isOpenCount = true).stepTitleOverrides,
        )
    }

    @Test
    fun `a dispense keeps every step title`() {
        assertEquals(emptyMap<StepState, Int>(), panelState().stepTitleOverrides)
    }
}
