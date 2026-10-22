package com.dispensesure.retail.feature.dispense.data

import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dispense.domain.DispenseWorkflow
import javax.inject.Inject

/** A dispense's steps and the step it opens on. Shared by the count screen and the audit log. */
class DispenseStepResolver @Inject constructor(
    private val preferenceHelper: PreferenceHelper,
    private val regionProvider: RegionProvider,
    private val dispenseCountRepository: DispenseCountRepository,
) {

    /** The dispense's steps, from its drug and the double-count / back-count settings. */
    fun steps(isTxnLoaded: Boolean, drugType: String?): List<StepState> =
        DispenseWorkflow.steps(
            isTxnLoaded = isTxnLoaded,
            drugType = drugType,
            doubleCountOn = preferenceHelper.isRequireDoubleCountEnabled(),
            controlDrugTypes = preferenceHelper.getControlDrugTypes(regionProvider.current),
            backCountOn = preferenceHelper.isRequireBackCountEnabled(),
        )

    /** The step counting starts on: the saved one, else the latest counted, else the first count step. */
    suspend fun countingStartStep(
        txnId: Long,
        steps: List<StepState>,
        savedWorkflowStep: String?,
        drugType: String?,
    ): StepState = DispenseWorkflow.startStep(
        steps = steps,
        savedStep = savedWorkflowStep?.let { runCatching { StepState.valueOf(it) }.getOrNull() },
        latestCountStep = dispenseCountRepository.latestCountStep(txnId),
        drugType = drugType,
    )

    /** Where a reopened order lands: SCAN until its container is scanned, then the counting start step. */
    suspend fun resumeStep(
        txnId: Long,
        isContainerScanned: Boolean,
        savedWorkflowStep: String?,
        drugType: String?,
    ): StepState =
        if (!isContainerScanned) StepState.SCAN
        else countingStartStep(txnId, steps(isTxnLoaded = true, drugType = drugType), savedWorkflowStep, drugType)
}
