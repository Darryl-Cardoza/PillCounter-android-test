package com.rite.pillcounting.feature.history.presentation

import Screen
import android.R.attr.maxHeight
import android.R.attr.maxWidth
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.compose.SplitResponsive
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.history.domain.model.HistoryDeleteFilter
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.feature.history.domain.model.ToggleOption
import com.rite.pillcounting.feature.history.presentation.compose.CalendarSection
import com.rite.pillcounting.feature.history.presentation.compose.CountsSection
import com.rite.pillcounting.feature.history.presentation.compose.HistoryPdfExporter
import com.rite.pillcounting.feature.history.presentation.viewmodel.HistoryViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import com.kizitonwose.calendar.compose.rememberCalendarState
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HistoryScreen(
    navController: NavController,
    historyMode: HistoryMode = HistoryMode.NORMAL,
    viewModel: HistoryViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current

    val selectedDate by viewModel.selectedDate.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val batchGroups by viewModel.batchGroups.collectAsState()
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var pendingDeleteFilter by remember { mutableStateOf(HistoryDeleteFilter.ALL) }

    val pdfExporter = remember { HistoryPdfExporter(context) }
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    var showSearch by remember { mutableStateOf(false) }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val topHeightPortrait = maxHeight * 0.45f
    val bottomHeightPortrait = maxHeight * 0.55f
    val startWidthLandScape = maxWidth * 0.45f
    val endWidthLandscape = maxWidth * 0.55f

    val calendarState = rememberCalendarState(
        startMonth = currentMonth.minusMonths(3),
        endMonth = currentMonth,
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = DayOfWeek.SUNDAY
    )
    var selectedOption by rememberSaveable(historyMode) {
        mutableStateOf(
            if (historyMode == HistoryMode.REGULAR) ToggleOption.STOCK else ToggleOption.DISPENSED
        )
    }
    val defaultToComplete = historyMode == HistoryMode.REGULAR || historyMode == HistoryMode.DISPENSE

    LaunchedEffect(historyMode) {
        viewModel.setHistoryMode(historyMode)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {

        if (showSearch) {
            HeadlineBar(
                navController = navController,
                title = "",
                searchQuery = searchQuery,
                showSearch = true,
                isMultiSelectMode = false,
                isAllSelected = false,
                hasSelection = false,
                showDelete = false,
                onSearchClick = {
                    viewModel.setSearchQuery("")
                    showSearch = false
                },
                onSearchChange = { viewModel.setSearchQuery(it) },
                onDeleteClick = {},
                onCancelClick = {},
                onConfirmDelete = {},
                onSelectAll = {}
            )

            val openPdfWith = stringResource(R.string.open_pdf_with)
            CountsSection(
                counts = counts,
                batches = batchGroups,
                initialShowComplete = defaultToComplete,
                isSearchActive = true,
                onExportClick = {
                    val file = pdfExporter.generateHistoryPdf(counts, selectedDate.toString())
                    file?.let { sharePdfFile(context, it, openPdfWith) }
                },
                onDeleteClick = { filter ->
                    pendingDeleteFilter = filter
                    showDeleteConfirmationDialog = true
                },
                selectedOption = selectedOption,
                onOptionSelected = { selectedOption = it },
                onTxnClick = { txnId ->
                    viewModel.selectCurrentTransaction(txnId)
                    navController.navigate(Screen.HistoryDetail.route)
                },
                onBatchClick = { batchId ->
                    val isCompleted = batchGroups.find { it.batchId == batchId }
                        ?.status == com.rite.pillcounting.core.room.models.enums.BatchStatus.COMPLETED
                    if (isCompleted) {
                        navController.navigate(Screen.BatchHistoryDetail.createRoute(batchId))
                    } else {
                        navController.navigate(Screen.Batch.createRoute(batchId))
                    }
                }
            )
        } else {
            SplitResponsive(
                topOrLeft = {
                    CalendarSection(
                        calendarState = calendarState,
                        selectedDate = selectedDate,
                        onDateSelected = { viewModel.selectDate(it) },
                        onBackClick = onBackClick,
                        navController = navController,
                        viewModel = viewModel,
                        showSearch = false,
                        onSearchToggle = { showSearch = true }
                    )
                },
                bottomOrRight = {
                    val openPdfWith = stringResource(R.string.open_pdf_with)
                    CountsSection(
                        counts = counts,
                        batches = batchGroups,
                        initialShowComplete = defaultToComplete,
                        onExportClick = {
                            val file =
                                pdfExporter.generateHistoryPdf(counts, selectedDate.toString())
                            file?.let { sharePdfFile(context, it, openPdfWith) }
                        },
                        selectedOption = selectedOption,
                        onOptionSelected = { selectedOption = it },
                        onDeleteClick = { filter ->
                            pendingDeleteFilter = filter
                            showDeleteConfirmationDialog = true
                        },
                        onTxnClick = { txnId ->
                            viewModel.selectCurrentTransaction(txnId)
                            navController.navigate(Screen.HistoryDetail.route)
                        },
                        onBatchClick = { batchId ->
                            val isCompleted = batchGroups.find { it.batchId == batchId }
                                ?.status == com.rite.pillcounting.core.room.models.enums.BatchStatus.COMPLETED
                            if (isCompleted) {
                                navController.navigate(Screen.BatchHistoryDetail.createRoute(batchId))
                            } else {
                                navController.navigate(Screen.Batch.createRoute(batchId))
                            }
                        }
                    )
                },
                landscapeRatio = startWidthLandScape to endWidthLandscape,
                portraitRatio = topHeightPortrait to bottomHeightPortrait
            )
        }
    }

    if (showDeleteConfirmationDialog) {
        CommonDialog(
            message = stringResource(R.string.confirm_delete_message),
            title = stringResource(R.string.confirm_delete_title),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                showDeleteConfirmationDialog = false
                viewModel.deleteCountsForSelectedDate(selectedOption, pendingDeleteFilter)
            },
            onCancel = { showDeleteConfirmationDialog = false }
        )
    }
}

private fun sharePdfFile(context: Context, file: File, title: CharSequence) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
    }

    context.startActivity(Intent.createChooser(intent, title))
}