package com.rite.pillcounting.feature.dispenseFlow.domain.model

import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.compose.ContainerStatus

enum class DispenseStage { PRE_RX, PRE_NDC, COUNTING }

data class DispenseFlowUiState(
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
    val ndcPackageQty: Int? = null,

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

    // Stock-count only: shown when the user scans an RX label instead of an NDC container.
    val showRxScannedInStockCountDialog: Boolean = false,

    // Stock-count batch association. 0L means no batch (dispense flow or stock count
    // started without a batch context).
    val batchId: Long = 0L,

    // One-shot navigation signal: non-null after a SEALED stock bottle is confirmed.
    // The screen observes this and navigates back to the batch, then clears it.
    val navigateToBatchId: Long? = null,
)
