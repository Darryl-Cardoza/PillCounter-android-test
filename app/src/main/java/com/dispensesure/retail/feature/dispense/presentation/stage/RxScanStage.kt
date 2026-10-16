package com.dispensesure.retail.feature.dispense.presentation.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.utils.common.drugDisplayName
import com.dispensesure.retail.core.utils.compose.VerifyRxDetailsInlinePanel
import com.dispensesure.retail.core.utils.compose.VerifyRxDetailsSheet
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowUiState
import kotlin.math.roundToInt

// Total rightward drag that dismisses the landscape Rx panel. Measured from
// where the drag started, so a slow pull counts the same as a flick.
private val INLINE_PANEL_DISMISS_DRAG = 56.dp

/**
 * ── RX bottomsheet / inline panel ───────────────────────────────────
 * Dismissible: swipe-down / outside-tap / back all act as Cancel. No
 * transaction exists yet at this point, so nothing is orphaned.
 */
@Composable
fun BoxScope.RxScanStage(
    flowState: DispenseFlowUiState,
    isLandscape: Boolean,
    isTablet: Boolean,
    onFlowAction: (DispenseFlowAction) -> Unit,
) {
    // Landscape inline-panel width. Matches the legacy ScanBarCodeScreen
    // formula: 30% of screen width clamped to a sensible min/max per device
    // class. Without the clamp the panel becomes uselessly narrow on small
    // phones and excessively wide on large tablets.
    val inlinePanelWidth = (LocalConfiguration.current.screenWidthDp.dp * 0.3f).coerceIn(
        if (isTablet) 320.dp else 280.dp,
        if (isTablet) 440.dp else 340.dp,
    )

    if (!isLandscape) {
        VerifyRxDetailsSheet(
            drugName = drugDisplayName(flowState.drugName),
            quantity = flowState.qty.orEmpty(),
            bucket = flowState.selectedBucketId,
            ndcNumber = flowState.ndc,
            rxNumber = flowState.rxNo.orEmpty(),
            strength = flowState.ndcStrength.orEmpty(),
            drugImage = flowState.drugImage,
            onCancel = { onFlowAction(DispenseFlowAction.RxCancelled) },
            onProceed = { onFlowAction(DispenseFlowAction.RxProceedTapped) },
            // The tour only leaves this sheet through its own Next / Skip.
            dismissible = !FtueController.state.isActive,
        )
    } else {
        // How far the panel has been dragged toward the right edge.
        var panelDragPx by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(inlinePanelWidth)
                .offset { IntOffset(panelDragPx.roundToInt(), 0) }
                .background(Color.Transparent)
                // The panel tracks the finger; releasing past the
                // threshold cancels, anything shorter snaps back. Gives
                // landscape the same escape as the portrait sheet.
                .pointerInput(Unit) {
                    val dismissPx = INLINE_PANEL_DISMISS_DRAG.toPx()
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (panelDragPx > dismissPx && !FtueController.state.isActive) {
                                onFlowAction(DispenseFlowAction.RxCancelled)
                            } else {
                                panelDragPx = 0f
                            }
                        },
                        onDragCancel = { panelDragPx = 0f },
                    ) { _, dragAmount ->
                        panelDragPx = (panelDragPx + dragAmount).coerceAtLeast(0f)
                    }
                }
        ) {
            VerifyRxDetailsInlinePanel(
                drugName = drugDisplayName(flowState.drugName),
                quantity = flowState.qty.orEmpty(),
                bucket = flowState.selectedBucketId,
                ndcNumber = flowState.ndc,
                rxNumber = flowState.rxNo.orEmpty(),
                strength = flowState.ndcStrength.orEmpty(),
                onCancel = { onFlowAction(DispenseFlowAction.RxCancelled) },
                onProceed = { onFlowAction(DispenseFlowAction.RxProceedTapped) },
                modifier = Modifier.fillMaxSize(),
                drugImage = flowState.drugImage
            )
        }
    }
}
