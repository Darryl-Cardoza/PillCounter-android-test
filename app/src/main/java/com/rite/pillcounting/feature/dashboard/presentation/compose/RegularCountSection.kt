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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.navigateSafely
import com.rite.pillcounting.core.utils.compose.bounceClick
import com.rite.pillcounting.core.utils.constants.Dimens.extraLarge
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.ui.theme.AppTheme

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
    onNavigate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bounceClick {
                navigateToBarcodeScanRegularCount(navController)
                onNavigate()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Regular count icon (click → ScanBarcode)
            Icon(
                painter = painterResource(id = R.drawable.regular_count),
                contentDescription = stringResource(R.string.regular_count),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(responsiveDp(120.dp))
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Regular Count title (click → ScanBarcode)
            Text(
                text = stringResource(R.string.regular_count),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Description label (click → ScanBarcode)
            Text(
                text = stringResource(R.string.regular_count_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.extendedColors.textColor,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Status row (Completed + Partial counts)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = extraLarge, end = extraLarge, bottom = small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Completed status
            Box(
                modifier = Modifier.clickable(
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
                    backgroundColor = Color.Transparent,
                    textColor = AppTheme.extendedColors.textColor,
                    iconRes = R.drawable.tick,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }

            // Partial status (click → ResumeRegularCounts screen)
            Box(
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    navController.navigateSafely(
                        Screen.ResumeRegularCounts.createRoute(CountType.REGULAR.toString())
                    )

                }
            ) {
                StatusChip(
                    text = "$partialRegularCount ${stringResource(R.string.partial)}",
                    backgroundColor = AppTheme.extendedColors.statusChipBackgroundOnPrimary,
                    textColor = AppTheme.extendedColors.textColor,
                    iconRes = R.drawable.partial,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

fun navigateToBarcodeScanRegularCount(navController: NavController) {
    navController.navigate(Screen.ScanBarcode.createRoute(CountType.REGULAR.toString(),ScanType.RX_LABEL))
}