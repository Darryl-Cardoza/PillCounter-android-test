package com.dispensesure.retail.feature.dispense.presentation.stage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.dispensesure.retail.core.scanning.domain.model.TxnDetail
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountPanelState
import com.dispensesure.retail.core.scanning.presentation.compose.count.HistoryModeLandscape
import com.dispensesure.retail.core.scanning.presentation.compose.count.HistoryModePortrait
import com.dispensesure.retail.core.scanning.presentation.compose.count.InformationPanelSection
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction

/**
 * Full pill panel — total / target / circle / Add / Done. Every
 * counting step, VIAL included, uses the full-bleed overlay:
 * details bar on top, its own control over the feed, strip at
 * the bottom.
 */
@Composable
fun BoxScope.CountingStage(
    countPanelState: CountPanelState,
    canReset: Boolean,
    onCountAction: (DispenseCountAction) -> Unit,
    onAddTapped: () -> Unit,
    onShowHistory: () -> Unit,
    onResetTapped: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxSize()
    ) {
        InformationPanelSection(
            state = countPanelState,
            onAdd = onAddTapped,
            onDone = { onCountAction(DispenseCountAction.DoneTapped) },
            onShowHistory = onShowHistory,
            onCountSteady = { onCountAction(DispenseCountAction.CountSteadied) },
            onCapture = { onCountAction(DispenseCountAction.VialCaptureTapped) },
            onRedoCapture = { onCountAction(DispenseCountAction.VialRedoTapped) },
            onSaveCapture = { onCountAction(DispenseCountAction.VialSaveTapped) },
            onReset = if (canReset) onResetTapped else null,
        )
    }
}

/** View All: the step's counts take over the screen when the user taps Total Count. */
@Composable
fun CountHistory(
    navController: NavController,
    isLandscape: Boolean,
    countPanelState: CountPanelState,
    stepCounts: List<TxnDetail>,
    selectedImagePath: String?,
    onCountDeleted: (countId: Long) -> Unit,
    onBack: () -> Unit,
    onImageSelected: (String?) -> Unit,
) {
    if (isLandscape) {
        HistoryModeLandscape(
            navController = navController,
            showTargetCount = countPanelState.isFixed,
            targetCount = countPanelState.targetCount,
            totalCount = countPanelState.totalCount,
            txnHistory = stepCounts,
            onDeleteTxn = onCountDeleted,
            drugName = countPanelState.drugName,
            onBack = onBack,
            selectedImagePath = selectedImagePath,
            onImageSelected = onImageSelected,
        )
    } else {
        HistoryModePortrait(
            navController = navController,
            showTargetCount = countPanelState.isFixed,
            targetCount = countPanelState.targetCount,
            totalCount = countPanelState.totalCount,
            txnHistory = stepCounts,
            onDeleteTxn = onCountDeleted,
            drugName = countPanelState.drugName,
            onBack = onBack,
            selectedImagePath = selectedImagePath,
            onImageSelected = onImageSelected,
        )
    }
}
