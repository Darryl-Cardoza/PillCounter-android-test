package com.rite.pillcounting.feature.countResume.presentation

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.countResume.presentation.compose.PartialBatchRow
import com.rite.pillcounting.feature.countResume.presentation.viewmodel.PartialCountsViewModel
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun PartialCountsScreen(
    navController: NavController,
    viewModel: PartialCountsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<PartialBatchItem>()) }

    var showDeleteDialog by remember { mutableStateOf(false) }

    val partialList = uiState.batches
    val filteredList = partialList.filter {
        it.batchId.contains(searchQuery, ignoreCase = true)
    }

    val isAllSelected =
        partialList.isNotEmpty() &&
                selectedItems.size == partialList.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
    ) {

        HeadlineBar(
            navController = navController,
            title = stringResource(R.string.partial_stock_counts),
            searchQuery = searchQuery,
            showSearch = showSearch,
            isMultiSelectMode = isMultiSelectMode,
            isAllSelected = isAllSelected,
            hasSelection = selectedItems.isNotEmpty(),
            showDelete = true,

            onSearchClick = {
                showSearch = !showSearch
                if (!showSearch) searchQuery = ""
            },

            onSearchChange = {
                searchQuery = it
            },

            onDeleteClick = {
                isMultiSelectMode = true
            },

            onCancelClick = {
                isMultiSelectMode = false
                selectedItems = emptySet()
            },

            onConfirmDelete = {
                showDeleteDialog = true
            },

            onSelectAll = {
                selectedItems =
                    if (isAllSelected) emptySet()
                    else partialList.toSet()
            },
            onBackClick = {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Dashboard.route) { inclusive = false }
                }
            }
        )

        // Loading state
        if (uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        }
        // Error state
        else if (uiState.error != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppTheme.extendedColors.primaryBackground),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = uiState.error ?: "An error occurred",
                    color = AppTheme.extendedColors.textColor
                )
            }
        }
        // Empty state
        else if (filteredList.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppTheme.extendedColors.primaryBackground),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = if (searchQuery.isNotEmpty())
                        stringResource(R.string.no_results_found)
                    else
                        stringResource(R.string.no_partial_counts),
                    color = AppTheme.extendedColors.textColor
                )
            }
        }
        // List of batches
        else {
            LazyColumn {

                items(filteredList) { item ->

                    PartialBatchRow(
                        title = "${stringResource(R.string.batch)} ${item.batchId}",
                        dateTime = item.dateTime,
                        bucketId = item.bucketId,
                        count = item.count,

                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = selectedItems.contains(item),

                        onSelect = {
                            selectedItems =
                                if (selectedItems.contains(item))
                                    selectedItems - item
                                else
                                    selectedItems + item
                        },

                        onClick = {
                            navController.navigate(Screen.Batch.createRoute(item.entityBatchId))
                        },

                        onMoreClick = {
                            // future actions
                        }
                    )
                }
            }
        }
    }


    if (showDeleteDialog) {
        CommonDialog(
            message = stringResource(R.string.confirm_delete_batches),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),

            onConfirm = {
                viewModel.deleteSelectedBatches(selectedItems.toList())
                selectedItems = emptySet()
                isMultiSelectMode = false
                showDeleteDialog = false
            },

            onCancel = {
                showDeleteDialog = false
            }
        )
    }
}

data class PartialBatchItem(
    val entityBatchId: Long,
    val batchId: String,
    val dateTime: String,
    val count: String,
    val bucketId: String
)