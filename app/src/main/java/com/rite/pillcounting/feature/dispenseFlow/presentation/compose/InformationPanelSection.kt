package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.feature.dispenseFlow.domain.data.PillScanningEvent
import com.rite.pillcounting.feature.dispenseFlow.domain.model.PillScanningUiState
import com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (stepType != StepState.VIAL && !isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = drugName,
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isLandscape) {
            CountModeLandscape(
                totalCount = totalCount,
                targetCount = targetCount,
                scanType = scanType,
                detectedCount = filteredPillCount,
                onAdd = onAdd,
                onDone = onDone,
                viewModel = viewModel,
                drugName = drugName,
                showHistory = onShowHistory
            )
        } else {
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
    }
}