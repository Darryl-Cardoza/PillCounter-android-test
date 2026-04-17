package com.rite.pillcounting.feature.countResume.presentation

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.feature.countResume.domain.data.NavigationEvent
import com.rite.pillcounting.feature.countResume.domain.data.RegularCountsEvent
import com.rite.pillcounting.feature.countResume.domain.data.ResumeEventFactory
import com.rite.pillcounting.feature.countResume.domain.model.CountItem
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.countResume.presentation.compose.PartialListPanel
import com.rite.pillcounting.feature.countResume.presentation.viewmodel.CountsViewModel
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun RegularCountResumeScreen(
    navController: NavController,
    viewModel: CountsViewModel = hiltViewModel()
) {
    val uiState by viewModel.regularUiState.collectAsState()
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
                    Screen.ScanBarcode.createRoute(CountType.REGULAR.toString(),ScanType.BARCODE,0)
                )
            }
        }
    }

    val regularEventFactory = object : ResumeEventFactory<RegularCountsEvent> {
        override fun toggleMultiSelectMode() = RegularCountsEvent.ToggleMultiSelectMode
        override fun closeMultiSelectMode() = RegularCountsEvent.CloseMultiSelectMode
        override fun deleteClicked() = RegularCountsEvent.DeleteClicked
        override fun itemSwipedToDelete(item: CountItem) = RegularCountsEvent.ItemSwipedToDelete(item)
        override fun forceCompleteTransaction(item: CountItem) = RegularCountsEvent.ForceCompleteTransaction(item)
        override fun selectItem(item: CountItem) = RegularCountsEvent.SelectItem(item)
        override fun resumeTransaction(item: CountItem) = RegularCountsEvent.resumeTransaction(item)
    }

    val isAllSelected =
        uiState.regularCounts.isNotEmpty() &&
                uiState.selectedItems.size == uiState.regularCounts.size


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
    ) {
        HeadlineBar(
            navController = navController,
            title = stringResource(R.string.regular_partial_count_title),
            searchQuery = searchQuery,
            showSearch = showSearch,
            isMultiSelectMode = uiState.isMultiSelectMode,
            hasSelection = hasSelection,
            showDelete = true,
            onSearchClick = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = "" // reset when closing
            },
            onSearchChange = { searchQuery = it },
            onDeleteClick = { viewModel.onRegularEvent(RegularCountsEvent.ToggleMultiSelectMode) },
            isAllSelected = isAllSelected,
            onCancelClick = {viewModel.onRegularEvent(RegularCountsEvent.ToggleMultiSelectMode)},
            onConfirmDelete = {showDeleteDialog = true},
            onSelectAll = {viewModel.onRegularEvent(RegularCountsEvent.SelectAllClicked)}
        )

        PartialListPanel(
            items = uiState.regularCounts,
            selectedItems = uiState.selectedItems,
            isMultiSelectMode = uiState.isMultiSelectMode,
            searchQuery = searchQuery,
            onEvent = viewModel::onRegularEvent,
            eventFactory = regularEventFactory,
            countType = CountType.REGULAR.toString(),
            showMultiDeleteConfirmDialog = showDeleteDialog,
            onMultiDelete = {
                viewModel.onRegularEvent(RegularCountsEvent.DeleteClicked)
                showDeleteDialog = false
            },
            onCloseDialog = {showDeleteDialog = false }
        )
    }
}
