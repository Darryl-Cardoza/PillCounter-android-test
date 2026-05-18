package com.rite.pillcounting.feature.history.presentation

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toDateString
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toTimeString
import com.rite.pillcounting.feature.batchCount.presentation.compose.BatchNdcCard
import com.rite.pillcounting.feature.batchCount.presentation.viewmodel.BatchViewModel
import com.rite.pillcounting.feature.countResume.presentation.compose.HeadlineBar
import com.rite.pillcounting.feature.history.presentation.compose.BatchStockCountPdfExporter
import com.rite.pillcounting.ui.theme.AppTheme
import java.io.File

@Composable
fun BatchHistoryDetailScreen(
    navController: NavController,
    viewModel: BatchViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val displayBatchId by viewModel.displayBatchId.collectAsStateWithLifecycle()
    val batchEntity by viewModel.batchEntity.collectAsStateWithLifecycle()
    val uniqueNdcCount by viewModel.uniqueNdcCount.collectAsStateWithLifecycle()
    val drugGroups by viewModel.drugGroups.collectAsStateWithLifecycle()

    val pdfExporter = remember { BatchStockCountPdfExporter(context) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var expandedDrugId by remember { mutableStateOf<Long?>(null) }

    val batchTitle = if (displayBatchId != 0L) "Batch #$displayBatchId" else "Batch"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        HeadlineBar(
            navController = navController,
            title = batchTitle,
            searchQuery = "",
            showSearch = false,
            isMultiSelectMode = false,
            isAllSelected = false,
            hasSelection = false,
            showDelete = true,
            onSearchClick = {},
            onSearchChange = {},
            onDeleteClick = { showDeleteDialog = true },
            onCancelClick = {},
            onConfirmDelete = {},
            onSelectAll = {},
            showSearchIcon = false,
            showPdfIcon = true,
            onPdfClick = {
                val file = pdfExporter.generate(
                    batchId = displayBatchId,
                    uniqueNdcCount = uniqueNdcCount,
                    drugGroups = drugGroups,
                    batchStatus = batchEntity?.status?.name ?: "INPROGRESS",
                    startDateTime = batchEntity?.startDateTime,
                    userName = viewModel.getCurrentUser()
                )
                file?.let { shareBatchPdf(context, it) }
            }
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Info row (outside card) ───────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.total_ndc_count),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.extendedColors.textColor
                        )
                        Text(
                            text = uniqueNdcCount.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (batchEntity?.status?.name == "COMPLETED" && batchEntity?.endDateTime != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.completed_on
                                ),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f)
                            )
                            Text(
                                text =
                                    "${batchEntity!!.endDateTime!!.toDateString()} ${batchEntity!!.endDateTime!!.toTimeString()}",
                                fontSize = 14.sp,
                                color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f)
                            )
                        }
                    } else if (batchEntity?.status?.name != "COMPLETED") {
                        Text(
                            text = stringResource(R.string.partial),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // ── Notes card (collapsible) ──────────────────────────────────────
            if (!batchEntity?.note.isNullOrBlank()) {
                item {
                    NotesCard(note = batchEntity?.note ?: "")
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // ── Drug group list ──────────────────────────────────────────────
            if (drugGroups.isNotEmpty()) {

                items(drugGroups, key = { it.drugId ?: 0L }) { group ->
                    BatchNdcCard(
                        group = group,
                        isExpanded = expandedDrugId == group.drugId,
                        onExpandToggle = {
                            expandedDrugId =
                                if (expandedDrugId == group.drugId) null else group.drugId
                        }
                    )
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_items_in_that_batch_yet),
                            color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        CommonDialog(
            message = stringResource(R.string.delete_item_text),
            title = stringResource(R.string.confirm_delete_title),
            confirmText = stringResource(R.string.delete),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.deleteBatch { navController.popBackStack() }
                showDeleteDialog = false
            },
            onCancel = { showDeleteDialog = false }
        )
    }
}

private fun shareBatchPdf(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(com.rite.pillcounting.R.string.pdf_open_stock_report)))
}

@Composable
private fun NotesCard(note: String) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.notes),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(responsiveDp(22.dp))
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f)
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

