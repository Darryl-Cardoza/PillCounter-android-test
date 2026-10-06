package com.dispensesure.retail.core.tourguide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.CommonDialog

/**
 * A Compose `Dialog` is its own window, always above the screen's overlay, so a tour card
 * drawn on the screen can never sit over it. During the tour a dialog is rendered here instead:
 * one full-screen window holding the dialog card and the tour overlay, in that z-order.
 * Back and outside taps do nothing; Skip on the tour card is the only way out.
 */
@Composable
fun TourDialogHost(
    state: TourGuideState,
    modifier: Modifier = Modifier,
    onNext: (suspend () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center,
        ) {
            content()
            TourGuideOverlay(
                state = state,
                modifier = Modifier.fillMaxSize(),
                handleBack = false,
                onNext = onNext,
            )
        }
    }
}

/** The "continue or end?" question asked between sections; hosted once at the app shell. */
@Composable
fun TourSectionPrompt(state: TourGuideState) {
    val next = state.pendingSection ?: return
    val done = state.currentStep?.section ?: return
    CommonDialog(
        message = stringResource(R.string.tour_section_done_prompt, done.label(), next.label()),
        confirmText = stringResource(R.string.tour_continue),
        cancelText = stringResource(R.string.tour_end),
        onConfirm = { FtueController.continueTour() },
        onCancel = { FtueController.end() },
    )
}

@Composable
fun TourSection.label(): String = stringResource(
    when (this) {
        TourSection.DASHBOARD -> R.string.tour_section_dashboard
        TourSection.DISPENSE -> R.string.tour_section_dispense
        TourSection.STOCK_COUNT -> R.string.tour_section_stock_count
    },
)
