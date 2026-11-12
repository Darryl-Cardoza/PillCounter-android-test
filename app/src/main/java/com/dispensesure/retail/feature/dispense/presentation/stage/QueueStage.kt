package com.dispensesure.retail.feature.dispense.presentation.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dispensesure.retail.feature.dashboard.domain.model.KpiFilter
import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem
import com.dispensesure.retail.feature.dispense.presentation.compose.DispenseQueuePanel
import com.dispensesure.retail.ui.theme.AppTheme

/** Share of the screen width the landscape queue panel takes; the top bar clears the same share. */
fun queuePanelWidthFraction(isTablet: Boolean): Float = if (isTablet) 0.4f else 0.45f

/**
 * Shows the dispense transaction list on top of the camera preview.
 * Layout adapts per device form factor and orientation.
 */
@Composable
fun BoxScope.QueueStage(
    queueItems: List<QueueItem.Dispense>,
    selectedFilter: KpiFilter?,
    isLandscape: Boolean,
    isTablet: Boolean,
    onFilterSelected: (KpiFilter) -> Unit,
    onItemTapped: (txnId: Long) -> Unit,
    onHomeTapped: () -> Unit,
) {
    val panelModifier = when {
        // Landscape (tablet or phone): panel on the right side.
        isLandscape -> Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .fillMaxWidth(queuePanelWidthFraction(isTablet))
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
        // Tablet portrait: panel anchored at bottom half.
        isTablet -> Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        // Phone portrait: bottom sheet anchored at bottom half.
        else -> Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.55f)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    }
    Box(modifier = panelModifier.background(AppTheme.extendedColors.secondaryBackground)) {
        DispenseQueuePanel(
            items = queueItems,
            selectedFilter = selectedFilter,
            onFilterSelected = onFilterSelected,
            onItemClick = onItemTapped,
            onHomeClick = onHomeTapped,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
