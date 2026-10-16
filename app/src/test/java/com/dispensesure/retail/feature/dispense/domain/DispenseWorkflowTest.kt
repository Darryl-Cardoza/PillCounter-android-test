package com.dispensesure.retail.feature.dispense.domain

import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.models.StepState.CONTAINER_INITIATE
import com.dispensesure.retail.core.models.StepState.CONTAINER_PENDING
import com.dispensesure.retail.core.models.StepState.SCAN
import com.dispensesure.retail.core.models.StepState.TARGET_REVERIFICATION
import com.dispensesure.retail.core.models.StepState.TARGET_VERIFICATION
import com.dispensesure.retail.core.models.StepState.VIAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [DispenseWorkflow]. Plain values in, plain values out. */
class DispenseWorkflowTest {

    private val baseFlow = listOf(SCAN, CONTAINER_INITIATE, TARGET_VERIFICATION, VIAL)
    private val shortFlow = listOf(SCAN, TARGET_VERIFICATION, VIAL)
    private val targetNotMet = DoneOutcome.Error(R.string.pills_count_should_be_greater_than_target_count)

    private fun steps(
        isTxnLoaded: Boolean = true,
        drugType: String? = "REGULAR",
        doubleCountOn: Boolean = false,
        controlDrugTypes: Set<String> = emptySet(),
        backCountOn: Boolean = false,
    ) = DispenseWorkflow.steps(isTxnLoaded, drugType, doubleCountOn, controlDrugTypes, backCountOn)

    private fun onDone(
        step: StepState,
        countedTotal: Int = 0,
        stepTarget: Int = 0,
        orderTarget: Int? = 30,
        isTxnLoaded: Boolean = true,
    ) = DispenseWorkflow.onDone(step, countedTotal, stepTarget, orderTarget, isTxnLoaded)

    // ─────────────────────────── steps ───────────────────────────

    @Test
    fun `a loaded txn whose drug has no type runs the short flow`() {
        assertEquals(shortFlow, steps(drugType = ""))
        assertEquals(shortFlow, steps(drugType = null))
    }

    @Test
    fun `the literal drug type null in any case also runs the short flow`() {
        assertEquals(shortFlow, steps(drugType = "null"))
        assertEquals(shortFlow, steps(drugType = "NULL"))
    }

    @Test
    fun `a drug type with no double or back count runs the base flow`() {
        assertEquals(baseFlow, steps(drugType = "REGULAR"))
    }

    @Test
    fun `double count adds the recount for a control drug type`() {
        assertEquals(
            listOf(SCAN, CONTAINER_INITIATE, TARGET_VERIFICATION, TARGET_REVERIFICATION, VIAL),
            steps(drugType = "CII", doubleCountOn = true, controlDrugTypes = setOf("CII")),
        )
    }

    @Test
    fun `double count skips the recount for a drug type outside the control list`() {
        assertEquals(baseFlow, steps(drugType = "REGULAR", doubleCountOn = true, controlDrugTypes = setOf("CII")))
    }

    @Test
    fun `double count off skips the recount even for a control drug type`() {
        assertEquals(baseFlow, steps(drugType = "CII", doubleCountOn = false, controlDrugTypes = setOf("CII")))
    }

    @Test
    fun `back count adds the second bottle count at the end`() {
        assertEquals(
            listOf(SCAN, CONTAINER_INITIATE, TARGET_VERIFICATION, VIAL, CONTAINER_PENDING),
            steps(backCountOn = true),
        )
    }

    @Test
    fun `double and back count together give the full flow`() {
        assertEquals(
            listOf(SCAN, CONTAINER_INITIATE, TARGET_VERIFICATION, TARGET_REVERIFICATION, VIAL, CONTAINER_PENDING),
            steps(drugType = "CII", doubleCountOn = true, controlDrugTypes = setOf("CII"), backCountOn = true),
        )
    }

    @Test
    fun `with no txn loaded a drug with no type still runs the base flow`() {
        assertEquals(baseFlow, steps(isTxnLoaded = false, drugType = ""))
    }

    @Test
    fun `with no txn loaded a typed drug runs the same base flow`() {
        assertEquals(baseFlow, steps(isTxnLoaded = false, drugType = "REGULAR"))
    }

    // ─────────────────────────── startStep ───────────────────────────

    @Test
    fun `counting resumes on the saved step`() {
        assertEquals(VIAL, DispenseWorkflow.startStep(baseFlow, VIAL, TARGET_VERIFICATION, "REGULAR"))
    }

    @Test
    fun `with no saved step counting resumes on the latest counted step`() {
        assertEquals(TARGET_VERIFICATION, DispenseWorkflow.startStep(baseFlow, null, TARGET_VERIFICATION, "REGULAR"))
    }

    @Test
    fun `a fresh count of a typed drug starts with the pour-out`() {
        assertEquals(CONTAINER_INITIATE, DispenseWorkflow.startStep(baseFlow, null, null, "REGULAR"))
    }

    @Test
    fun `a fresh count of a drug with no type starts on the prescribed count`() {
        assertEquals(TARGET_VERIFICATION, DispenseWorkflow.startStep(shortFlow, null, null, null))
        assertEquals(TARGET_VERIFICATION, DispenseWorkflow.startStep(shortFlow, null, null, "null"))
    }

    @Test
    fun `a saved recount removed by settings moves on to the next step`() {
        assertEquals(VIAL, DispenseWorkflow.startStep(baseFlow, TARGET_REVERIFICATION, null, "CII"))
    }

