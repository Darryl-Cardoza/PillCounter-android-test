package com.rite.pillcounting.feature.dispenseScan.domain.model

import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.compose.ContainerStatus

enum class DispenseStage { PRE_RX, PRE_NDC, COUNTING }

data class DispenseScanUiState(
    val stage: DispenseStage = DispenseStage.PRE_RX,
    val scanType: String = CountType.FIXED.toString(),

    val drugName: String = "",
    val ndc: String = "",
    val rxNo: String? = null,
    val qty: String? = null,
    val selectedBucketId: String = "",
    val barcodeImagePath: String? = null,

    val ndcScannedValue: String = "",
    val ndcDrugName: String = "",

    val selectedContainerStatus: ContainerStatus = ContainerStatus.SEALED,

    val showRxDetails: Boolean = false,
    val showNdcDetails: Boolean = false,
    val showInvalidScanDialog: Boolean = false,
    val showNdcNotFoundDialog: Boolean = false,
    val showNdcEquivalenceDialog: Boolean = false,

    // Transient toast signals — bumped to trigger a one-shot toast in the UI.
    val scanNdcToastTick: Int = 0,
    val ndcMismatchToastTick: Int = 0,

    val isLoading: Boolean = false,
    val error: String? = null,

    // HL7/PMS-driven entry: when true the user landed here from a PMS
    // notification, the txn already exists, and the RX scan stage is skipped.
    val isFromHl7: Boolean = false,
    // Expected NDC from HL7 (= the drug PMS told us to dispense). Used to
    // detect substitute / mismatch when the user scans the container.
    val hl7ExpectedNdc: String? = null,
    val isSubstituteConfirmed: Boolean = false,

    val txnId: Long = 0L,

    val isHazardous: Boolean = false,
)
