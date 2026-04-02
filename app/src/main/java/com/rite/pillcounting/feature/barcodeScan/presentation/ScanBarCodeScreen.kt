package com.rite.pillcounting.feature.barcodeScan.presentation

import Screen
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.compose.DialogField
import com.rite.pillcounting.core.utils.compose.LabelScannedSuccessfullyDialog
import com.rite.pillcounting.feature.barcodeScan.domain.data.NavigationEvent
import com.rite.pillcounting.feature.barcodeScan.domain.data.ScanBarcodeEvent
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.feature.barcodeScan.presentation.viewmodel.ScanBarcodeViewModel

/**
 * A stateful composable that manages the logic for camera permissions and collects state
 * from the ViewModel. It serves as the entry point for the barcode scanning screen.
 *
 * @param navController The navigation controller for handling screen transitions.
 * @param viewModel The ViewModel that holds the business logic and state for this screen.
 */
@Composable
fun ScanBarCodeScreen(
    navController: NavController,
    scanType: String,
    txnScanType: ScanType,
    viewModel: ScanBarcodeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    // Collect the UI state from the ViewModel in a lifecycle-aware manner.
    val uiState by viewModel.uiState.collectAsState()
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val transactionScanType = viewModel.txnScanType.collectAsState().value

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    // Request camera permission when the composable is first launched if not already granted.
    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.setScanType(txnScanType)
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is NavigationEvent.NavigateToPillCount -> {
                    navController.navigate(Screen.PillCount.createRoute(scanType)) {}
                }

                NavigationEvent.NavigateBack -> {
                    navController.popBackStack()
                }

                is NavigationEvent.NavigateToResumePillCount -> navController.navigate(
                    Screen.ResumeRegularCounts.createRoute(
                        scanType
                    )
                ) {}

            }
        }
    }

    if (uiState.showNdcNotFoundDialog) {
        CommonDialog(
            title = stringResource(R.string.rescan_require),
            message = stringResource(R.string.the_scanned_ndc_does_not_match),
            confirmText = stringResource(R.string.rescane),
            cancelText = "",
            onConfirm = {
                viewModel.hideNdcNotMatchedDialog()
            },
            onCancel = {},
            isSingleButton = true
        )

    }

    if (uiState.showNdcEquivalenceDialog) {
        CommonDialog(
            title = stringResource(R.string.scan_container_qr_code),
            message = stringResource(R.string.scanned_item_is_a_generic_equivalent_to_the_specific_drug),

            confirmText = stringResource(R.string.substitute),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.analyzer.pause()
                viewModel.hideNdcNotMatchedDialog()
                viewModel.showSuccessDialog()
            },
            onCancel = {
                viewModel.hideNdcNotMatchedDialog()
            }
        )
    }

    if(uiState.showInvalidScanDialog){
        CommonDialog(
            title = stringResource(R.string.rescan_require),
            message = stringResource(R.string.scan_correct_label),
            confirmText = stringResource(R.string.rescane),
            cancelText = "",
            onConfirm = {
                viewModel.hideNdcNotMatchedDialog()
            },
            onCancel = {},
            isSingleButton = true
        )
    }

    if (uiState.showScanSuccessfullyDialog) {
        if (transactionScanType == ScanType.RX_LABEL) {
            LabelScannedSuccessfullyDialog(
                fields = listOf(
                    DialogField(stringResource(R.string.rx_number), uiState.rxNo.toString()),
                    DialogField(stringResource(R.string.ndc_number), uiState.ndc),
                    DialogField(stringResource(R.string.drugname), uiState.drugName),
                    DialogField(stringResource(R.string.quantity), uiState.qty.toString())
                ),

                title = stringResource(R.string.label_scanned_successfully),
                onCancel = {
                    viewModel.analyzer.resume()
                    viewModel.hideSuccessDialog()
                },
                onProceed = {
                    viewModel.hideSuccessDialog()
                    viewModel.analyzer.resume()
                    viewModel.onEvent(ScanBarcodeEvent.CreateTxn())

                }
            )
        } else {
            LabelScannedSuccessfullyDialog(
                fields = listOf(
                    DialogField(stringResource(R.string.ndc_number), uiState.ndc),
                    DialogField(stringResource(R.string.drugname), uiState.drugName),
                    ),

                title = stringResource(R.string.label_scanned_successfully),
                onCancel = {
                    viewModel.analyzer.resume()
                    viewModel.hideSuccessDialog()
                },
                onProceed = {
                    viewModel.hideSuccessDialog()
                    viewModel.analyzer.resume()
                    viewModel.onEvent(ScanBarcodeEvent.StartCount())
                }
            )
        }
        viewModel.analyzer.pause()

    }

    // Delegate the UI rendering to the stateless content composable.
    ScanBarCodeScreenContent(
        navController = navController,
        uiState = uiState,
        hasCameraPermission = hasCameraPermission,
        onRequestPermission = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onEvent = viewModel::onEvent,
        analyzer = viewModel.analyzer
    )
}

