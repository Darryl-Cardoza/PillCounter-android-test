package com.dispensesure.retail.feature.dispense.domain

import androidx.annotation.StringRes
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState

/** What Done on a counting step leads to. */
sealed interface DoneOutcome {
    data object Next : DoneOutcome
    data object Finish : DoneOutcome
    // Back count came out short: ask before finishing.
    data object ConfirmMismatch : DoneOutcome
    data class Error(@StringRes val text: Int) : DoneOutcome
    // Pour-out with no order target loaded: Done does nothing, as before.
    data object Ignored : DoneOutcome
}

/** What finishing a dispense leads to. */
sealed interface FinishOutcome {
    data object NoCounts : FinishOutcome
    data object AskNote : FinishOutcome
    data object Complete : FinishOutcome
}

/** The dispense count's step rules. Values in, decisions out; no Room, files or Android. */
object DispenseWorkflow {

    private val targetNotMet = DoneOutcome.Error(R.string.pills_count_should_be_greater_than_target_count)

    /**
     * The steps of one dispense. A loaded txn whose drug has no type runs the short flow.
     * Otherwise the stock bottle is counted first, a control drug is recounted when double
     * count is on, and the bottle is counted again at the end when back count is on.
     */
    fun steps(
        isTxnLoaded: Boolean,
        drugType: String?,
        doubleCountOn: Boolean,
        controlDrugTypes: Set<String>,
        backCountOn: Boolean,
    ): List<StepState> {
        if (isTxnLoaded && isMissingDrugType(drugType)) {
            return listOf(StepState.SCAN, StepState.TARGET_VERIFICATION, StepState.VIAL)
        }

        val steps = mutableListOf(
            StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION
        )

        val shouldDoubleCount = doubleCountOn && controlDrugTypes.contains(drugType.orEmpty())

        if (shouldDoubleCount) steps.add(StepState.TARGET_REVERIFICATION)

        steps.add(StepState.VIAL)

        if (backCountOn) steps.add(StepState.CONTAINER_PENDING)

        return steps
    }

    /** The step counting starts on: the saved one, else the latest counted, else the first count step. */
    fun startStep(
        steps: List<StepState>,
        savedStep: StepState?,
        latestCountStep: StepState?,
        drugType: String?,
    ): StepState = when {
        // No saved step yet — fall back to deriving from details history
        savedStep == null -> latestCountStep
            ?: if (isMissingDrugType(drugType)) StepState.TARGET_VERIFICATION else StepState.CONTAINER_INITIATE
        savedStep in steps -> savedStep
        else -> {
            // Saved step was removed by a settings change (e.g. double-count or
            // back-count toggled off).  Find the nearest valid step:
            //   - For a middle step (TARGET_REVERIFICATION): advance to the next
            //     step that still exists in the workflow.
            //   - For a tail step (CONTAINER_PENDING): fall back to the last
            //     remaining step (VIAL).
            steps.firstOrNull { step -> step.ordinal > savedStep.ordinal } ?: steps.last()
        }
    }

    /** The step after [current]; null when [current] is the last one or there are none. */
    fun next(steps: List<StepState>, current: StepState): StepState? =
        steps.getOrNull(steps.indexOf(current) + 1)

    /**
     * Done on [step]. The pour-out must reach the order's target; the counts must hit the
     * step's target exactly; a back count short of its target asks before finishing.
     */
    fun onDone(
        step: StepState,
        countedTotal: Int,
        stepTarget: Int,
        orderTarget: Int?,
        isTxnLoaded: Boolean,
    ): DoneOutcome = when (step) {
        StepState.CONTAINER_INITIATE -> when {
            orderTarget == null -> DoneOutcome.Ignored
            orderTarget <= countedTotal -> DoneOutcome.Next
            else -> targetNotMet
        }
        StepState.CONTAINER_PENDING ->
            if (stepTarget - countedTotal > 0) DoneOutcome.ConfirmMismatch else DoneOutcome.Finish
        StepState.TARGET_VERIFICATION ->
            if (isTxnLoaded && stepTarget == countedTotal) DoneOutcome.Next else targetNotMet
        StepState.TARGET_REVERIFICATION ->
            if (stepTarget == countedTotal) DoneOutcome.Next else targetNotMet
        else -> DoneOutcome.Next
    }

    /** Finishing the dispense. The note is asked for when the setting is on, or when the back count came out short. */
    fun finish(
        totalPillCount: Int,
        notesDialogOn: Boolean,
        step: StepState,
        stepTarget: Int,
        countedTotal: Int,
    ): FinishOutcome = when {
        totalPillCount == 0 -> FinishOutcome.NoCounts
        notesDialogOn -> FinishOutcome.AskNote
        stepTarget - countedTotal > 0 && step == StepState.CONTAINER_PENDING -> FinishOutcome.AskNote
        else -> FinishOutcome.Complete
    }

    /**
     * True when the back count follows the vial and the pour-out held exactly the order's
     * target: the stock bottle is empty, so the back count is offered as a skip.
     */
    fun offerSkipBackCount(steps: List<StepState>, poured: Int, orderTarget: Int): Boolean =
        next(steps, StepState.VIAL) == StepState.CONTAINER_PENDING && poured == orderTarget

    private fun isMissingDrugType(drugType: String?): Boolean =
        drugType.isNullOrEmpty() || drugType.equals("null", ignoreCase = true)
}
