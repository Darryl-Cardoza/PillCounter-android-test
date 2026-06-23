package com.rite.pillcounting.feature.dispenseFlow.domain.model

import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.compose.ContainerStatus
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem

enum class DispenseStage { QUEUE, PRE_RX, PRE_NDC, COUNTING }

data class DispenseFlowUiState(
    val stage: DispenseStage = DispenseStage.PRE_RX,
    val queueItems: List<QueueItem.Dispense> = emptyList(),
    val selectedQueueFilter: KpiFilter? = null,
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
    val ndcDrugType: String? = null,
    // Strength + dosage form from the NDC drug API (active_ingredients[0].strength
    // and dosage_form[0]). Surfaced on the NDC verification sheet.
    val ndcStrength: String? = null,
    val ndcDosageForm: String? = null,

    val selectedContainerStatus: ContainerStatus = ContainerStatus.SEALED,

    val showRxDetails: Boolean = false,
    val showNdcDetails: Boolean = false,
    val showInvalidScanDialog: Boolean = false,
    val showNdcNotFoundDialog: Boolean = false,
    val showNdcEquivalenceDialog: Boolean = false,

    // Transient toast signals — bumped to trigger a one-shot toast in the UI.
    val scanNdcToastTick: Int = 0,
    val ndcMismatchToastTick: Int = 0,
    val txnNotFoundToastTick: Int = 0,
    // Fired when a scanned NDC is rejected by the batch PMS allowlist.
    val ndcNotAllowedToastTick: Int = 0,
    val ndcNotAllowedValue: String = "",
    // Fired during the VIAL step when a scanned vial barcode's RX does not match
    // the active transaction's RX number.
    val vialRxMismatchToastTick: Int = 0,

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

    // RX duplicate check: shown when a scanned RX already has a PARTIAL txn in the DB.
    val showContinueRxDialog: Boolean = false,
    // RX on-hold check: shown when the found txn is in ON_HOLD status.
    val showOnHoldDialog: Boolean = false,

    // Stock-count batch association. 0L means no batch (dispense flow or stock count
    // started without a batch context).
    val batchId: Long = 0L,

    // NDC allowlist for batch Scan-Pills from a PMS batch. Non-empty only when the
    // batch was PMS-sourced (or the user had a specific active NDC on the card).
    // onNdcBarcodeRead rejects any scanned NDC that isn't in this set.
    // Empty = no restriction (manually-started batch or plain dispense flow).
    val allowedNdcs: Set<String> = emptySet(),

    // One-shot navigation signal: non-null after a SEALED stock bottle is confirmed.
    // The screen observes this and navigates back to the batch, then clears it.
    val navigateToBatchId: Long? = null,

    // One-shot signal: set true when a transaction completes and the queue is empty.
    val navigateToDashboard: Boolean = false,
)
