package com.dispensesure.retail.feature.dispense.presentation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.dispensesure.retail.R
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.core.scanning.presentation.compose.AddNoteDialog
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.CommonDialog
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountUiState
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowUiState
import com.dispensesure.retail.feature.dispense.presentation.compose.TargetPillsCountDialog

/** The count view model's dialogs. [onTargetCountCancelled] leaves the count after the target dialog's Cancel. */
@Composable
fun DispenseCountDialogs(
    countState: DispenseCountUiState,
    onCountAction: (DispenseCountAction) -> Unit,
    onTargetCountCancelled: () -> Unit,
) {
    if (countState.showTargetCountDialog) {
        TargetPillsCountDialog(
            onDismiss = {
                onCountAction(DispenseCountAction.TargetCountDismissed)
                onTargetCountCancelled()
            },
            onOkay = { count ->
                onCountAction(DispenseCountAction.TargetCountEntered(count))
            }
        )
    }

    if (countState.showNotesDialog) {
        AddNoteDialog(
            onDismiss = { onCountAction(DispenseCountAction.NoteDismissed) },
            onSkip = { onCountAction(DispenseCountAction.NoteSkipped) },
            onSave = { note -> onCountAction(DispenseCountAction.NoteSaved(note)) },
        )
    }

    if (countState.showCountMismatchDialog) {
        CommonDialog(
            message = stringResource(R.string.the_counted_quantity_does_not_match_the_target_count),
            title = stringResource(R.string.count_mismatch),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = { onCountAction(DispenseCountAction.CountMismatchConfirmed) },
            onCancel = { onCountAction(DispenseCountAction.CountMismatchDismissed) },
        )
    }

    if (countState.showAddBottleDialog) {
        CommonDialog(
            message = stringResource(R.string.add_new_bottle_confirm_message),
            title = stringResource(R.string.new_bottle_detected),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { onCountAction(DispenseCountAction.AddBottleConfirmed) },
            onCancel = { onCountAction(DispenseCountAction.AddBottleDismissed) },
        )
    }

    if (countState.showReplaceBottleDialog) {
        CommonDialog(
            message = stringResource(R.string.replace_bottle_confirm_message),
            title = stringResource(R.string.replace_bottle_detected),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { onCountAction(DispenseCountAction.ReplaceBottleConfirmed) },
            onCancel = { onCountAction(DispenseCountAction.ReplaceBottleDismissed) },
        )
    }

    if (countState.showSkipStepDialog) {
        CommonDialog(
            message = stringResource(R.string.skip_step_message),
            title = stringResource(R.string.skip_step_title),
            confirmText = stringResource(R.string.skip),
            cancelText = "",
            onConfirm = { onCountAction(DispenseCountAction.SkipBackCountTapped) },
            onCancel = {},
            isSingleButton = true
        )
    }
}

/**
 * ── Tray color classification popup ─────────────────────────────────────
 * Shown when a tray color is detected during a hazardous transaction and has
 * not been previously classified as hazardous or non-hazardous.
 */
@Composable
fun TrayClassificationDialog(
    pendingColor: TrayColor,
    isHazardousTxn: Boolean,
    onCountAction: (DispenseCountAction) -> Unit,
) {
    LaunchedEffect(pendingColor) {
        Log.i(HAZARDOUS_TAG, "Tray classification popup shown | color=${pendingColor.label} | isHazardousTxn=$isHazardousTxn")
    }
    CommonDialog(
        title = stringResource(R.string.tray_classification_title),
        message = stringResource(R.string.tray_classification_message, pendingColor.label),
        confirmText = stringResource(R.string.yes),
        cancelText = stringResource(R.string.no),
        onConfirm = {
            Log.i(HAZARDOUS_TAG, "Tray classification: user confirmed ${pendingColor.label} as HAZARDOUS → saving")
            onCountAction(DispenseCountAction.TrayClassified(pendingColor, isHazardous = true))
        },
        onCancel = {
            Log.i(HAZARDOUS_TAG, "Tray classification: user rejected ${pendingColor.label} as hazardous → saving false")
            onCountAction(DispenseCountAction.TrayClassified(pendingColor, isHazardous = false))
        },
    )
}

/** The flow view model's dialogs: the on-hold block and the substitute confirmation. */
@Composable
fun DispenseFlowDialogs(
    flowState: DispenseFlowUiState,
    onFlowAction: (DispenseFlowAction) -> Unit,
) {
    // RX transaction is ON_HOLD: block the user with an informational dialog.
    if (flowState.showOnHoldDialog) {
        CommonDialog(
            title = stringResource(R.string.rx_on_hold_title),
            message = stringResource(R.string.rx_on_hold_message),
            confirmText = stringResource(R.string.ok),
            cancelText = "",
            onConfirm = { onFlowAction(DispenseFlowAction.OnHoldDismissed) },
            onCancel = {},
            isSingleButton = true,
        )
    }

    // Substitute drug confirmation: surfaces when the scanned NDC is reported
    // by the server as a generic equivalent of the HL7-expected NDC.
    if (flowState.showNdcEquivalenceDialog) {
        CommonDialog(
            title = stringResource(R.string.scan_container_qr_code),
            message = stringResource(R.string.scanned_item_is_a_generic_equivalent_to_the_specific_drug),
            confirmText = stringResource(R.string.substitute),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { onFlowAction(DispenseFlowAction.SubstituteConfirmed) },
            onCancel = { onFlowAction(DispenseFlowAction.SubstituteDismissed) },
        )
    }
}

/** Reset confirmation for the count. */
@Composable
fun ResetCountDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    CommonDialog(
        title = stringResource(R.string.reset_count_title),
        message = stringResource(R.string.reset_count_message),
        confirmText = stringResource(R.string.reset),
        cancelText = stringResource(R.string.cancel),
        onCancel = onCancel,
        onConfirm = onConfirm,
    )
}
