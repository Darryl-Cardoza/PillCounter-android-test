package com.rite.pillcounting.feature.dashboard.presentation.compose

import Screen
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonSingleSelectDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.common.navigateSafely
import com.rite.pillcounting.core.utils.compose.DashBoardIcon
import com.rite.pillcounting.core.utils.compose.bounceClick
import com.rite.pillcounting.feature.dashboard.presentation.viewmodel.DashboardViewModel
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dashboard section for displaying the "Regular Count" workflow.
 *
 * This composable renders:
 * - A regular count icon (clickable → navigates to ScanBarcode screen).
 * - Title and description labels (clickable for the same navigation).
 * - Status chips showing completed and partial regular counts.
 *
 * @param completedRegularCount Number of completed regular counts (as string for direct rendering).
 * @param partialRegularCount Number of partially completed regular counts.
 * @param navController Used for navigation between screens.
 */
@Composable
fun RegularCountSection(
    completedRegularCount: String,
    partialRegularCount: String,
    navController: NavController,
    onNavigate: () -> Unit,
    viewModel: DashboardViewModel
) {
    val dimens = AppTheme.dimens
    var showCreateBatchDialog by remember { mutableStateOf(false) }
    var showBucketSelectDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier.bounceClick {
                showCreateBatchDialog = true
                onNavigate()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Regular count icon (click → ScanBarcode)
            DashBoardIcon(
                outerCircleColor = MaterialTheme.colorScheme.primary,
                innerColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(responsiveDp(120.dp)),
                outerSize = 120.dp,
                innerSize = 90.dp,
                contentDescription = stringResource(R.string.stock_count)
            )

            Spacer(modifier = Modifier.height(responsiveDp(24.dp)))

            // Regular Count title (click → ScanBarcode)
            Text(
                text = stringResource(R.string.stock_count),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(modifier = Modifier.height(responsiveDp(10.dp)))

            // Description label (click → ScanBarcode)
            Text(
                text = stringResource(R.string.start_a_new_inventory_count),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.extendedColors.textColor,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Status row (Completed + Partial counts)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimens.smallMedium,
                    end = dimens.smallMedium,
                    bottom = dimens.small
                ),
            horizontalArrangement = Arrangement.spacedBy(responsiveDp(14.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Completed status
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        navController.navigateSafely(
                            Screen.History.createRoute(HistoryMode.REGULAR)
                        )
                    }
            ) {
                StatusChip(
                    text = "$completedRegularCount ${stringResource(R.string.completed)}",
                    backgroundColor = AppTheme.extendedColors.statusChipBackgroundOnPrimary.copy(alpha = 0.5f),
                    textColor = MaterialTheme.colorScheme.primary,
                    iconRes = R.drawable.complete,
                    iconTint = MaterialTheme.colorScheme.primary,
                )
            }

            // Partial status (click → ResumeRegularCounts screen)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        navController.navigateSafely(
                            Screen.PartialCountsScreen.route
                        )
                    }
            ) {
                StatusChip(
                    text = "$partialRegularCount ${stringResource(R.string.partial)}",
                    backgroundColor = AppTheme.extendedColors.statusChipBackgroundOnPrimary.copy(alpha = 0.5f),
                    textColor = MaterialTheme.colorScheme.primary,
                    iconRes = R.drawable.partial,
                    iconTint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showCreateBatchDialog) {
        val options = listOf(
            stringResource(R.string.stock_count_dialog_option_first),
            stringResource(R.string.stock_count_dialog_option_second),
        )

        val noLastBatchMessage = stringResource(R.string.no_last_batch_available)

        CommonSingleSelectDialog(
            title = stringResource(R.string.stock_count_dialog_title),
            options = options,
            selectedIndex = 0,
            onCancel = { showCreateBatchDialog = false },
            onOk = { index ->

                when (index) {
                    0 -> {
                        showBucketSelectDialog = true
                    }

                    1 -> {
                        scope.launch {
                            val batch = withContext(Dispatchers.IO) {
                                viewModel.getLastInProgressBatch()
                            }
                            if (batch != null) {
                                navController.navigate(Screen.Batch.createRoute(batch.batchId))
                            } else {
                                showToast(
                                    context = context,
                                    message = noLastBatchMessage,
                                    duration = Toast.LENGTH_SHORT
                                )
                            }
                        }
                    }
                }

                showCreateBatchDialog = false
            },
            distanceBetweenOptions = 2.dp
        )
    }

    if (showBucketSelectDialog) {
        val bucketList = viewModel.getBucketList()

        CommonSingleSelectDialog(
            title = stringResource(R.string.select_bucket),
            options = bucketList,
            // No preselection — the user must deliberately tap a bucket before OK
            // is enabled. Prevents silently committing the first bucket.
            selectedIndex = null,
            onCancel = { showBucketSelectDialog = false },
            onOk = { index ->
                if (index in bucketList.indices) {
                    viewModel.createBatch(bucketList[index])
                }
                showBucketSelectDialog = false
            },
            distanceBetweenOptions = 2.dp
        )
    }
}