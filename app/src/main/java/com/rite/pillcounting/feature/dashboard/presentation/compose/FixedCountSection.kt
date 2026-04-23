package com.rite.pillcounting.feature.dashboard.presentation.compose

import Screen
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.navigateSafely
import com.rite.pillcounting.core.utils.compose.DashBoardIcon
import com.rite.pillcounting.core.utils.compose.bounceClick
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.constants.Dimens.smallMedium
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Dashboard section for displaying the "Fixed Count" workflow.
 *
 * This composable renders:
 * - A fixed count icon that navigates to the barcode scanning screen.
 * - Title and description labels (both clickable for navigation).
 * - Status chips showing completed and partial counts.
 *
 * @param completedFixedCount The number of completed fixed counts (as string for direct UI rendering).
 * @param partialFixedCount The number of partially completed fixed counts.
 * @param navController Navigation controller used for screen transitions.
 */
@Composable
fun FixedCountSection(
    completedFixedCount: String,
    partialFixedCount: String,
    navController: NavController,
    onNavigate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bounceClick {
                navigateToBarcodeScanFixedCount(navController)
                onNavigate()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Fixed count icon leading to ScanBarcode screen
            DashBoardIcon(
                outerCircleColor = MaterialTheme.colorScheme.primary,
                innerColor = MaterialTheme.colorScheme.secondary,
                innerIconRes = R.drawable.fixed_count_inner,
                modifier = Modifier.size(responsiveDp(120.dp)),
                contentDescription = stringResource(R.string.dispense)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Fixed Count title (clickable -> ScanBarcode)
            Text(
                text = stringResource(R.string.dispense),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Description text (clickable -> ScanBarcode)
            Text(
                text = stringResource(R.string.tap_to_scan_rx_labels),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.extendedColors.textColor,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Row of status chips for Completed & Partial
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = smallMedium, end = smallMedium, bottom = small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Completed status
            Box(
                modifier = Modifier.clickable(
                indication = null, interactionSource = remember { MutableInteractionSource() }) {
                navController.navigateSafely(
                    Screen.History.createRoute(HistoryMode.DISPENSE)
                )
            }) {
                StatusChip(
                    text = "$completedFixedCount ${stringResource(R.string.completed)}",
                    backgroundColor = AppTheme.extendedColors.statusChipBackgroundOnSecondary,
                    textColor = MaterialTheme.colorScheme.primary,
                    iconRes = R.drawable.complete,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }

            // Partial status (clickable -> ResumeFixedCounts screen)
            Box(
                modifier = Modifier.clickable(
                indication = null, interactionSource = remember { MutableInteractionSource() }) {
                navController.navigateSafely(
                    Screen.ResumeFixedCounts.createRoute(CountType.FIXED.toString())
                )
            }) {
                StatusChip(
                    text = "$partialFixedCount ${stringResource(R.string.partial)}",
                    backgroundColor = AppTheme.extendedColors.statusChipBackgroundOnSecondary,
                    textColor = MaterialTheme.colorScheme.primary,
                    iconRes = R.drawable.partial,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

fun navigateToBarcodeScanFixedCount(navController: NavController) {
    navController.navigate(Screen.ScanBarcode.createRoute(CountType.FIXED.toString(),ScanType.RX_LABEL,0))
}