    @Test
    fun `a saved back count removed by settings falls back to the last step`() {
        assertEquals(VIAL, DispenseWorkflow.startStep(baseFlow, CONTAINER_PENDING, null, "REGULAR"))
    }

    // ─────────────────────────── next ───────────────────────────

    @Test
    fun `next is the following step`() {
        assertEquals(VIAL, DispenseWorkflow.next(baseFlow, TARGET_VERIFICATION))
    }

    @Test
    fun `next after the last step is null`() {
        assertNull(DispenseWorkflow.next(baseFlow, VIAL))
    }

    @Test
    fun `next with no steps is null`() {
        assertNull(DispenseWorkflow.next(emptyList(), TARGET_VERIFICATION))
    }

    @Test
    fun `next from a step not in the list is the first step`() {
        assertEquals(TARGET_VERIFICATION, DispenseWorkflow.next(listOf(TARGET_VERIFICATION, VIAL), SCAN))
    }

    // ─────────────────────────── onDone ───────────────────────────

    @Test
    fun `the pour-out moves on once it holds the order's target`() {
        assertEquals(DoneOutcome.Next, onDone(CONTAINER_INITIATE, countedTotal = 30, orderTarget = 30))
        assertEquals(DoneOutcome.Next, onDone(CONTAINER_INITIATE, countedTotal = 40, orderTarget = 30))
    }

    @Test
    fun `the pour-out below the order's target is turned down`() {
        assertEquals(targetNotMet, onDone(CONTAINER_INITIATE, countedTotal = 29, orderTarget = 30))
    }

    @Test
    fun `the pour-out with no order target does nothing`() {
        assertEquals(DoneOutcome.Ignored, onDone(CONTAINER_INITIATE, countedTotal = 30, orderTarget = null))
    }

    @Test
    fun `the prescribed count moves on when it hits the target exactly`() {
        assertEquals(DoneOutcome.Next, onDone(TARGET_VERIFICATION, countedTotal = 30, stepTarget = 30))
    }

    @Test
    fun `the prescribed count off target is turned down`() {
        assertEquals(targetNotMet, onDone(TARGET_VERIFICATION, countedTotal = 29, stepTarget = 30))
        assertEquals(targetNotMet, onDone(TARGET_VERIFICATION, countedTotal = 31, stepTarget = 30))
    }

    @Test
    fun `the prescribed count with no txn loaded is turned down`() {
        assertEquals(targetNotMet, onDone(TARGET_VERIFICATION, countedTotal = 0, stepTarget = 0, isTxnLoaded = false))
    }

    @Test
    fun `the recount moves on only on the target`() {
        assertEquals(DoneOutcome.Next, onDone(TARGET_REVERIFICATION, countedTotal = 30, stepTarget = 30))
        assertEquals(targetNotMet, onDone(TARGET_REVERIFICATION, countedTotal = 29, stepTarget = 30))
    }

    @Test
    fun `a back count short of its target asks first`() {
        assertEquals(DoneOutcome.ConfirmMismatch, onDone(CONTAINER_PENDING, countedTotal = 3, stepTarget = 5))
    }

    @Test
    fun `a back count on or over its target finishes`() {
        assertEquals(DoneOutcome.Finish, onDone(CONTAINER_PENDING, countedTotal = 5, stepTarget = 5))
        assertEquals(DoneOutcome.Finish, onDone(CONTAINER_PENDING, countedTotal = 6, stepTarget = 5))
    }

    @Test
    fun `Done on the vial step moves on`() {
        assertEquals(DoneOutcome.Next, onDone(VIAL))
    }

    // ─────────────────────────── finish ───────────────────────────

    @Test
    fun `finishing with nothing counted saves nothing`() {
        assertEquals(FinishOutcome.NoCounts, DispenseWorkflow.finish(0, true, TARGET_VERIFICATION, 0, 0))
    }

    @Test
    fun `finishing asks for a note when the setting is on`() {
        assertEquals(FinishOutcome.AskNote, DispenseWorkflow.finish(30, true, VIAL, 0, 0))
    }

    @Test
    fun `finishing a short back count asks for a note`() {
        assertEquals(FinishOutcome.AskNote, DispenseWorkflow.finish(30, false, CONTAINER_PENDING, 5, 3))
    }

    @Test
    fun `finishing otherwise completes`() {
        assertEquals(FinishOutcome.Complete, DispenseWorkflow.finish(30, false, VIAL, 0, 0))
        // Short, but not on the back count.
        assertEquals(FinishOutcome.Complete, DispenseWorkflow.finish(30, false, TARGET_VERIFICATION, 30, 20))
    }

    // ─────────────────────────── offerSkipBackCount ───────────────────────────

    @Test
    fun `the back count is offered as a skip when the pour-out was exactly the target`() {
        assertTrue(DispenseWorkflow.offerSkipBackCount(listOf(VIAL, CONTAINER_PENDING), poured = 30, orderTarget = 30))
    }

    @Test
    fun `the back count is walked into when pills are left`() {
        assertFalse(DispenseWorkflow.offerSkipBackCount(listOf(VIAL, CONTAINER_PENDING), poured = 50, orderTarget = 30))
    }

    @Test
    fun `there is no skip when the vial is the last step`() {
        assertFalse(DispenseWorkflow.offerSkipBackCount(listOf(TARGET_VERIFICATION, VIAL), poured = 30, orderTarget = 30))
    }
}
