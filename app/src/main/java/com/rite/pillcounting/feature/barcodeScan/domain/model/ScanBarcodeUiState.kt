package com.rite.pillcounting.feature.barcodeScan.domain.model

import com.rite.pillcounting.core.utils.compose.ContainerStatus

/**
 * Represents the current state of the ScanBarCodeScreen.
 *
 * @property scanType The type of count being performed (e.g., "fixed", "regular").
 * @property drugName The name of the scanned drug, if any.
 * @property ndc The National Drug Code (NDC) of the scanned drug.
 * @property isLoading Indicates if a background operation is in progress.
 * @property error An error message to display to the user, if any.
 * @property isScannerActive Controls the active state of the barcode analyzer. True to scan, false to pause.
 */
data class ScanBarcodeUiState(
    val scanType: String = "",
    val drugName: String = "",
    val ndc: String = "",
    val expiry: String = "",
    val lotNo: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isScannerActive: Boolean = true,
    val barcodeImagePath: String? = null,
    val hl7ExpectedNdc: String? = null,
    val rxNo: String? = null,
    val qty: String? = null,

    val showNdcNotFoundDialog: Boolean = false,
    val showNdcEquivalenceDialog: Boolean = false,
    val showScanSuccessfullyDialog: Boolean = false,
    val showInvalidScanDialog: Boolean = false,
    val showPmsNdcMismatchDialog: Boolean = false,
    val selectedContainerStatus: ContainerStatus = ContainerStatus.SEALED,
    val batchId: Long = 0,
    val selectedBucketId: String = "",
    val isSubstituteConfirmed: Boolean = false
)