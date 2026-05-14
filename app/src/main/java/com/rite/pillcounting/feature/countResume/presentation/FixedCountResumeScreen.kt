package com.rite.pillcounting.feature.countResume.presentation

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.FilledButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.feature.countResume.domain.data.FixedCountsEvent
import com.rite.pillcounting.feature.countResume.domain.data.NavigationEvent
import com.rite.pillcounting.feature.countResume.domain.data.ResumeEventFactory
import com.rite.pillcounting.feature.countResume.domain.model.CountItem
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.countResume.presentation.compose.PartialListPanel
import com.rite.pillcounting.feature.countResume.presentation.viewmodel.CountsViewModel
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun FixedCountResumeScreen(
    navController: NavController,
    viewModel: CountsViewModel = hiltViewModel()
) {
    val uiState by viewModel.fixedUiState.collectAsState()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val hasSelection = uiState.selectedItems.isNotEmpty()

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is NavigationEvent.NavigateToPillCount ->
                    navController.navigate(Screen.PillCount.createRoute(event.countType.toString())) {}

                NavigationEvent.NavigateBack -> navController.popBackStack()
                is NavigationEvent.NavigateToScanBarcode -> navController.navigate(
                    Screen.ScanBarcode.createRoute(CountType.FIXED.toString(), ScanType.BARCODE, 0)
                )
            }
        }
    }

    val fixedEventFactory = object : ResumeEventFactory<FixedCountsEvent> {
        override fun toggleMultiSelectMode() = FixedCountsEvent.ToggleMultiSelectMode
        override fun closeMultiSelectMode() = FixedCountsEvent.CloseMultiSelectMode
        override fun deleteClicked() = FixedCountsEvent.DeleteClicked
        override fun itemSwipedToDelete(item: CountItem) = FixedCountsEvent.ItemSwipedToDelete(item)
        override fun forceCompleteTransaction(item: CountItem) =
            FixedCountsEvent.ForceCompleteTransaction(item)
        override fun selectItem(item: CountItem) = FixedCountsEvent.SelectItem(item)
        override fun resumeTransaction(item: CountItem) = FixedCountsEvent.resumeTransaction(item)
    }

    val isAllSelected =
        uiState.fixedCounts.isNotEmpty() &&
                uiState.selectedItems.size == uiState.fixedCounts.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
    ) {
        HeadlineBar(
            navController = navController,
            title = stringResource(R.string.fixed_partial_count_title),
            searchQuery = searchQuery,
            showSearch = showSearch,
            isMultiSelectMode = uiState.isMultiSelectMode,
            hasSelection = hasSelection,
            showDelete = uiState.fixedCounts.isNotEmpty(),
            showSearchIcon = uiState.fixedCounts.isNotEmpty(),
            onSearchClick = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = ""
            },
            onSearchChange = { searchQuery = it },
            onDeleteClick = { viewModel.onFixedEvent(FixedCountsEvent.ToggleMultiSelectMode) },
            isAllSelected = isAllSelected,
            onCancelClick = { viewModel.onFixedEvent(FixedCountsEvent.ToggleMultiSelectMode) },
            onConfirmDelete = { showDeleteDialog = true },
            onSelectAll = { viewModel.onFixedEvent(FixedCountsEvent.SelectAllClicked) }
        )

        Box(modifier = Modifier.weight(1f)) {
            PartialListPanel(
                items = uiState.fixedCounts,
                selectedItems = uiState.selectedItems,
                isMultiSelectMode = uiState.isMultiSelectMode,
                searchQuery = searchQuery,
                onEvent = viewModel::onFixedEvent,
                eventFactory = fixedEventFactory,
                countType = CountType.FIXED.toString(),
                showMultiDeleteConfirmDialog = showDeleteDialog,
                onMultiDelete = {
                    viewModel.onFixedEvent(FixedCountsEvent.DeleteClicked)
                    showDeleteDialog = false
                },
                onCloseDialog = { showDeleteDialog = false }
            )
        }

        if (uiState.isMultiSelectMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HollowButton(
                    text = stringResource(R.string.cancel).uppercase(),
                    onClick = {
                        viewModel.onFixedEvent(FixedCountsEvent.ToggleMultiSelectMode)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(responsiveDp(120.dp))
                )
                FilledButton(
                    text = stringResource(R.string.delete).uppercase(),
                    onClick = {
                        showDeleteDialog = true
                    },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(responsiveDp(120.dp))
                )
            }
        }
    }
}
