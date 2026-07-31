package com.rite.pillcounting.feature.history.presentation


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.utils.common.FullScreenImageDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toDateString
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toTimeString
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.history.presentation.compose.DrugInfoSection
import com.rite.pillcounting.feature.history.presentation.viewmodel.HistoryDetailsViewModel
import com.rite.pillcounting.ui.theme.AppTheme


@Composable
fun HistoryDetailScreen(
    navController: NavController,
    viewModel: HistoryDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.secondaryBackground),
    ) {
        if (previewImagePath == null) {
        HeadlineBar(
            navController = navController,
            title = uiState.txnInfo?.drugName ?: "",
            searchQuery = "",
            showSearch = false,
            isMultiSelectMode = false,
            isAllSelected = false,
            hasSelection = false,
            showDelete = true,
            onSearchClick = {},
            onSearchChange = {},
            onDeleteClick = { showDeleteConfirmDialog = true },
            onCancelClick = {},
            onConfirmDelete = {},
            onSelectAll = {},
            showSearchIcon = false,
            showPdfIcon = false,
        )
        } // if previewImagePath == null
        val bottleList = BottleInfoJson.decode(uiState.txnInfo?.bottleInfoListJson)
        DrugInfoSection(
            ndc = uiState.txnInfo?.ndc ?: "",
            drugName = uiState.txnInfo?.drugName ?: "",
            // Lot/expiry are no longer stored on dispense transactions (moved to the
            // normalized stock tables); dispense never populated them, so these stay blank.
            expiry = "",
            lotNo = "",
            serialNo = "",
            date = uiState.txnInfo?.createdAt?.toDateString() ?: "",
            time = uiState.txnInfo?.createdAt?.toTimeString() ?: "",
            note = uiState.txnInfo?.note ?: "",
            barcodeImage = bottleList.firstOrNull()?.barcodeImagePath,
            targetCount = uiState.txnInfo?.targetCount,
            transactionDetails = uiState.txnInfo?.txnDetails ?: emptyList(),
            drugType = uiState.txnInfo?.drugType,
            isFromHl7 = uiState.txnInfo?.isComingFromHL7 ?: false,
            isSubstitute = uiState.txnInfo?.isSubstitute ?: false,
            requestedDrugName = uiState.txnInfo?.requestedDrugName ?: "",
            requestedNdc = uiState.txnInfo?.requestedNdc ?: "",
            bottleList = bottleList,
            onImagePreview = { previewImagePath = it }
        )

        if (showDeleteConfirmDialog) {
            CommonDialog(
                message = stringResource(R.string.delete_item_text),
                title = stringResource(R.string.confirm_delete_title),
                confirmText = stringResource(R.string.delete),
                cancelText = stringResource(R.string.cancel),
                onConfirm = {
                    viewModel.deleteTransaction()
                    showDeleteConfirmDialog = false
                    navController.popBackStack()
                },
                onCancel = { showDeleteConfirmDialog = false }
            )
        }
    }

    previewImagePath?.let { imagePath ->
        FullScreenImageDialog(
            imagePath = imagePath,
            onDismiss = { previewImagePath = null }
        )
    }

    }
}


