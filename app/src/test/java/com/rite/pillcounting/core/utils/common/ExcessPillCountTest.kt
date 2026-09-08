package com.rite.pillcounting.core.utils.common

import com.rite.pillcounting.core.models.StepState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [OverlayUtils.excessPillCount] — the green/red split behind the
 * pill dots. Pure integer logic, so no Robolectric here (unlike [OverlayUtilsTest],
 * which needs real Canvas drawing).
 */
class ExcessPillCountTest {

    @Test
    fun `stock count has no target so nothing is excess`() {
        assertEquals(
            0,
            OverlayUtils.excessPillCount(
                pillCount = 8,
                targetCount = 0,
                alreadyCounted = 0,
                isDispense = false,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `stock count with a stale non-zero target is still all green`() {
        assertEquals(
            0,
            OverlayUtils.excessPillCount(
                pillCount = 20,
                targetCount = 10,
                alreadyCounted = 0,
                isDispense = false,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `unknown transaction type is treated as no split`() {
        assertEquals(
            0,
            OverlayUtils.excessPillCount(
                pillCount = 8,
                targetCount = 10,
                alreadyCounted = 0,
                isDispense = null,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `parent container step pours out the whole bottle so nothing is excess`() {
        assertEquals(
            0,
            OverlayUtils.excessPillCount(
                pillCount = 30,
                targetCount = 10,
                alreadyCounted = 0,
                isDispense = true,
                stepType = StepState.CONTAINER_INITIATE,
            )
        )
    }

    @Test
    fun `dispense past the target marks the overflow as excess`() {
        assertEquals(
            5,
            OverlayUtils.excessPillCount(
                pillCount = 15,
                targetCount = 10,
                alreadyCounted = 0,
                isDispense = true,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `dispense under the target has no excess`() {
        assertEquals(
            0,
            OverlayUtils.excessPillCount(
                pillCount = 7,
                targetCount = 10,
                alreadyCounted = 0,
                isDispense = true,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `dispense with no target entered yet is all green`() {
        assertEquals(
            0,
            OverlayUtils.excessPillCount(
                pillCount = 12,
                targetCount = 0,
                alreadyCounted = 0,
                isDispense = true,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `already counted pills reduce the remaining target`() {
        assertEquals(
            3,
            OverlayUtils.excessPillCount(
                pillCount = 7,
                targetCount = 10,
                alreadyCounted = 6,
                isDispense = true,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `once the target is met every remaining pill is excess`() {
        assertEquals(
            4,
            OverlayUtils.excessPillCount(
                pillCount = 4,
                targetCount = 10,
                alreadyCounted = 10,
                isDispense = true,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `over-counting past the target does not wrap the remaining target negative`() {
        assertEquals(
            4,
            OverlayUtils.excessPillCount(
                pillCount = 4,
                targetCount = 10,
                alreadyCounted = 14,
                isDispense = true,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }

    @Test
    fun `an empty tray has no excess`() {
        assertEquals(
            0,
            OverlayUtils.excessPillCount(
                pillCount = 0,
                targetCount = 10,
                alreadyCounted = 10,
                isDispense = true,
                stepType = StepState.CONTAINER_PENDING,
            )
        )
    }
}
