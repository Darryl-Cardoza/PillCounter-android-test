package com.rite.pillcounting.feature.barcodeScan.presentation

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.feature.barcodeScan.domain.data.ScanBarcodeEvent
import com.rite.pillcounting.feature.barcodeScan.domain.model.ScanBarcodeUiState
import com.rite.pillcounting.feature.barcodeScan.presentation.analyzer.BarcodeAnalyzer
import com.rite.pillcounting.feature.barcodeScan.presentation.compose.ScannerView
import com.rite.pillcounting.feature.barcodeScan.presentation.viewmodel.ScanBarcodeViewModel
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.StepTitleWithSpeech
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Stateless composable for the barcode scanning screen.
 * Handles lifecycle, permissions, and analyzer interaction.
 */
@Composable
fun ScanBarCodeScreenContent(
    navController: NavController,
    uiState: ScanBarcodeUiState,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onEvent: (ScanBarcodeEvent) -> Unit,
    analyzer: BarcodeAnalyzer,
    viewModel: ScanBarcodeViewModel = hiltViewModel(),
    batchId: Long
) {

    val isSoundEnabled = viewModel.isSoundEnabled.collectAsState().value
    val scanType by viewModel.txnScanType.collectAsStateWithLifecycle()
    val latestScanType by rememberUpdatedState(scanType)
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val stepType = if (latestScanType == ScanType.RX_LABEL) {
        StepState.RX_LABEL
    } else if (latestScanType == ScanType.STOCK_COUNT) {
        StepState.STOCK_COUNT
    } else {
        StepState.SCAN
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        ScannerView(
            analyzer = analyzer,
            isActive = uiState.isScannerActive,
            singleScanMode = true,
            onBarcodeScanned = { value, imagePath ->
                val decoder = BarcodeDecoder()
                val cleanedImagePath = imagePath.orEmpty()

                if (value.isBlank()) {
                    onEvent(ScanBarcodeEvent.InvalidScan)
                    return@ScannerView
                }

                val isGs1 = decoder.isGs1Barcode(value)
                val decoded = if (isGs1) decoder.decode(value) else null

                if (latestScanType == ScanType.RX_LABEL) {
                    onEvent(
                        ScanBarcodeEvent.ScanBarcode(
                            gtin14 = value,
                            imagePath = cleanedImagePath,
                            expiry = "",
                            lotNo = ""
                        )
                    )
                    return@ScannerView
                }

                val extractedGtin = if (isGs1) {
                    decoded?.gtin
                } else {
                    decoder.toGtin14(value)
                }

                val finalGtin14 = extractedGtin?.let { decoder.toGtin14(it) } ?: ""

                val isInvalidGtin = finalGtin14.isBlank() ||
                        finalGtin14.length != 14 ||
                        !finalGtin14.all { it.isDigit() }

                if (isInvalidGtin) {
                    onEvent(ScanBarcodeEvent.InvalidScan)
                    return@ScannerView
                }

                onEvent(
                    ScanBarcodeEvent.ScanBarcode(
                        gtin14 = finalGtin14,
                        imagePath = cleanedImagePath,
                        expiry = if (isGs1) decoded?.expirationDate.toString() else "",
                        lotNo = if (isGs1) decoded?.lotNumber.orEmpty() else ""
                    )
                )
            },
            onError = { exception ->
                onEvent(ScanBarcodeEvent.ScannerError(exception))
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            BackButton(
                navController = navController,
                showBox = false,
                onClick = {
                    analyzer.pause()
                    navController.popBackStack()
                }
            )

            if (isLandscape) {
                Spacer(modifier = Modifier.weight(0.3f))
            } else {
                Spacer(modifier = Modifier.weight(0.6f))
            }

            StepTitleWithSpeech(stepType = stepType, isSoundOverride = isSoundEnabled)

            Spacer(modifier = Modifier.weight(1f))
        }
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (uiState.error != null) {
            showToast(context = context, message = uiState.error, duration = Toast.LENGTH_SHORT)
            viewModel.clearToast()
        }

        if (latestScanType == ScanType.STOCK_COUNT)
            Text(
                text = "${stringResource(R.string.batch)} $batchId",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(
                        AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(50.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                color = AppTheme.extendedColors.textColor,
            )
    }

}

@Composable
fun FocusAnimationOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "focusPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(responsiveDp(140.dp))
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .background(
                    color = Color.Transparent,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp)
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
        )
    }
}
