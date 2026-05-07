package com.rite.pillcounting.feature.countResume.presentation.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonSingleSelectDialog
import com.rite.pillcounting.core.utils.compose.DrugCountRow
import com.rite.pillcounting.core.utils.compose.DrugCountRowData
import com.rite.pillcounting.core.utils.constants.Dimens.extraSmall
import com.rite.pillcounting.core.utils.constants.Dimens.medium
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.feature.countResume.domain.data.ResumeEvent
import com.rite.pillcounting.feature.countResume.domain.data.ResumeEventFactory
import com.rite.pillcounting.feature.countResume.domain.model.CountItem
import com.rite.pillcounting.feature.countResume.domain.model.FilterType
import com.rite.pillcounting.ui.theme.AppTheme

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun <E : ResumeEvent> PartialListPanel(
    items: List<CountItem>,
    selectedItems: List<CountItem>,
    isMultiSelectMode: Boolean,
    searchQuery: String,
    onEvent: (E) -> Unit,
    eventFactory: ResumeEventFactory<E>,
    countType: String,
    showMultiDeleteConfirmDialog: Boolean,
    onMultiDelete: () -> Unit,
    onCloseDialog: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showForceCompletedDialog by remember { mutableStateOf(false) }
    var showMoreDialog by remember { mutableStateOf(false) }
    var pendingItem by remember { mutableStateOf<CountItem?>(null) }
    var selectedFilter by remember { mutableStateOf(FilterType.ALL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = small, end = small, bottom = extraSmall)
    ) {
        val filteredItems = remember(searchQuery, items, selectedFilter) {
            val searchFiltered = if (searchQuery.isBlank()) {
                items
            } else {
                items.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }

            when (selectedFilter) {
                FilterType.ALL -> searchFiltered
                FilterType.PMS -> searchFiltered.filter { it.isComingFromHL7 }
                FilterType.NON_PMS -> searchFiltered.filter { !it.isComingFromHL7 }
            }
        }

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_data_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.extendedColors.textColor
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(medium),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    DrugCountRow(
                        data = DrugCountRowData(
                            barcodeImage = item.barcodeImage,
                            ndc = item.ndc,
                            drugType = item.drugType,
                            drugName = item.name,
                            date = item.date,
                            bucketId = item.bucketId,
                            pillCount = item.pillCount,
                            targetCount = item.target,
                            countType = CountType.valueOf(countType),
                            isComingFromHL7 = item.isComingFromHL7
                        ),
                        multiSelectMode = isMultiSelectMode,
                        isSelected = selectedItems.contains(item),
                        onSelectChange = { onEvent(eventFactory.selectItem(item)) },
                        onClick = {
                            pendingItem = item
                            showMoreDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showDeleteDialog || showForceCompletedDialog) {
        val message = if (showDeleteDialog) stringResource(R.string.delete_item_text)
        else stringResource(R.string.confirm_force_completed_txn)

        CommonDialog(
            message = message,
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                if (showDeleteDialog) {
                    pendingItem?.let {
                        onEvent(eventFactory.itemSwipedToDelete(it))
                    }
                } else {
                    pendingItem?.let {
                        onEvent(eventFactory.forceCompleteTransaction(it))
                    }

                }
                showDeleteDialog = false
                showForceCompletedDialog = false
            },
            onCancel = {
                showDeleteDialog = false
                showForceCompletedDialog = false
            }
        )
    }


    if (showMultiDeleteConfirmDialog) {
        CommonDialog(
            message = stringResource(R.string.delete_selected_items_text),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                onMultiDelete()
            },
            onCancel = {
                onCloseDialog()
            }
        )
    }

    if (showMoreDialog && pendingItem != null) {
        val options = listOf(
            stringResource(R.string.resume).uppercase(),
            stringResource(R.string.force_complete).uppercase(),
            stringResource(R.string.delete).uppercase()
        )
        CommonSingleSelectDialog(
            title = stringResource(R.string.select_option).uppercase(),
            options = options,
            selectedIndex = 0,
            onCancel = { showMoreDialog = false; pendingItem = null },
            onOk = { index ->
                pendingItem?.let { item ->
                    when (index) {
                        0 -> {
                            onEvent(eventFactory.resumeTransaction(item))
                            pendingItem = null
                        }

                        1 -> showForceCompletedDialog = true

                        2 -> showDeleteDialog = true
                    }
                }
                showMoreDialog = false
            }
        )
    }
}
