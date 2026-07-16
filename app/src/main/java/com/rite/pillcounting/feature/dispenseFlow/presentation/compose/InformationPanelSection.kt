package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.domain.data.PillScanningEvent
import com.rite.pillcounting.core.scanning.domain.model.PillScanningUiState
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils

@Composable
fun InformationPanelSection(
    uiState: PillScanningUiState,
    viewModel: PillScanningViewModel,
    onEvent: (PillScanningEvent) -> Unit,
    filteredPillCount: Int,
    onShowHistory: () -> Unit
) {
    val stepType by viewModel.currentStep.collectAsState()
    val totalCount = if (uiState.scanType == CountType.REGULAR.toString()) {
        uiState.stockCountSessionTotal
    } else {
        uiState.txnDetailHistory.sumOf { it.count }
    }
    val drugName = uiState.drugName
    val targetCount = uiState.targetCount
    val scanType = uiState.scanType
    val onAdd = { onEvent(PillScanningEvent.AddTransactionDetailClicked(filteredPillCount, stepType)) }
    val onDone = { onEvent(PillScanningEvent.FinalDone(stepType = stepType, totalCount)) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // VIAL capture keeps the original bottom strip (CameraActionBar) in both
    // orientations. All other counting steps use the shared full-bleed overlay
    // (CountModeOverlay): top details bar, centered tap-to-add count circle over
    // the live feed, and a bottom progress bar with counted/target.
    if (stepType == StepState.VIAL) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isLandscape) Modifier
                    else Modifier
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
        ) {
            CountModePortrait(
                totalCount = totalCount,
                targetCount = targetCount,
                scanType = scanType,
                detectedCount = filteredPillCount,
                onAdd = onAdd,
                onDone = onDone,
                viewModel = viewModel,
                showHistory = onShowHistory
            )
        }
        return
    }

    // All other counting steps use the shared full-bleed overlay, split by form
    // factor into four variants so each layout can be tuned independently (see
    // CountMode{Phone,Tablet}{Portrait,Landscape}). Mirrors the DashboardScreen
    // form-factor dispatch.
    val isTablet = UserInterfaceUtils.isTablet()
    when {
        isTablet && !isLandscape -> CountModeTabletPortrait(
            totalCount = totalCount,
            targetCount = targetCount,
            scanType = scanType,
            detectedCount = filteredPillCount,
            onAdd = onAdd,
            onDone = onDone,
            viewModel = viewModel,
            drugName = drugName,
            ndc = uiState.ndc,
            strength = uiState.strength,
            bucket = uiState.bucket,
            showHistory = onShowHistory,
            drugImage = uiState.drugImage
        )

        isTablet && isLandscape -> CountModeTabletLandscape(
            totalCount = totalCount,
            targetCount = targetCount,
            scanType = scanType,
            detectedCount = filteredPillCount,
            onAdd = onAdd,
            onDone = onDone,
            viewModel = viewModel,
            drugName = drugName,
            ndc = uiState.ndc,
            strength = uiState.strength,
            bucket = uiState.bucket,
            showHistory = onShowHistory,
            drugImage = uiState.drugImage
        )

        !isTablet && !isLandscape -> CountModePhonePortrait(
            totalCount = totalCount,
            targetCount = targetCount,
            scanType = scanType,
            detectedCount = filteredPillCount,
            onAdd = onAdd,
            onDone = onDone,
            viewModel = viewModel,
            drugName = drugName,
            ndc = uiState.ndc,
            strength = uiState.strength,
            bucket = uiState.bucket,
            showHistory = onShowHistory,
            drugImage = uiState.drugImage
        )

        else -> CountModePhoneLandscape(
            totalCount = totalCount,
            targetCount = targetCount,
            scanType = scanType,
            detectedCount = filteredPillCount,
            onAdd = onAdd,
            onDone = onDone,
            viewModel = viewModel,
            drugName = drugName,
            ndc = uiState.ndc,
            strength = uiState.strength,
            bucket = uiState.bucket,
            showHistory = onShowHistory,
            drugImage = uiState.drugImage
        )
    }
}