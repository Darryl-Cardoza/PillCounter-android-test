package com.rite.pillcounting.feature.barcodeScan.domain.data

import com.rite.pillcounting.core.utils.compose.ContainerStatus

/**
 * Defines the user interactions (events) that can occur on the ScanBarCodeScreen.
 */
sealed interface ScanBarcodeEvent {
    data object RedoScan : ScanBarcodeEvent
    data class StartCount(val receivedFromHL7: Boolean = false) : ScanBarcodeEvent
//    data class BarcodeScanned(val gtin14: String, val imagePath: String, val expiry: String, val lotNo: String) : ScanBarcodeEvent
    data class ScannerError(val exception: Exception) : ScanBarcodeEvent
    data class ScanBarcode(val gtin14: String, val imagePath: String, val expiry: String, val lotNo: String) : ScanBarcodeEvent
    data class CreateTxn(val receivedFromHL7: Boolean = false) : ScanBarcodeEvent
    data class OnContainerStatusChanged(val status: ContainerStatus) : ScanBarcodeEvent
    data class OnBucketSelected(val bucketId: String) : ScanBarcodeEvent
    data object InvalidScan : ScanBarcodeEvent
//    data object CreateBatchId : ScanBarcodeEvent
}